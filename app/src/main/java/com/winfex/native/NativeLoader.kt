package com.winfex.native

/**
 * 仅负责加载 libwinfex.so。.rat 包的释放由 RatPackageManager 负责。
 */
object NativeLoader {
    @JvmStatic
    fun load() {
        System.loadLibrary("winfex")
    }
}
