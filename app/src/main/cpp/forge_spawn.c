#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void close_pair(int pair[2]) {
    if (pair[0] >= 0) close(pair[0]);
    if (pair[1] >= 0) close(pair[1]);
}

/**
 * Closes all inherited file descriptors >= 3 in the forked child before execve/execlp
 * so JVM sockets (LocalFormatGateway, OpenRouterRoutingGateway), Keystore/DataStore FDs,
 * and sibling PTY master FDs never leak into child processes.
 */
static void close_extra_fds(void) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir != NULL) {
        int dir_fd = dirfd(dir);
        struct dirent *entry;
        while ((entry = readdir(dir)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd >= 3 && fd != dir_fd) {
                close(fd);
            }
        }
        closedir(dir);
        return;
    }
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0 || max_fd > 65536) max_fd = 1024;
    for (int fd = 3; fd < (int)max_fd; fd++) {
        close(fd);
    }
}

/**
 * Kills and reaps a newly-forked child on early error paths so it never becomes a zombie (<defunct>).
 */
static void kill_and_reap_child(pid_t pid) {
    if (pid <= 0) return;
    kill(pid, SIGKILL);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) break;
    }
}

static void free_spawn_allocations(char **argv, jsize argc, char **envp, jsize envc,
                                   char *cwd, char *output_path) {
    if (argv) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
    }
    if (envp) {
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
    }
    free(cwd);
    free(output_path);
}

JNIEXPORT jintArray JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                                   jobjectArray java_env, jstring java_cwd,
                                                   jstring java_output, jboolean use_pty,
                                                   jint pty_rows, jint pty_columns) {
    (void)self;
    if (!java_argv || !java_env || !java_cwd || !java_output) return NULL;
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    if (argc <= 0) return NULL;
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        free(argv);
        free(envp);
        return NULL;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
        argv[i] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, value, utf);
        if (value) (*env)->DeleteLocalRef(env, value);
        if (!argv[i]) {
            free_spawn_allocations(argv, argc, envp, envc, NULL, NULL);
            return NULL;
        }
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
        envp[i] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, value, utf);
        if (value) (*env)->DeleteLocalRef(env, value);
        if (!envp[i]) {
            free_spawn_allocations(argv, argc, envp, envc, NULL, NULL);
            return NULL;
        }
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf ? cwd_utf : "/");
    if (cwd_utf) (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);

    const char *output_utf = (*env)->GetStringUTFChars(env, java_output, NULL);
    char *output_path = strdup(output_utf ? output_utf : "/dev/null");
    if (output_utf) (*env)->ReleaseStringUTFChars(env, java_output, output_utf);

    if (!cwd || !output_path) {
        free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);
        return NULL;
    }

    int in_pipe[2] = {-1, -1};
    int master_fd = -1;
    char slave_name[128];
    slave_name[0] = '\0';
    if (use_pty) {
        master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (master_fd < 0 || grantpt(master_fd) != 0 || unlockpt(master_fd) != 0 ||
            ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
            if (master_fd >= 0) close(master_fd);
            free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);
            return NULL;
        }
    } else if (pipe2(in_pipe, O_CLOEXEC) != 0) {
        free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);
        return NULL;
    }

    pid_t parent_pid_before_fork = getpid();
    pid_t pid = fork();
    if (pid == 0) {
        // Ensure child receives SIGTERM if parent JVM process dies unexpectedly
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0);
        if (getppid() != parent_pid_before_fork) {
            _exit(126);
        }
        if (use_pty) {
            if (setsid() < 0) _exit(126);
            int slave_fd = open(slave_name, O_RDWR | O_CLOEXEC);
            if (slave_fd < 0 || ioctl(slave_fd, TIOCSCTTY, 0) != 0) _exit(126);
            struct winsize size = {
                .ws_row = (unsigned short)(pty_rows > 0 ? pty_rows : 40),
                .ws_col = (unsigned short)(pty_columns > 0 ? pty_columns : 120),
            };
            ioctl(slave_fd, TIOCSWINSZ, &size);
            struct termios terminal;
            if (tcgetattr(slave_fd, &terminal) == 0) {
                terminal.c_lflag &= (tcflag_t)~(ECHO | ECHONL);
                tcsetattr(slave_fd, TCSANOW, &terminal);
            }
            dup2(slave_fd, STDIN_FILENO);
            dup2(slave_fd, STDOUT_FILENO);
            dup2(slave_fd, STDERR_FILENO);
            if (slave_fd > STDERR_FILENO) close(slave_fd);
            close(master_fd);
        } else {
            // Setiap peluncuran Chroot mendapat process group sendiri (PGID == pid)
            // agar pembatalan menghentikan seluruh pohon proses di bawahnya.
            setpgid(0, 0);
            close(in_pipe[1]);
            int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
            if (output_fd < 0) _exit(126);
            dup2(in_pipe[0], STDIN_FILENO);
            dup2(output_fd, STDOUT_FILENO);
            dup2(output_fd, STDERR_FILENO);
            close(in_pipe[0]);
            close(output_fd);
        }
        close_extra_fds();
        if (chdir(cwd) != 0 && chdir("/") != 0) {
            _exit(126);
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        // execvpe, not execve: callers pass bare names such as "su", which execve never resolves via PATH
        // (it would always fail with ENOENT). The lookup uses this process's PATH; the child gets envp.
        execvpe(argv[0], argv, envp);
        dprintf(STDERR_FILENO, "NextCraft native exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    if (pid > 0 && !use_pty) setpgid(pid, pid);
    free_spawn_allocations(argv, argc, envp, envc, cwd, output_path);

    if (pid < 0) {
        if (use_pty) close(master_fd); else close_pair(in_pipe);
        return NULL;
    }
    if (!use_pty) close(in_pipe[0]);
    int input_fd = use_pty ? fcntl(master_fd, F_DUPFD_CLOEXEC, 0) : in_pipe[1];
    if (input_fd < 0) {
        if (use_pty) close(master_fd);
        kill_and_reap_child(pid);
        return NULL;
    }
    jint values[3] = {pid, input_fd, use_pty ? master_fd : -1};
    jintArray result = (*env)->NewIntArray(env, 3);
    if (!result) {
        close(input_fd);
        if (use_pty) close(master_fd);
        kill_and_reap_child(pid);
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env;
    (void)self;
    int status = 0;
    pid_t value;
    do {
        value = waitpid((pid_t)pid, &status, no_hang ? WNOHANG : 0);
    } while (value < 0 && errno == EINTR && !no_hang);

    if (value == 0) return -2;
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env;
    (void)self;
    if (pid <= 0) return -1;
    // Target seluruh process group (-pid) terlebih dahulu.
    int result = kill((pid_t)(-pid), signal);
    if (result != 0 && errno == ESRCH) {
        result = kill((pid_t)pid, signal);
    }
    // Root escalation on EPERM is handled by Kotlin's NativeSpawnProcess.killViaRootIfNeeded(),
    // which checks RuntimeEngines.isRootEngine() and enforces a 2-second timeout.
    return result == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_resizePty(JNIEnv *env, jobject self,
                                                       jint master_fd, jint rows, jint columns) {
    (void)env;
    (void)self;
    if (master_fd < 0) return -EBADF;
    struct winsize size = {
        .ws_row = (unsigned short)(rows > 0 ? rows : 40),
        .ws_col = (unsigned short)(columns > 0 ? columns : 120),
    };
    return ioctl(master_fd, TIOCSWINSZ, &size) == 0 ? 0 : -errno;
}
