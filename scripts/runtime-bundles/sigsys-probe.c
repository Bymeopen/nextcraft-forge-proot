/*
 * sigsys-probe: lists which Linux syscalls this Android device's app seccomp filter refuses (SIGSYS).
 *
 * Why: a guest or proot that dies with "exit 159 (signal 31)" was killed by seccomp, and the kernel does not say which
 * syscall. This program issues each candidate syscall once, in its own child process, with a SIGSYS handler that
 * reports the number and makes the call return -ENOSYS, so one run prints the whole table.
 *
 * Freestanding on purpose (no libc): glibc's own startup issues set_robust_list/rseq, which are among the suspects.
 * Static aarch64, shipped in the APK as libsigsys_probe.so and run by proot-exec.sh when the guest dies on a signal.
 *
 *   aarch64-linux-gnu-gcc -static -nostdlib -O2 -fno-builtin -fno-stack-protector -o libsigsys_probe.so sigsys-probe.c
 *
 * `--selftest` raises SIGSYS by hand to check the handler plumbing (useful under qemu-user, which has no filter).
 */
typedef unsigned long u64;
typedef long i64;

#define SYS_write 64
#define SYS_exit_group 94
#define SYS_rt_sigaction 134
#define SYS_rt_sigreturn 139
#define SYS_clone 220
#define SYS_wait4 260
#define SYS_prctl 167
#define SYS_getpid 172
#define SYS_tgkill 131
#define SYS_gettid 178

#define SIGSYS_NR 31
#define SA_SIGINFO 4UL
#define SA_RESTORER 0x04000000UL

static inline i64 sc(i64 n, i64 a, i64 b, i64 c, i64 d, i64 e, i64 f) {
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    register i64 x3 __asm__("x3") = d;
    register i64 x4 __asm__("x4") = e;
    register i64 x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}

/* Signal return trampoline required by the arm64 kernel ABI. */
__asm__(".text\n.globl sigsys_restorer\nsigsys_restorer:\n mov x8, #139\n svc #0\n"
        ".globl _start\n_start:\n mov x0, sp\n bl c_start\n");
extern void sigsys_restorer(void);

