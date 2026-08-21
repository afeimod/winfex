// process_executor.c — fork + execve 桥
//
// 设计目标：
//   - 父进程立即返回，把子进程 pid 回吐给 Kotlin
//   - 子进程继承父进程的 stdin/stdout/stderr 文件描述符（Kotlin 用 pipe+epoll 读取）
//   - 子进程在 exec 之前：调用 cpu_affinity.c 的钩子，设置 setsid() 形成独立进程组
//   - 用 execve 而不是 system/popen，避免引入 sh
//
// 为什么不用 posix_spawn？因为我们需要在 fork 后、exec 前做自定义 CPU 亲和性 + setsid，
// posix_spawn 的 file_actions 不能表达这些动作。fork 在 bionic 上足够快。

#define _GNU_SOURCE
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <signal.h>
#include <android/log.h>
#include <sys/prctl.h>

#include "process_executor.h"

#define TAG "winfex-exec"

extern void winfex_apply_cpu_affinity(unsigned long mask);
extern void winfex_set_oom_score_adj(int adj);

static void child_close_unused_fds(int stdin_fd, int stdout_fd, int stderr_fd) {
    // 关闭所有继承进来的 fd，除了我们关心的三个
    // bionic 没有提供 closefrom，用 sysconf(_SC_OPEN_MAX) 兜底
    long max = sysconf(_SC_OPEN_MAX);
    if (max < 0) max = 1024;
    for (int fd = 3; fd < (int) max; fd++) {
        if (fd == stdin_fd || fd == stdout_fd || fd == stderr_fd) continue;
        close(fd);
    }
}

int winfex_exec_binary(const char *path,
                       const char *const *argv,
                       const char *const *envp,
                       const char *working_dir,
                       int stdin_fd,
                       int stdout_fd,
                       int stderr_fd,
                       int *out_pid) {
    if (path == NULL || argv == NULL) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "fork failed: %s", strerror(errno));
        return -2;
    }

    if (pid == 0) {
        // ===== child =====
        // 1. 形成独立进程组，便于整组 kill
        setsid();

        // 2. 不要让子进程在父进程死后继续占用资源被 init 收养太久
        prctl(PR_SET_PDEATHSIG, SIGKILL);

        // 3. 不再获取新权限
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);

        // 4. 应用 CPU 亲和性
        winfex_apply_cpu_affinity(0);  // 0 = 不修改；Kotlin 侧通过 nativeSetupProcess 单独设置
        winfex_set_oom_score_adj(-100); // 让系统优先保活

        // 5. 重定向 stdio（仅当 fd 有效时）
        if (stdin_fd  >= 0) dup2(stdin_fd,  STDIN_FILENO);
        if (stdout_fd >= 0) dup2(stdout_fd, STDOUT_FILENO);
        if (stderr_fd >= 0) dup2(stderr_fd, STDERR_FILENO);

        child_close_unused_fds(stdin_fd, stdout_fd, stderr_fd);

        // 6. 工作目录
        if (working_dir) {
            if (chdir(working_dir) != 0) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "chdir %s failed: %s",
                                    working_dir, strerror(errno));
            }
        }

        // 7. 清除可能有害的环境变量
        unsetenv("LD_PRELOAD");
        unsetenv("LD_LIBRARY_PATH");
        unsetenv("TMPDIR");
        unsetenv("HOME");

        // 8. execve
        char *const *argv_mut = (char *const *) argv;
        char *const *envp_mut = (envp == NULL)
                ? (char *const *) environ
                : (char *const *) envp;

        execve(path, argv_mut, envp_mut);

        // 如果 execve 返回，说明失败
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "execve %s failed: %s", path, strerror(errno));
        _exit(127);
    }

    // ===== parent =====
    *out_pid = (int) pid;
    return 0;
}
