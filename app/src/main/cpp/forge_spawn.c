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

static void close_fd_pair(int descriptors[2]) {
    if (descriptors[0] >= 0) {
        close(descriptors[0]);
    }
    if (descriptors[1] >= 0) {
        close(descriptors[1]);
    }
}

static void scrub_inherited_descriptors(void) {
    DIR *fd_dir = opendir("/proc/self/fd");
    if (fd_dir != NULL) {
        int self_dir_fd = dirfd(fd_dir);
        struct dirent *item;
        while ((item = readdir(fd_dir)) != NULL) {
            int candidate_fd = atoi(item->d_name);
            if (candidate_fd >= 3 && candidate_fd != self_dir_fd) {
                close(candidate_fd);
            }
        }
        closedir(fd_dir);
        return;
    }
    long upper_bound = sysconf(_SC_OPEN_MAX);
    if (upper_bound < 0 || upper_bound > 65536) {
        upper_bound = 1024;
    }
    for (int fd_idx = 3; fd_idx < (int)upper_bound; fd_idx++) {
        close(fd_idx);
    }
}

static void terminate_and_collect_child(pid_t child_pid) {
    if (child_pid <= 0) {
        return;
    }
    kill(child_pid, SIGKILL);
    int wait_state = 0;
    while (waitpid(child_pid, &wait_state, 0) < 0) {
        if (errno != EINTR) {
            break;
        }
    }
}

static void release_native_buffers(
    char **arg_vec,
    jsize arg_len,
    char **env_vec,
    jsize env_len,
    char *work_dir,
    char *log_file
) {
    if (arg_vec != NULL) {
        for (jsize idx = 0; idx < arg_len; idx++) {
            free(arg_vec[idx]);
        }
        free(arg_vec);
    }
    if (env_vec != NULL) {
        for (jsize idx = 0; idx < env_len; idx++) {
            free(env_vec[idx]);
        }
        free(env_vec);
    }
    free(work_dir);
    free(log_file);
}

static char **copy_java_string_array(JNIEnv *env, jobjectArray j_array, jsize count) {
    char **native_vec = calloc((size_t)count + 1, sizeof(char *));
    if (native_vec == NULL) {
        return NULL;
    }
    for (jsize idx = 0; idx < count; idx++) {
        jstring elem = (jstring)(*env)->GetObjectArrayElement(env, j_array, idx);
        const char *raw_utf = elem ? (*env)->GetStringUTFChars(env, elem, NULL) : NULL;
        native_vec[idx] = strdup(raw_utf ? raw_utf : "");
        if (raw_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, elem, raw_utf);
        }
        if (elem != NULL) {
            (*env)->DeleteLocalRef(env, elem);
        }
        if (native_vec[idx] == NULL) {
            for (jsize k = 0; k < idx; k++) {
                free(native_vec[k]);
            }
            free(native_vec);
            return NULL;
        }
    }
    return native_vec;
}

static char *dup_jstring_or_default(JNIEnv *env, jstring j_str, const char *fallback) {
    const char *raw = (*env)->GetStringUTFChars(env, j_str, NULL);
    char *copied = strdup(raw ? raw : fallback);
    if (raw != NULL) {
        (*env)->ReleaseStringUTFChars(env, j_str, raw);
    }
    return copied;
}

