/*
 * LD_PRELOAD shim for guests that run under proot on Android.
 *
 * Android's per-app seccomp filter raises SIGSYS for syscalls outside bionic's list. Recent musl implements
 * faccessat(..., AT_EACCESS) with faccessat2 (kernel 5.8+), which the filter refuses, so bash dies the first time it
 * searches $PATH. The plain faccessat syscall (48) is allowed. This shim answers faccessat itself.
 *
 * AT_EACCESS needs no special care: the kernel sees the app uid for real and effective IDs alike (proot's -0 only fakes
 * the id the guest reads back), so the plain syscall already checks the right thing.
 *
 *   gcc -O2 -shared -fPIC -o libnextcraft-compat.so seccomp-compat.c      (inside the Alpine build container)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int faccessat(int dirfd, const char *path, int mode, int flags)
{
	if (flags & ~(AT_EACCESS | AT_SYMLINK_NOFOLLOW)) {
		errno = EINVAL;
		return -1;
	}
	if (flags & AT_SYMLINK_NOFOLLOW) {
		struct stat st;
		if (fstatat(dirfd, path, &st, AT_SYMLINK_NOFOLLOW) < 0)
			return -1;
		if (S_ISLNK(st.st_mode))
			return 0; /* a symlink's own mode is always rwxrwxrwx */
	}
	/* Raw syscall: the libc faccessat wrapper is the one being replaced. */
	return syscall(SYS_faccessat, dirfd, path, mode) < 0 ? -1 : 0; /* syscall() sets errno itself */
}
