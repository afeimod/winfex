package com.winfex.ui.common

import android.content.res.AssetManager
import com.winfex.core.WinfexPaths

/**
 * 简单包装 assets 操作。
 */
object WinfexAssets {
    private val am: AssetManager get() = WinfexPaths.appContext.assets

    fun list(path: String): Array<String>? = try {
        am.list(path)
    } catch (_: Exception) { null }

    fun open(path: String) = try {
        am.open(path)
    } catch (_: Exception) { null }
}
