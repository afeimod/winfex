package com.winfex

import android.app.Application
import android.util.Log
import com.winfex.core.InputController
import com.winfex.core.RatPackageManager
import com.winfex.core.WinfexCrashHandler
import com.winfex.core.WinfexPaths
import com.winfex.core.XServerManager
import com.winfex.input.XTestInjector
import com.winfex.native.NativeLoader

/**
 * Winfex Application 入口。
 *
 * 启动时做：
 *  1. 加载 libwinfex.so（JNI 桥）
 *  2. 初始化私有目录结构
 *  3. 挂全局崩溃捕获
 *  4. 扫描已安装的 .rat 包
 *  5. 初始化 XTestInjector（延迟到 X server 启动后再 connect）
 */
class WinfexApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        WinfexPaths.init(this)
        WinfexCrashHandler.install(this)

        try {
            NativeLoader.load()
            Log.i(TAG, "libwinfex.so loaded")
        } catch (t: Throwable) {
            Log.e(TAG, "load libwinfex.so failed", t)
        }

        // 初始化 XTestInjector（加载 libX11.so + libXtst.so，但还不 connect）
        // 真正 connect 在 XServerManager.start 成功后触发
        try {
            XTestInjector.init()
        } catch (e: Exception) {
            Log.w(TAG, "XTestInjector.init failed (will retry on first use): ${e.message}")
        }

        // 启动时不做重活，扫描放到 splash 时
        RatPackageManager.warmup()
    }

    companion object {
        private const val TAG = "WinfexApp"
        @JvmStatic
        lateinit var instance: WinfexApp
            private set
    }
}
