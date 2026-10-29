#!/usr/bin/env python3
"""Make glibc's own syscalls skip what Android's per-app seccomp filter refuses.

Android answers every syscall outside bionic's list with SIGSYS, which kills the process. glibc 2.39 issues set_robust_list
and rseq at every process start (ld.so included) and tries faccessat2/fchmodat2 first, relying on ENOSYS to fall back.
ENOSYS is exactly what is wanted, but the kernel never gets to say it: the filter acts first.

This edits sysdeps/unix/sysv/linux/aarch64/sysdep.h so that INTERNAL_SYSCALL_RAW, the single place all of glibc's internal
syscalls pass through, returns -ENOSYS at compile time for those numbers without executing `svc`. Calls through the public
syscall() function are not affected (libuv's io_uring probing is handled by UV_USE_IO_URING=0).

  glibc-android-syscalls.py <glibc source tree>

Exits non-zero when the file does not look as expected, so a glibc update cannot silently skip the patch.
"""
import os
import sys

OLD = '''# define INTERNAL_SYSCALL_RAW(name, nr, args...)		\\
  ({ long _sys_result;						\\
     {								\\
       LOAD_ARGS_##nr (args)					\\
       register long _x8 asm ("x8") = (name);			\\
       asm volatile ("svc	0	// syscall " # name     \\
		     : "=r" (_x0) : "r"(_x8) ASM_ARGS_##nr : "memory");	\\
       _sys_result = _x0;					\\
     }								\\
     _sys_result; })
'''

NEW = '''/* Android's seccomp filter kills the process on these (SIGSYS); callers all fall back on ENOSYS. arm64 numbers:
   set_robust_list 99, openat2 437, faccessat2 439, rseq 293, fchmodat2 452. */
# define NEXTCRAFT_ANDROID_REFUSED(n) \\
  ((n) == 99 || (n) == 293 || (n) == 437 || (n) == 439 || (n) == 452)

# define INTERNAL_SYSCALL_RAW(name, nr, args...)		\\
  ({ long _sys_result;						\\
     {								\\
       LOAD_ARGS_##nr (args)					\\
       if (__builtin_constant_p (name) && NEXTCRAFT_ANDROID_REFUSED (name)) \\
	 _sys_result = -38; /* -ENOSYS, without entering the kernel */ \\
       else							\\
	 {							\\
	   register long _x8 asm ("x8") = (name);		\\
	   asm volatile ("svc	0	// syscall " # name     \\
			 : "=r" (_x0) : "r"(_x8) ASM_ARGS_##nr : "memory");	\\
	   _sys_result = _x0;					\\
	 }							\\
     }								\\
     _sys_result; })
'''

def main(tree: str) -> int:
    path = os.path.join(tree, "sysdeps/unix/sysv/linux/aarch64/sysdep.h")
    text = open(path).read()
    if NEW in text:
        print("already patched")
        return 0
    if text.count(OLD) != 1:
        print(f"{path}: INTERNAL_SYSCALL_RAW is not as expected; update glibc-android-syscalls.py", file=sys.stderr)
        return 1
    open(path, "w").write(text.replace(OLD, NEW))
    print("patched", path)
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]) if len(sys.argv) == 2 else 2)
