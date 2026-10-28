/*
 * android-seccomp-run: run a command under a seccomp filter shaped like Android's per-app policy, to reproduce
 * "exit 159 (signal 31)" on an ordinary ARM64 Linux host.
 *
 *   android-seccomp-run [--root <dir>] <allowlist.txt> <cmd> [args...]
 *
 * --root chroots into <dir> first (needs root), then installs the filter: the guest runs under the filter without proot, so
 * `strace -f` from outside can show the syscall number of the SIGSYS that kills it.
 *
 * allowlist.txt holds one arm64 syscall number per line (gen-android-allowlist.py builds it from AOSP's lists). Anything
 * not listed raises SIGSYS (SECCOMP_RET_TRAP), as bionic's filter does. The filter is inherited by every child, including
 * the guest processes proot spawns. Syscall -1 (what a ptracer uses to skip a call) is not listed either: that is the
 * point, a tracer that skips before the filter runs must survive.
 *
 *   gcc -O2 -o android-seccomp-run android-seccomp-run.c      (ARM64 host)
 */
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

#define MAX_ALLOWED 400

#if defined(__aarch64__)
#define NATIVE_AUDIT_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__) /* only so the harness itself can be sanity-checked on an x86 dev machine */
#define NATIVE_AUDIT_ARCH AUDIT_ARCH_X86_64
#else
#error "unsupported architecture"
#endif

int main(int argc, char **argv)
{
	const char *root = NULL;
	unsigned deny = SECCOMP_RET_TRAP; /* NEXTCRAFT_FILTER_ERRNO=1: refuse with ENOSYS instead, so a tracer's log names the syscall */
	if (getenv("NEXTCRAFT_FILTER_ERRNO"))
		deny = SECCOMP_RET_ERRNO | ENOSYS;
	if (argc >= 3 && strcmp(argv[1], "--root") == 0) { /* chroot happens after the allowlist is read, below */
		root = argv[2];
		argc -= 2;
		argv += 2;
	}
	if (argc < 3) {
		fprintf(stderr, "usage: %s [--root <dir>] <allowlist.txt> <cmd> [args...]\n", argv[0]);
		return 2;
	}
	unsigned nr[MAX_ALLOWED];
	int m = 0;
	FILE *f = fopen(argv[1], "r");
	if (!f) {
		perror(argv[1]);
		return 2;
	}
	while (m < MAX_ALLOWED && fscanf(f, "%u", &nr[m]) == 1)
		m++;
	fclose(f);
	if (m == 0 || m > 250) { /* 8-bit BPF jump offsets */
		fprintf(stderr, "allowlist has %d entries\n", m);
		return 2;
	}

	if (root && (chroot(root) < 0 || chdir("/") < 0)) {
		perror("chroot");
		return 2;
	}

	struct sock_filter prog[MAX_ALLOWED + 8];
	int n = 0;
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
	prog[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NATIVE_AUDIT_ARCH, 1, 0);
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, deny);
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
	for (int i = 0; i < m; i++) /* a match jumps over the remaining entries and the TRAP, onto ALLOW */
		prog[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr[i], (unsigned char)(m - i), 0);
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, deny);
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

	struct sock_fprog fprog = { .len = (unsigned short)n, .filter = prog };
	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0 || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &fprog) < 0) {
		perror("seccomp");
		return 2;
	}
	execvp(argv[2], argv + 2);
	perror(argv[2]);
	return 127;
}
