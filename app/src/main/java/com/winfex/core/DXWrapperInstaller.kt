package com.winfex.core

import android.util.Log
import com.winfex.model.WinePrefix
import java.io.File

/**
 * DX 包装器安装器（ImageFS 版本）。
 *
 * 从 imagefs/opt/wine/lib/wine/ 或 imagefs/usr/lib/ 找 DXVK DLL，
 * 复制到 prefix 的 system32/syswow64。
 */
object DXWrapperInstaller {

    private const val TAG = "DXWrapperInstaller"

    fun install(prefix: WinePrefix): Boolean {
        val imagefs = ImageFsInstaller.imagefsDir.absolutePath
        val prefixDir = WinfexPaths.prefixDir(prefix.id)
        val system32 = File(prefixDir, "drive_c/windows/system32")
        val syswow64 = File(prefixDir, "drive_c/windows/syswow64")
        if (!system32.exists() || !syswow64.exists()) {
            Log.w(TAG, "system32/syswow64 not initialized, skipping DX install")
            return false
        }

        // 从 imagefs 找 DXVK DLL
        val dxvkDirs = listOf(
            File("$imagefs/opt/dxvk"),
            File("$imagefs/usr/share/dxvk"),
            File("$imagefs/opt/wine/lib/wine/dxvk")
        )
        for (dir in dxvkDirs) {
            if (dir.exists()) {
                copyDlls(File(dir, "x64"), system32, "DXVK-x64")
                copyDlls(File(dir, "x32"), syswow64, "DXVK-x32")
                break
            }
        }

        // 从 imagefs 找 FEX DLL（libwow64fex.dll / libarm64ecfex.dll）
        val fexDirs = listOf(
            File("$imagefs/opt/fex/system32"),
            File("$imagefs/usr/share/fex/system32"),
            File("$imagefs/opt/wine/lib/wine")
        )
        for (dir in fexDirs) {
            if (dir.exists()) {
                listOf("libwow64fex.dll", "libarm64ecfex.dll").forEach { dll ->
                    val src = File(dir, dll)
                    if (src.exists()) {
                        src.copyTo(File(system32, dll), overwrite = true)
                        Log.i(TAG, "Installed $dll")
                    }
                }
                break
            }
        }

        return true
    }

    private fun copyDlls(srcDir: File, dstDir: File, label: String) {
        if (!srcDir.exists() || !srcDir.isDirectory) return
        srcDir.listFiles { f -> f.isFile && f.name.endsWith(".dll", ignoreCase = true) }?.forEach { f ->
            try {
                f.copyTo(File(dstDir, f.name), overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "copy ${f.name} failed: ${e.message}")
            }
        }
        Log.i(TAG, "$label: copied ${srcDir.list()?.size ?: 0} files")
    }
}