JNIEXPORT jintArray JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_spawn(
    JNIEnv *env,
    jobject thiz,
    jobjectArray java_argv,
    jobjectArray java_env,
    jstring java_cwd,
    jstring java_output,
    jboolean use_pty,
    jint pty_rows,
    jint pty_columns
) {
    (void)thiz;
    if (java_argv == NULL || java_env == NULL || java_cwd == NULL || java_output == NULL) {
        return NULL;
    }
    jsize arg_count = (*env)->GetArrayLength(env, java_argv);
    jsize env_count = (*env)->GetArrayLength(env, java_env);
    if (arg_count <= 0) {
        return NULL;
    }

    char **arg_vec = copy_java_string_array(env, java_argv, arg_count);
    char **env_vec = copy_java_string_array(env, java_env, env_count);
    char *work_dir = dup_jstring_or_default(env, java_cwd, "/");
    char *log_file = dup_jstring_or_default(env, java_output, "/dev/null");

    if (arg_vec == NULL || env_vec == NULL || work_dir == NULL || log_file == NULL) {
        release_native_buffers(arg_vec, arg_count, env_vec, env_count, work_dir, log_file);
        return NULL;
    }

    int stdin_pipe[2] = {-1, -1};
    int pty_master = -1;
    char pty_slave_path[128];
    pty_slave_path[0] = '\0';

    if (use_pty) {
        pty_master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        int pty_ok = (pty_master >= 0)
            && (grantpt(pty_master) == 0)
            && (unlockpt(pty_master) == 0)
            && (ptsname_r(pty_master, pty_slave_path, sizeof(pty_slave_path)) == 0);
        if (!pty_ok) {
            if (pty_master >= 0) {
                close(pty_master);
            }
            release_native_buffers(arg_vec, arg_count, env_vec, env_count, work_dir, log_file);
            return NULL;
        }
    } else if (pipe2(stdin_pipe, O_CLOEXEC) != 0) {
        release_native_buffers(arg_vec, arg_count, env_vec, env_count, work_dir, log_file);
        return NULL;
    }

    pid_t host_jvm_pid = getpid();
    pid_t child_pid = fork();

    if (child_pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0);
        if (getppid() != host_jvm_pid) {
            _exit(126);
        }
        if (use_pty) {
            if (setsid() < 0) {
                _exit(126);
            }
            int pty_slave = open(pty_slave_path, O_RDWR | O_CLOEXEC);
            if (pty_slave < 0 || ioctl(pty_slave, TIOCSCTTY, 0) != 0) {
                _exit(126);
            }
            struct winsize win_dims = {
                .ws_row = (unsigned short)(pty_rows > 0 ? pty_rows : 40),
                .ws_col = (unsigned short)(pty_columns > 0 ? pty_columns : 120),
            };
            ioctl(pty_slave, TIOCSWINSZ, &win_dims);
            struct termios term_attrs;
            if (tcgetattr(pty_slave, &term_attrs) == 0) {
                term_attrs.c_lflag &= (tcflag_t)~(ECHO | ECHONL);
                tcsetattr(pty_slave, TCSANOW, &term_attrs);
            }
            dup2(pty_slave, STDIN_FILENO);
            dup2(pty_slave, STDOUT_FILENO);
            dup2(pty_slave, STDERR_FILENO);
            if (pty_slave > STDERR_FILENO) {
                close(pty_slave);
            }
            close(pty_master);
        } else {
            setpgid(0, 0);
            close(stdin_pipe[1]);
            int out_fd = open(log_file, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
            if (out_fd < 0) {
                _exit(126);
            }
            dup2(stdin_pipe[0], STDIN_FILENO);
            dup2(out_fd, STDOUT_FILENO);
            dup2(out_fd, STDERR_FILENO);
            close(stdin_pipe[0]);
            close(out_fd);
        }
        scrub_inherited_descriptors();
        if (chdir(work_dir) != 0 && chdir("/") != 0) {
            _exit(126);
        }
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execvpe(arg_vec[0], arg_vec, env_vec);
        dprintf(STDERR_FILENO, "NextCraft native exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    if (child_pid > 0 && !use_pty) {
        setpgid(child_pid, child_pid);
    }
    release_native_buffers(arg_vec, arg_count, env_vec, env_count, work_dir, log_file);

    if (child_pid < 0) {
        if (use_pty) {
            close(pty_master);
        } else {
            close_fd_pair(stdin_pipe);
        }
        return NULL;
    }
    if (!use_pty) {
        close(stdin_pipe[0]);
    }
    int writer_fd = use_pty ? fcntl(pty_master, F_DUPFD_CLOEXEC, 0) : stdin_pipe[1];
    if (writer_fd < 0) {
        if (use_pty) {
            close(pty_master);
        }
        terminate_and_collect_child(child_pid);
        return NULL;
    }

    jint spawn_tuple[3] = {child_pid, writer_fd, use_pty ? pty_master : -1};
    jintArray tuple_array = (*env)->NewIntArray(env, 3);
    if (tuple_array == NULL) {
        close(writer_fd);
        if (use_pty) {
            close(pty_master);
        }
        terminate_and_collect_child(child_pid);
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, tuple_array, 0, 3, spawn_tuple);
    return tuple_array;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_waitFor(
    JNIEnv *env,
    jobject thiz,
    jint target_pid,
    jboolean non_blocking
) {
    (void)env;
    (void)thiz;
    int raw_status = 0;
    pid_t waited_pid;
    int wait_flags = non_blocking ? WNOHANG : 0;
    do {
        waited_pid = waitpid((pid_t)target_pid, &raw_status, wait_flags);
    } while (waited_pid < 0 && errno == EINTR && !non_blocking);

    if (waited_pid == 0) {
        return -2;
    }
    if (waited_pid < 0) {
        return -128 - errno;
    }
    if (WIFEXITED(raw_status)) {
        return WEXITSTATUS(raw_status);
    }
    if (WIFSIGNALED(raw_status)) {
        return 128 + WTERMSIG(raw_status);
    }
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_kill(
    JNIEnv *env,
    jobject thiz,
    jint target_pid,
    jint sig_num
) {
    (void)env;
    (void)thiz;
    if (target_pid <= 0) {
        return -1;
    }
    int rc = kill((pid_t)(-target_pid), sig_num);
    if (rc != 0 && errno == ESRCH) {
        rc = kill((pid_t)target_pid, sig_num);
    }
    return rc == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_nextcraft_forge_runtime_NativeSpawn_resizePty(
    JNIEnv *env,
    jobject thiz,
    jint pty_master,
    jint rows,
    jint columns
) {
    (void)env;
    (void)thiz;
    if (pty_master < 0) {
        return -EBADF;
    }
    struct winsize win_dims = {
        .ws_row = (unsigned short)(rows > 0 ? rows : 40),
        .ws_col = (unsigned short)(columns > 0 ? columns : 120),
    };
    return ioctl(pty_master, TIOCSWINSZ, &win_dims) == 0 ? 0 : -errno;
}
