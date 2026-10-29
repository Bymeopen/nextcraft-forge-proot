/*
 * LD_PRELOAD shim for guests that run under proot on Android.
 *
 * Android's per-app seccomp filter raises SIGSYS for syscalls outside bionic's list. Recent musl implements
 * faccessat(..., AT_EACCESS) with faccessat2 (kernel 5.8+), which the filter refuses, so bash dies the first time it
 * searches $PATH. The plain faccessat syscall (48) is allowed. This shim answers faccessat itself.
 *
 * It also answers syscall() for numbers Android refuses and callers can live without. libuv (Node) probes io_uring through
 * syscall(); ENOSYS makes it fall back to its thread pool, the same result as UV_USE_IO_URING=0 without depending on that
 * variable. Calls made by libc internally cannot be intercepted this way (faccessat2 inside musl), only calls through the
 * PLT: that is why faccessat itself is replaced above.
 *
 * AT_EACCESS needs no special care: the kernel sees the app uid for real and effective IDs alike (proot's -0 only fakes
 * the id the guest reads back), so the plain syscall already checks the right thing.
 *
 *   gcc -O2 -shared -fPIC -o libnextcraft-compat.so seccomp-compat.c      (inside the Alpine build container)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdarg.h>
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
	return syscall(SYS_faccessat, dirfd, path, mode) < 0 ? -1 : 0; /* our syscall() below forwards it; it sets errno */
}

/* musl implements eaccess/euidaccess as an internal call to faccessat(AT_EACCESS), which the PLT replacement above cannot
 * reach; bash uses eaccess() for command lookup. */
int eaccess(const char *path, int mode)
{
	return faccessat(AT_FDCWD, path, mode, AT_EACCESS);
}

int euidaccess(const char *path, int mode)
{
	return faccessat(AT_FDCWD, path, mode, AT_EACCESS);
}

#if defined(__aarch64__)
#define NR_io_uring_setup 425
#define NR_io_uring_enter 426
#define NR_io_uring_register 427
#define NR_faccessat2 439

long syscall(long n, ...)
{
	va_list ap;
	long a[6];
	va_start(ap, n);
	for (int i = 0; i < 6; i++)
		a[i] = va_arg(ap, long);
	va_end(ap);

	switch (n) {
	case NR_io_uring_setup:
	case NR_io_uring_enter:
	case NR_io_uring_register:
		errno = ENOSYS;
		return -1;
	case NR_faccessat2:
		return faccessat((int)a[0], (const char *)a[1], (int)a[2], (int)a[3]);
	}

	register long x8 __asm__("x8") = n;
	register long x0 __asm__("x0") = a[0];
	register long x1 __asm__("x1") = a[1];
	register long x2 __asm__("x2") = a[2];
	register long x3 __asm__("x3") = a[3];
	register long x4 __asm__("x4") = a[4];
	register long x5 __asm__("x5") = a[5];
	__asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
	if ((unsigned long)x0 > (unsigned long)-4096L) {
		errno = (int)-x0;
		return -1;
	}
	return x0;
}
#endif
