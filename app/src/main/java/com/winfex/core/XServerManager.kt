package com.winfex.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.winfex.native.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * X Server 生命周期管理器。
 *
 * 设计要点：
 *
 * 1. **DISPLAY 号选择**：默认 `:13`，与系统上其他 X server（XSDL 的 `:0`、termux-x11 的
 *    `:0` 等）物理隔离。socket 走 `$TMPDIR/.X11-unix/X13`，TMPDIR 在
 *    `/data/data/com.winfex/cache/tmp/`，无需 root。
 *
 * 2. **进程模型**：参考 Lorie 上游的两进程模型——X server 跑在独立进程，避免被 Activity
 *    生命周期杀掉。但与 Lorie 原生不同的是，我们用 `WineRunnerService` 的子进程方式
 *    拉起 X server，而不是 `app_process`，因为：
 *      - app_process 需要 CLASSPATH 指向 XServerActivity 的 dex，依赖项复杂
 *      - 子进程方式可以直接复用 NativeBridge.nativeExecBinary，环境变量传递更精确
 *      - X server 不需要 GUI Activity 也能跑（渲染靠 EGL+ANativeWindow，
 *        但这是 Lorie Activity 的事；纯命令行 X server 不需要）
 *
 *    实际部署时建议两种模式都支持：
 *      - **Headless 模式**（默认）：X server 跑在 Service 子进程，无 UI，等 Wine 连接
 *      - **Lorie Activity 模式**（可选）：拉起 XServerActivity，渲染到 Surface
 *
 * 3. **二进制路径**：X server 的可执行文件由 `xserver/` Gradle module 提供，最终在
 *    `libXserver.so` 中。本工程不直接 exec .so 文件，而是通过 Lorie Activity 的 JNI
 *    入口启动 server 主循环。如果要走 headless，需要单独编译一个 `Xserver` ELF 二进制
 *    放到 `assets/xserver/` 或 .rat 包里。
 *
 * 4. **socket 清理**：启动前删除可能残留的 `$TMPDIR/.X11-unix/X<N>`，避免 bind 冲突。
 *
 * 5. **就绪检测**：fork 后 X server 需要 ~1-3 秒初始化（xkbcomp、字体加载、EGL 上下文）。
 *    我们用 socket connect 探测 + 超时重试来判断 X server 是否 ready。
 */
object XServerManager {

    private const val TAG = "XServerManager"

    /** 默认 DISPLAY 号。:13 与 XSDL/termux-x11 的 :0 错开。 */
    const val DEFAULT_DISPLAY_NUMBER = 13

    /** X server 启动后等待 ready 的超时时间（毫秒）。 */
    private const val READY_TIMEOUT_MS = 10_000

    /** 探测间隔。 */
    private const val PROBE_INTERVAL_MS = 200L

    /** X server 进程的 pgid。-1 表示未启动。 */
    @Volatile
    private var xserverPgid: Int = -1

    /** X server lib 路径（如果走 headless 模式）。 */
    @Volatile
    private var xserverBinaryPath: String? = null

    /** 当前 DISPLAY 号（可在运行时切换，但需要重启 X server）。 */
    @Volatile
    private var displayNumber: Int = DEFAULT_DISPLAY_NUMBER

    /** 状态机。 */
    enum class State {
        STOPPED, STARTING, READY, FAILED
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前 DISPLAY 字符串，形如 ":13"。 */
    fun displayString(): String = ":$displayNumber"

    /** socket 目录：$TMPDIR/.X11-unix */
    private fun socketDir(): File {
        return File("${WinfexPaths.cacheDir.absolutePath}/tmp/.X11-unix").apply { mkdirs() }
    }

    /** socket 文件路径：$TMPDIR/.X11-unix/X13 */
    fun socketFile(): File = File(socketDir(), "X$displayNumber")

    /**
     * 设置 DISPLAY 号（启动前调）。修改后需要重启 X server 才生效。
     */
    fun setDisplayNumber(n: Int) {
        if (n < 0 || n > 99) {
            Log.w(TAG, "invalid display number $n, ignoring")
            return
        }
        displayNumber = n
    }

    /**
     * 设置 X server 二进制路径（headless 模式）。
     * 如果不设置，则尝试通过 XServerActivity 启动（Lorie 模式）。
     */
    fun setBinaryPath(path: String) {
        xserverBinaryPath = path
    }

    /**
     * 启动 X server。
     *
     * @param context 任何 Context
     * @param mode 启动模式
     * @return true 表示 ready，false 表示超时或失败
     */
    fun start(context: Context, mode: StartMode = StartMode.AUTO): Boolean {
        if (_state.value == State.READY) {
            Log.i(TAG, "X server already running on $displayString()")
            return true
        }
        if (_state.value == State.STARTING) {
            Log.w(TAG, "X server already starting, waiting...")
            return waitForReady()
        }

        _state.value = State.STARTING

        // 清理可能残留的 socket
        cleanupSocket()

        // 选择启动模式
        val effectiveMode = when (mode) {
            StartMode.AUTO -> if (xserverBinaryPath != null) StartMode.HEADLESS else StartMode.LORIE_ACTIVITY
            else -> mode
        }

        val ok = when (effectiveMode) {
            StartMode.HEADLESS -> startHeadless()
            StartMode.LORIE_ACTIVITY -> startLorieActivity(context)
        }

        if (!ok) {
            _state.value = State.FAILED
            return false
        }

        // 等待 socket ready
        val ready = waitForReady()
        _state.value = if (ready) State.READY else State.FAILED
        Log.i(TAG, "X server start result: $ready (mode=$effectiveMode, display=$displayString())")

        // X server ready 后，连接 XTestInjector
        if (ready) {
            try {
                com.winfex.input.XTestInjector.connect(displayString())
            } catch (e: Exception) {
                Log.w(TAG, "XTestInjector.connect failed: ${e.message}")
            }
        }
        return ready
    }

