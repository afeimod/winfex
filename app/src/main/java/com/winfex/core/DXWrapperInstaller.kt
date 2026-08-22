package com.winfex.core

import android.util.Log
import com.winfex.model.RatPackage
import com.winfex.model.WinePrefix
import java.io.File

/**
 * DX 包装器（DXVK / VKD3D / WineD3D）的 DLL 安装器。
 *
 * 完全对齐 MiceWine MainActivity.installDXWrapper 的逻辑：
 *   - 根据 prefix.d3dxRenderer 选择 DXVK 或 WineD3D
 *   - 把对应包的 files/x64/ 下的 DLL 复制到 drive_c/windows/system32/
 *   - 把 files/x32/ 下的 DLL 复制到 drive_c/windows/syswow64/
 *   - VKD3D 总是安装（与 DXVK/WineD3D 共存）
 *
 * 应在每次启动游戏前调用一次（覆盖式拷贝），保证 prefix 中的 DLL 是最新选中的版本。
 */
object DXWrapperInstaller {

    private const val TAG = "DXWrapperInstaller"

    /**
     * 安装 DX 包装器到 prefix。
     * @return true 表示成功，false 表示有缺失（已选中包不存在）
     */
    fun install(prefix: WinePrefix): Boolean {
        val sel = RatPackageManager.selected.value
        val prefixDir = WinfexPaths.prefixDir(prefix.id)
        val system32 = File(prefixDir, "drive_c/windows/system32")
        val syswow64 = File(prefixDir, "drive_c/windows/syswow64")
        if (!system32.exists() || !syswow64.exists()) {
            Log.w(TAG, "system32/syswow64 not initialized, skipping DX install")
            return false
        }

        var ok = true

        // 1. 主 D3D 渲染器：DXVK 或 WineD3D（互斥）
        when (prefix.d3dxRenderer) {
            "DXVK" -> {
                val pkg = sel.dxvkUuid?.let { RatPackageManager.byUuid(it) }
                if (pkg == null) {
                    Log.w(TAG, "DXVK 包未选中")
                    ok = false
                } else {
                    val src = WinfexPaths.packageFilesDir(pkg.uuid, pkg.category)
                    copyDlls(File(src, "x64"), system32, "DXVK-x64")
                    copyDlls(File(src, "x32"), syswow64, "DXVK-x32")
                }
            }
            "WineD3D" -> {
                val pkg = sel.wineD3dUuid?.let { RatPackageManager.byUuid(it) }
                if (pkg == null) {
                    Log.w(TAG, "WineD3D 包未选中")
                    ok = false
                } else {
                    val src = WinfexPaths.packageFilesDir(pkg.uuid, pkg.category)
                    copyDlls(File(src, "x64"), system32, "WineD3D-x64")
                    copyDlls(File(src, "x32"), syswow64, "WineD3D-x32")
                }
            }
        }

        // 2. VKD3D 总是安装
        val vkd3dPkg = sel.vkd3dUuid?.let { RatPackageManager.byUuid(it) }
        if (vkd3dPkg != null) {
            val src = WinfexPaths.packageFilesDir(vkd3dPkg.uuid, vkd3dPkg.category)
            copyDlls(File(src, "x64"), system32, "VKD3D-x64")
            copyDlls(File(src, "x32"), syswow64, "VKD3D-x32")
        }

        // 3. WineUtils 的 DirectX runtime（d3dcompiler_*.dll, d3dx*.dll）—— 一次性安装
        val utilsPkg = sel.wineUtilsUuid?.let { RatPackageManager.byUuid(it) }
        if (utilsPkg != null) {
            val utilsDir = WinfexPaths.packageFilesDir(utilsPkg.uuid, utilsPkg.category)
            val dxSrc = File(utilsDir, "wine-utils/DirectX")
            if (dxSrc.exists()) {
                copyDlls(File(dxSrc, "x64"), system32, "DirectX-x64")
                copyDlls(File(dxSrc, "x32"), syswow64, "DirectX-x32")
            }
            // OpenAL
            val openalSrc = File(utilsDir, "wine-utils/OpenAL")
            if (openalSrc.exists()) {
                copyDlls(File(openalSrc, "x64"), system32, "OpenAL-x64")
                copyDlls(File(openalSrc, "x32"), syswow64, "OpenAL-x32")
            }
            // CoreFonts
            val fontsSrc = File(utilsDir, "wine-utils/CoreFonts")
            val fontsDst = File(prefixDir, "drive_c/windows/Fonts")
            if (fontsSrc.exists() && fontsDst.exists()) {
                fontsSrc.listFiles()?.forEach { f ->
                    if (f.isFile) f.copyTo(File(fontsDst, f.name), overwrite = true)
                }
            }
        }

        return ok
    }

    private fun copyDlls(srcDir: File, dstDir: File, label: String) {
        if (!srcDir.exists() || !srcDir.isDirectory) {
            Log.w(TAG, "$label source not found: ${srcDir.absolutePath}")
            return
        }
        srcDir.listFiles { f -> f.isFile }?.forEach { f ->
            // 只复制 .dll 文件
            if (!f.name.endsWith(".dll", ignoreCase = true)) return@forEach
            try {
                f.copyTo(File(dstDir, f.name), overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "copy ${f.name} failed: ${e.message}")
            }
        }
        Log.i(TAG, "$label installed ${srcDir.list()?.size ?: 0} files → ${dstDir.absolutePath}")
    }
}
