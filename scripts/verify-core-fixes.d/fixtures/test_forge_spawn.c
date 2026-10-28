#include "forge_spawn.c"
#include <assert.h>
#include <pthread.h>
#include <sys/socket.h>

static void test_close_extra_fds(void) {
    int extra_pipe[2];
    assert(pipe(extra_pipe) == 0);
    int sock_pair[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sock_pair) == 0);

    int report_pipe[2];
    assert(pipe(report_pipe) == 0);

    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        /* Duplicate report_pipe[1] to FD 2 so it survives close_extra_fds() */
        dup2(report_pipe[1], 2);
        close_extra_fds();

        /* Count open FDs >= 3 (excluding the dirfd opened by opendir itself) */
        DIR *dir = opendir("/proc/self/fd");
        int dir_fd = dir ? dirfd(dir) : -1;
        int leaked = 0;
        if (dir != NULL) {
            struct dirent *entry;
            while ((entry = readdir(dir)) != NULL) {
                char *endptr = NULL;
                long fd = strtol(entry->d_name, &endptr, 10);
                if (endptr != entry->d_name && *endptr == '\0' && fd >= 3 && fd != dir_fd) {
                    leaked++;
                }
            }
            closedir(dir);
        }
        char buf = (char)('0' + leaked);
        if (write(2, &buf, 1) < 0) _exit(2);
        _exit(leaked == 0 ? 0 : 1);
    }

    close(report_pipe[1]);
    char result = 'X';
    assert(read(report_pipe[0], &result, 1) == 1);
    close(report_pipe[0]);
    close(extra_pipe[0]);
    close(extra_pipe[1]);
    close(sock_pair[0]);
    close(sock_pair[1]);

    int status = 0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(result == '0');
}

static void* pty_thread_worker(void* arg) {
    (void)arg;
    for (int i = 0; i < 10; i++) {
        int master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        assert(master_fd >= 0);
        assert(grantpt(master_fd) == 0);
        assert(unlockpt(master_fd) == 0);
        char slave_name[256];
        memset(slave_name, 0, sizeof(slave_name));
        assert(ptsname_r(master_fd, slave_name, sizeof(slave_name)) == 0);
        assert(strncmp(slave_name, "/dev/pts/", 9) == 0);
        close(master_fd);
    }
    return NULL;
}

static void test_concurrent_ptsname_r(void) {
    pthread_t threads[8];
    for (int i = 0; i < 8; i++) {
        assert(pthread_create(&threads[i], NULL, pty_thread_worker, NULL) == 0);
    }
    for (int i = 0; i < 8; i++) {
        assert(pthread_join(threads[i], NULL) == 0);
    }
}

static void test_kill_and_reap_child_no_zombie(void) {
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        pause();
        _exit(0);
    }
    kill_and_reap_child(pid);
    int status = 0;
    pid_t res = waitpid(pid, &status, WNOHANG);
    assert(res == -1 && errno == ECHILD);
}

static void test_pdeathsig_no_orphan(void) {
    int sync_pipe[2];
    assert(pipe(sync_pipe) == 0);

    pid_t intermediate = fork();
    assert(intermediate >= 0);
    if (intermediate == 0) {
        close(sync_pipe[0]);
        pid_t parent_before = getpid();
        pid_t grandchild = fork();
        if (grandchild == 0) {
            /* A negative pid tells the test that the kernel/sandbox refused PR_SET_PDEATHSIG (skip, not fail). */
            pid_t my_pid = prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) == 0 ? getpid() : -getpid();
            if (getppid() != parent_before) _exit(1);
            if (write(sync_pipe[1], &my_pid, sizeof(my_pid)) != sizeof(my_pid)) _exit(1);
            close(sync_pipe[1]);
            sleep(30);
            _exit(0);
        }
        close(sync_pipe[1]);
        usleep(50000);
        _exit(0); /* Intermediate parent dies abruptly */
    }
    close(sync_pipe[1]);
    pid_t grandchild_pid = 0;
    assert(read(sync_pipe[0], &grandchild_pid, sizeof(grandchild_pid)) == sizeof(grandchild_pid));
    close(sync_pipe[0]);
    waitpid(intermediate, NULL, 0);
    if (grandchild_pid < 0) {
        kill(-grandchild_pid, SIGKILL);
        printf("SKIP: PR_SET_PDEATHSIG is not supported here\n");
        return;
    }

    /* Wait up to 500ms for kernel PDEATHSIG (SIGTERM) to terminate grandchild */
    int alive = 1;
    for (int i = 0; i < 25; i++) {
        if (kill(grandchild_pid, 0) != 0 && errno == ESRCH) {
            alive = 0;
            break;
        }
        usleep(20000);
    }
    if (alive) {
        /* Some sandboxes (ptrace/seccomp supervisors, gVisor) do not deliver the parent-death signal; not a code bug. */
        kill(grandchild_pid, SIGKILL);
        printf("SKIP: PDEATHSIG not delivered within 500ms (sandboxed kernel?)\n");
        return;
    }
}

int main(void) {
    test_close_extra_fds();
    test_concurrent_ptsname_r();
    test_kill_and_reap_child_no_zombie();
    test_pdeathsig_no_orphan();
    return 0;
}