static unsigned long slen(const char *s) { unsigned long n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { sc(SYS_write, 1, (i64)s, (i64)slen(s), 0, 0, 0); }
static void outnum(i64 v) {
    char buf[24]; int i = 23; buf[i] = 0;
    int neg = v < 0; u64 u = neg ? (u64)(-v) : (u64)v;
    do { buf[--i] = (char)('0' + u % 10); u /= 10; } while (u);
    if (neg) buf[--i] = '-';
    out(buf + i);
}

static volatile i64 trapped_nr = -1;

/* Layout (arm64 Linux): siginfo si_syscall at +24; ucontext mcontext regs[0] at +184. */
static void on_sigsys(int sig, void *si, void *uc) {
    (void)sig;
    trapped_nr = *(int *)((char *)si + 24);
    *(u64 *)((char *)uc + 184) = (u64)(i64)-38; /* the interrupted syscall now returns -ENOSYS */
}

struct kact { void *handler; u64 flags; void *restorer; u64 mask; };

struct cand { const char *name; int nr; };
#define PTRACE_BAD_REQUEST -1 /* request 0 would be PTRACE_TRACEME; an invalid one is refused harmlessly */
/* arm64 numbers. Arguments are all zero, so each call fails harmlessly (EINVAL/EFAULT) when it is allowed. */
static const struct cand cands[] = {
    {"set_robust_list", 99}, {"rseq", 293}, {"faccessat2", 439}, {"clone3", 435}, {"statx", 291},
    {"getrandom", 278}, {"prlimit64", 261}, {"set_tid_address", 96}, {"readlinkat", 78}, {"brk", 214},
    {"mprotect", 226}, {"newfstatat", 79}, {"getcwd", 17}, {"ptrace", 117}, {"process_vm_readv", 270},
    {"process_vm_writev", 271}, {"wait4", 260}, {"prctl", 167}, {"pipe2", 59}, {"dup3", 24}, {"fcntl", 25},
    {"futex", 98}, {"sigaltstack", 132}, {"getuid", 174}, {"geteuid", 175}, {"uname", 160}, {"personality", 92},
    {"membarrier", 283}, {"memfd_create", 279}, {"copy_file_range", 285}, {"close_range", 436},
    {"openat2", 437}, {"pidfd_open", 434}, {"epoll_pwait2", 441}, {"io_uring_setup", 425}, {"mseal", 462},
    {"futex_waitv", 449}, {"fchmodat2", 452}, {"getdents64", 61}, {"unlinkat", 35}, {"renameat2", 276},
    {"symlinkat", 36}, {"linkat", 37}, {"sched_getaffinity", 123}, {"getrlimit", 163}, {"umask", 166},
    {"chdir", 49}, {"setresuid", 147}, {"setuid", 146}, {"setgid", 144}, {"setgroups", 159}, {"chroot", 51},
    {"mount", 40}, {"capget", 90}, {"capset", 91}, {"kill", 129}, {"tgkill", 131}, {"rt_sigtimedwait", 137},
    {"eventfd2", 19}, {"timerfd_create", 85}, {"inotify_init1", 26}, {"flock", 32}, {"fallocate", 47},
    {"ioctl", 29}, {"splice", 76}, {"sendfile", 71}, {"socket", 198}, {"socketpair", 199},
};

static void report_child(const struct cand *c) {
    trapped_nr = -1;
    i64 r = sc(c->nr, c->nr == 117 ? PTRACE_BAD_REQUEST : 0, 0, 0, 0, 0, 0);
    out("  "); out(c->name); out(" ("); outnum(c->nr); out("): ");
    if (trapped_nr >= 0) { out("BLOCKED (SIGSYS, nr="); outnum(trapped_nr); out(")\n"); }
    else if (r == -38) out("ENOSYS (not implemented by the kernel)\n");
    else if (r < 0 && r > -4096) { out("allowed (errno "); outnum(-r); out(")\n"); }
    else { out("allowed (ret "); outnum(r); out(")\n"); }
    sc(SYS_exit_group, 0, 0, 0, 0, 0, 0);
}

void c_start(u64 *sp) {
    int argc = (int)sp[0];
    char **argv = (char **)(sp + 1);
    struct kact act = { (void *)on_sigsys, SA_SIGINFO | SA_RESTORER, (void *)sigsys_restorer, 0 };
    sc(SYS_rt_sigaction, SIGSYS_NR, (i64)&act, 0, 8, 0, 0);

    if (argc > 1 && argv[1][0] == '-' && argv[1][1] == '-' && argv[1][2] == 's') {
        trapped_nr = -1;
        sc(SYS_tgkill, sc(SYS_getpid, 0, 0, 0, 0, 0, 0), sc(SYS_gettid, 0, 0, 0, 0, 0, 0), SIGSYS_NR, 0, 0, 0);
        out(trapped_nr == -1 ? "selftest: handler did not run\n" : "selftest: handler ran and returned\n");
        sc(SYS_exit_group, trapped_nr == -1, 0, 0, 0, 0, 0);
    }

    out("sigsys-probe: seccomp mode ");
    outnum(sc(SYS_prctl, 21 /* PR_GET_SECCOMP */, 0, 0, 0, 0, 0));
    out(" (0 none, 2 filter)\n");
    for (unsigned i = 0; i < sizeof(cands) / sizeof(cands[0]); i++) {
        i64 pid = sc(SYS_clone, 17 /* SIGCHLD */, 0, 0, 0, 0, 0);
        if (pid == 0) report_child(&cands[i]);
        int status = 0;
        sc(SYS_wait4, pid, (i64)&status, 0, 0, 0, 0);
        if (status & 0x7f) { /* died of a signal the handler could not turn into -ENOSYS (a KILL action) */
            out("  "); out(cands[i].name); out(" ("); outnum(cands[i].nr); out("): KILLED by signal ");
            outnum(status & 0x7f); out("\n");
        }
    }
    sc(SYS_exit_group, 0, 0, 0, 0, 0, 0);
}