    /**
     * 停止 X server。
     */
    fun stop() {
        // 先断开 XTestInjector
        try { com.winfex.input.XTestInjector.disconnect() } catch (_: Exception) {}
        if (xserverPgid > 0) {
            Log.i(TAG, "stopping X server pgid=$xserverPgid")
            NativeBridge.nativeKillProcessGroup(xserverPgid)
            xserverPgid = -1
        }
        cleanupSocket()
        _state.value = State.STOPPED
    }

    /**
     * 等待 X server socket ready。
     */
    private fun waitForReady(): Boolean {
        val socket = socketFile()
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists() && probeSocketConnect()) {
                return true
            }
            try { Thread.sleep(PROBE_INTERVAL_MS) } catch (_: InterruptedException) {}
        }
        return false
    }

    /**
     * 通过 connect() 探测 socket 是否真的可连（光看文件存在还不够，可能 server 还没 listen）。
     */
    private fun probeSocketConnect(): Boolean {
        return try {
            val addr = java.net.UnixDomainSocketAddress.of(socketFile().absolutePath)
            val channel = java.nio.channels.SocketChannel.open(addr)
            channel.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Headless 模式：直接 execve Xserver 二进制。
     *
     * 需要先调用 setBinaryPath() 指向一个可执行的 Xserver ELF。
     * 当前是个 stub，因为 com.winfex 不内置 Xserver ELF，需要用户自己提供
     * （可以编译一个放到 assets/xserver/，或者打成一个独立的 .rat 包）。
     */
    private fun startHeadless(): Boolean {
        val bin = xserverBinaryPath ?: run {
            Log.e(TAG, "headless mode requires setBinaryPath() first")
            return false
        }
        if (!File(bin).exists()) {
            Log.e(TAG, "X server binary not found: $bin")
            return false
        }

        val argv = listOf(
            bin,
            displayString(),
            "-nolisten", "tcp",        // 不监听 TCP
            "-noreset",                // 最后一个 client 退出后不重启
            "-wr",                     // 黑色背景
            "-seat", "seat0",
            "-dpi", "240",
            "-logfile", WinfexPaths.logFile("xserver").absolutePath,
            "-config", "lorie.conf"    // Lorie DDX 配置
        )
        val env = mapOf(
            "DISPLAY" to displayString(),
            "TMPDIR" to "${WinfexPaths.cacheDir.absolutePath}/tmp",
            "XDG_RUNTIME_DIR" to WinfexPaths.cacheDir.absolutePath,
            "HOME" to WinfexPaths.homeDir.absolutePath,
            "LD_LIBRARY_PATH" to "/system/lib64:${WinfexPaths.usrDir.absolutePath}/lib",
            "XKB_CONFIG_ROOT" to "${WinfexPaths.usrDir.absolutePath}/share/X11/xkb",
            "XLOCALEDIR" to "${WinfexPaths.usrDir.absolutePath}/share/X11/locale"
        )

        val logFile = WinfexPaths.logFile("xserver")
        val spec = ProcessExecutor.ExecSpec(
            binary = bin,
            argv = argv.drop(1),
            envp = env,
            workdir = WinfexPaths.cacheDir.absolutePath,
            label = "xserver:$displayNumber"
        )
        return try {
            xserverPgid = ProcessExecutor.start(spec, logFile) { line ->
                Log.d(TAG, "[xserver] $line")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "headless start failed", e)
            false
        }
    }

    /**
     * Lorie Activity 模式：拉起 com.winfex.xserver.XServerActivity。
     *
     * X server 跑在 Activity 内的 native 线程，渲染到 Surface。
     * 这个模式需要 `xserver/` Gradle module 已集成且编译成功。
     *
     * 当 Activity 不存在（用户没集成 xserver module）时，返回 false 并打印日志。
     */
    private fun startLorieActivity(context: Context): Boolean {
        return try {
            val className = "com.winfex.xserver.XServerActivity"
            val cls = Class.forName(className)
            @Suppress("DEPRECATION")
            val intent = Intent(context, cls).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_DISPLAY_NUMBER, displayNumber)
            }
            context.startActivity(intent)
            // Activity 模式下没法直接拿 pid；通过 socket ready 判断
            xserverPgid = -1  // 由 Activity 进程内部管理
            Log.i(TAG, "started XServerActivity for $displayString()")
            true
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "XServerActivity not found — xserver module not integrated?", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "startLorieActivity failed", e)
            false
        }
    }

    /**
     * 清理 socket 文件。
     */
    private fun cleanupSocket() {
        val f = socketFile()
        if (f.exists()) {
            Log.d(TAG, "cleaning up stale socket ${f.absolutePath}")
            f.delete()
        }
    }

    /**
     * 检查 X server 是否真的在运行（通过 socket + 进程双重判断）。
     */
    fun isRunning(): Boolean {
        if (_state.value != State.READY) return false
        if (xserverPgid > 0) {
            // headless 模式：检查进程组是否还活着
            try {
                android.system.Os.kill(xserverPgid, 0)
                return true
            } catch (_: Exception) {
                return false
            }
        }
        // Activity 模式：靠 socket 探测
        return probeSocketConnect()
    }

    /**
     * 启动模式。
     */
    enum class StartMode {
        /** 优先 headless，binary 不存在时回退 Activity */
        AUTO,
        /** 直接 execve Xserver 二进制 */
        HEADLESS,
        /** 拉起 XServerActivity，渲染到 Surface */
        LORIE_ACTIVITY
    }

    const val EXTRA_DISPLAY_NUMBER = "winfex.xserver.display_number"
}
