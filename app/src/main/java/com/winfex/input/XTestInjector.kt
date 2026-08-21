package com.winfex.input

import android.util.Log
import com.winfex.core.WinfexPaths
import com.winfex.core.XServerManager

/**
 * XTEST 扩展注入器 —— 把按键/鼠标事件注入到 X server。
 *
 * 调用链：
 *   InputOverlayView 触摸 → InputController → XTestInjector
 *     → nativeInjectKey → XTestFakeKeyEvent → X server → Wine winex11.drv
 *
 * 依赖：
 *   - libX11.so + libXtst.so 在 ${usr}/lib/ 下（由 Core .rat 包提供）
 *   - X server 已就绪（XServerManager.state == READY）
 *
 * 用法：
 *   XTestInjector.init()           // 加载库
 *   XTestInjector.connect()        // 连接 :13
 *   XTestInjector.injectKey(38, true)   // 按下 A
 *   XTestInjector.injectKey(38, false)  // 松开 A
 *   XTestInjector.injectMouseButton(1, true)   // 鼠标左键按下
 */
object XTestInjector {

    private const val TAG = "XTestInjector"

    @Volatile private var initialized = false
    @Volatile private var connected = false

    /** 加载 libX11.so + libXtst.so */
    fun init(): Boolean {
        if (initialized) return true
        val libDir = "${WinfexPaths.usrDir.absolutePath}/lib"
        initialized = nativeInit(libDir)
        if (!initialized) {
            Log.e(TAG, "init failed — 检查 $libDir/libX11.so 和 libXtst.so 是否存在")
            Log.e(TAG, "这些库应该由 Core .rat 包提供（MiceWine 的 Core 包含 libX11）")
        }
        return initialized
    }

    /** 连接到 X server（默认 :13） */
    fun connect(display: String = XServerManager.displayString()): Boolean {
        if (!init()) return false
        if (connected) return true
        connected = nativeConnect(display)
        if (!connected) {
            Log.e(TAG, "connect $display failed — X server 是否启动？")
        } else {
            Log.i(TAG, "connected to $display")
        }
        return connected
    }

    fun disconnect() {
        if (connected) {
            nativeDisconnect()
            connected = false
        }
    }

    fun isReady(): Boolean = connected && nativeIsReady()

    /** 注入键盘按键 */
    fun injectKey(xKeycode: Int, isDown: Boolean): Boolean {
        if (!ensureConnected()) return false
        return nativeInjectKey(xKeycode, isDown)
    }

    /** 注入鼠标按键：1=左 2=中 3=右 4=滚上 5=滚下 */
    fun injectMouseButton(button: Int, isDown: Boolean): Boolean {
        if (!ensureConnected()) return false
        return nativeInjectMouseButton(button, isDown)
    }

    /** 注入鼠标绝对位置移动 */
    fun injectMouseAbs(x: Int, y: Int): Boolean {
        if (!ensureConnected()) return false
        return nativeInjectMouseAbs(x, y)
    }

    /** 注入鼠标相对移动（FPS 游戏用） */
    fun injectMouseMoveRelative(dx: Int, dy: Int): Boolean {
        if (!ensureConnected()) return false
        return nativeInjectMouseMoveRelative(dx, dy)
    }

    private fun ensureConnected(): Boolean {
        if (connected && nativeIsReady()) return true
        return connect()
    }

    // ===== JNI =====
    @JvmStatic external fun nativeInit(libDir: String): Boolean
    @JvmStatic external fun nativeConnect(display: String): Boolean
    @JvmStatic external fun nativeDisconnect()
    @JvmStatic external fun nativeInjectKey(xKeycode: Int, isDown: Boolean): Boolean
    @JvmStatic external fun nativeInjectMouseButton(button: Int, isDown: Boolean): Boolean
    @JvmStatic external fun nativeInjectMouseAbs(x: Int, y: Int): Boolean
    @JvmStatic external fun nativeInjectMouseMoveRelative(dx: Int, dy: Int): Boolean
    @JvmStatic external fun nativeIsReady(): Boolean

    init {
        // 由 NativeLoader 在 Application.onCreate 触发 System.loadLibrary("winfex")
        try {
            System.loadLibrary("winfex")
        } catch (_: Throwable) { /* NativeLoader 已经加载过 */ }
    }
}
