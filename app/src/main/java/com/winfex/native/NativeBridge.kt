package com.winfex.native

/**
 * JNI 桥：对应 cpp/winfex_jni.c
 *
 * 与 MiceWine 的 shell_loader.c 不同，我们没有走 fork+execl("/system/bin/sh")
 * 而是直接 fork + execve 二进制。理由：
 *   - shell 会引入一层解析开销，且 sh 在 Android 上功能有限
 *   - 我们需要 setsid / CPU 亲和性 / OOM adj / NO_NEW_PRIVS，这些 shell 路径下要
 *     通过额外命令实现，复杂；execve 路径下在 fork 后直接调 syscall
 *
 * 但 symlink 创建 / 文件复制等"shell 操作"我们走 Kotlin 层的 Os.symlink / File.copyTo。
 */
object NativeBridge {

    /**
     * 启动一个外部二进制。
     *
     * @param path        二进制绝对路径，必须已 chmod 0700
     * @param argv        参数数组，argv[0] 通常是程序名
     * @param envp        环境变量数组，"KEY=VALUE" 格式；传 null 用当前 environ
     * @param workdir     工作目录；可为 null
     * @param stdinFd / stdoutFd / stderrFd
     *                    传 -1 表示不重定向（继承父进程）；通常 stdout/stderr 传 pipe 写端
     * @param outPid      长度 1 的 int[]，子进程 pid 写入 outPid[0]
     * @return 0 表示 fork 成功（不代表 exec 成功），< 0 表示失败
     */
    @JvmStatic
    external fun nativeExecBinary(
        path: String,
        argv: Array<String>,
        envp: Array<String>?,
        workdir: String?,
        stdinFd: Int,
        stdoutFd: Int,
        stderrFd: Int,
        outPid: IntArray?
    ): Int

    /** 在当前进程上设置 CPU 亲和性 + OOM adj。仅在子进程刚 fork 出来时由 native 内部调用。 */
    @JvmStatic
    external fun nativeSetupProcess(cpuMask: Long, oomAdj: Int)

    /** 杀掉一个进程组。pgid 由 setsid 产生，等于 nativeExecBinary 返回的 pid。 */
    @JvmStatic
    external fun nativeKillProcessGroup(pgid: Int): Int

    /** 创建符号链接。返回 0=成功，<0=失败。 */
    @JvmStatic
    external fun nativeSymlink(target: String, linkPath: String): Int

    /** 给文件设置权限模式（如 0700）。 */
    @JvmStatic
    external fun nativeChmod(path: String, mode: Int): Int
}
