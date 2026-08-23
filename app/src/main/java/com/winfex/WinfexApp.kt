package com.winfex

import android.app.Application
import android.util.Log
import com.winfex.core.ImageFsInstaller
import com.winfex.core.WinfexCrashHandler
import com.winfex.core.WinfexPaths
import com.winfex.input.XTestInjector
import com.winfex.native.NativeLoader

/**
 * Winfex Application 入口。
 *
 * 启动时做：
 *  1. 加载 libwinfex.so
 *  2. 初始化私有目录结构
 *  3. 挂全局崩溃捕获
 *  4. 安装 ImageFS（从 assets/components/ 下的 tar.xz 解压，无 root）
 *  5. 初始化 XTestInjector
 *
 * 不需要 root：
 *  - FEX 作为 Wine 的 WoW64 DLL（libwow64fex.dll）进程内加载
 *  - 路径重定向靠环境变量 + LD_PRELOAD libredirect.so
 *  - 所有文件在 app 私有目录 /data/data/com.winfex/files/imagefs/
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

        // 初始化 XTestInjector
        try {
            XTestInjector.init()
        } catch (e: Exception) {
            Log.w(TAG, "XTestInjector.init failed: ${e.message}")
        }

        // 安装 ImageFS（首次启动时从 assets 解压 tar.xz，无 root）
        Thread({
            val ok = kotlinx.coroutines.runBlocking {
                ImageFsInstaller.installIfNeeded(this)
            }
            if (ok) {
                Log.i(TAG, "ImageFS installed successfully")
            } else {
                Log.e(TAG, "ImageFS installation failed")
            }
        }, "imagefs-installer").start()
    }

    companion object {
        private const val TAG = "WinfexApp"
        @JvmStatic
        lateinit var instance: WinfexApp
            private set
    }
}
