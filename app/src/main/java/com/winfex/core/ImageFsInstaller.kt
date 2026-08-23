package com.winfex.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * ImageFS 安装器 —— 从 assets/components/*.tar.xz 解压到 filesDir/imagefs/
 *
 * 替代旧的 RatPackageManager。不再需要用户手动导入 .rat 包，
 * 所有组件预编译后内置在 assets 里，首次启动自动解压。
 *
 * 组件列表：
 *   core-aarch64.tar.xz    → imagefs/usr/{lib,bin,etc}
 *   wine-arm64ec.tar.xz    → imagefs/opt/wine/
 *   box64-aarch64.tar.xz   → imagefs/usr/bin/box64
 *   fex-aarch64.tar.xz     → imagefs/usr/bin/FEXLoader + etc/binfmt/
 *   turnip-aarch64.tar.xz  → imagefs/usr/lib/libvulkan_freedreno.so + etc/vulkan/
 *   rootfs-aarch64.tar.xz  → imagefs/{etc,home,usr/bin/start-container.sh,...}
 *
 * 安装流程：
 *   1. 检查版本号（.imagefs_version）
 *   2. 如果版本不匹配，清空 imagefs（保留 home/ 目录）
 *   3. 按顺序解压每个组件的 tar.xz
 *   4. chmod 可执行文件
 *   5. 写入版本号
 */
object ImageFsInstaller {

    private const val TAG = "ImageFsInstaller"
    private const val VERSION = 1
    private const val VERSION_FILE = ".imagefs_version"

    /** assets 目录下组件 tar.xz 的目录 */
    private const val COMPONENTS_DIR = "components"

    /** 组件列表（按安装顺序） */
    private val COMPONENT_ORDER = listOf(
        "rootfs-aarch64",   // 先装 rootfs（基础目录结构）
        "core-aarch64",     // 运行时库
        "turnip-aarch64",   // Turnip 驱动
        "box64-aarch64",    // Box64
        "fex-aarch64",      // FEX-Emu
        "wine-arm64ec"      // Wine（最后装，依赖前面的库）
    )

    private val _progress = MutableStateFlow(InstallProgress())
    val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    data class InstallProgress(
        val installing: Boolean = false,
        val currentComponent: String = "",
        val currentIndex: Int = 0,
        val totalCount: Int = 0,
        val message: String = "",
        val complete: Boolean = false,
        val error: String? = null
    )

    /** imagefs 根目录 */
    val imagefsDir: File by lazy { File(WinfexPaths.baseDir, "imagefs") }

    /** 是否已安装且版本匹配 */
    fun isInstalled(): Boolean {
        val versionFile = File(imagefsDir, VERSION_FILE)
        return versionFile.exists() && versionFile.readText().trim().toIntOrNull() == VERSION
    }

    /**
     * 从 assets 安装所有组件。
     * 如果已安装且版本匹配，跳过。
     */
    suspend fun installIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            Log.i(TAG, "ImageFS already installed (v$VERSION)")
            _progress.value = InstallProgress(complete = true, message = "已安装")
            return@withContext true
        }

        _progress.value = InstallProgress(installing = true, totalCount = COMPONENT_ORDER.size, message = "开始安装")
        Log.i(TAG, "Starting ImageFS installation v$VERSION")

        try {
            // 清空 imagefs（保留 home/ 目录）
            clearImagefs()

            // 确保 imagefs 目录存在
            imagefsDir.mkdirs()

            // 按顺序安装每个组件
            for ((index, component) in COMPONENT_ORDER.withIndex()) {
                val assetName = "$component.tar.xz"
                _progress.value = InstallProgress(
                    installing = true,
                    currentComponent = component,
                    currentIndex = index,
                    totalCount = COMPONENT_ORDER.size,
                    message = "解压 $assetName"
                )

                val ok = extractComponent(context, assetName)
                if (!ok) {
                    // 组件不存在在 assets 里是正常的（用户可能只放了部分）
                    Log.w(TAG, "Component $assetName not found in assets, skipping")
                }
            }

            // chmod 可执行文件
            chmodExecutables()

            // 写版本号
            File(imagefsDir, VERSION_FILE).writeText(VERSION.toString())

            _progress.value = InstallProgress(
                complete = true,
                message = "安装完成（v$VERSION）"
            )
            Log.i(TAG, "ImageFS installation complete v$VERSION")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            _progress.value = InstallProgress(
                complete = true,
                error = e.message ?: e.toString()
            )
            false
        }
    }

    /**
     * 清空 imagefs，但保留 home/ 目录（用户数据）。
     */
    private fun clearImagefs() {
        if (!imagefsDir.exists()) return
        Log.i(TAG, "Clearing imagefs (preserving home/)")

        imagefsDir.listFiles()?.forEach { f ->
            if (f.name != "home") {
                f.deleteRecursively()
            }
        }
    }

    /**
     * 从 assets 解压一个组件的 tar.xz 到 imagefs。
     */
    private suspend fun extractComponent(context: Context, assetName: String): Boolean =
        withContext(Dispatchers.IO) {
            val assetPath = "$COMPONENTS_DIR/$assetName"
            val input = try {
                context.assets.open(assetPath)
            } catch (_: Exception) {
                return@withContext false
            }

            input.use { fis ->
                XZCompressorInputStream(fis).buffered().use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextTarEntry
                        while (entry != null) {
                            val outFile = File(imagefsDir, entry.name)

                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                            }

                            // 符号链接
                            if (entry.isSymbolicLink) {
                                try {
                                    if (outFile.exists()) outFile.delete()
                                    android.system.Os.symlink(entry.linkName, outFile.absolutePath)
                                } catch (_: Exception) {}
                            }

                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
            Log.i(TAG, "Extracted $assetName")
            true
        }

    /**
     * 给所有无扩展名的文件设置可执行权限。
     */
    private fun chmodExecutables() {
        val binDir = File(imagefsDir, "usr/bin")
        if (binDir.exists()) {
            binDir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.contains('.')) {
                    try { com.winfex.native.NativeBridge.nativeChmod(f.absolutePath, 0b111_101_101) }
                    catch (_: Exception) {}
                }
            }
        }

        // opt/wine/bin 下的可执行文件
        val wineBin = File(imagefsDir, "opt/wine/bin")
        if (wineBin.exists()) {
            wineBin.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.contains('.')) {
                    try { com.winfex.native.NativeBridge.nativeChmod(f.absolutePath, 0b111_101_101) }
                    catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * 获取组件安装状态。
     */
    fun getComponentStatus(): List<ComponentStatus> {
        return COMPONENT_ORDER.map { name ->
            val dir = when {
                name.startsWith("rootfs") -> imagefsDir
                name.startsWith("core") -> File(imagefsDir, "usr/lib")
                name.startsWith("wine") -> File(imagefsDir, "opt/wine")
                name.startsWith("box64") -> File(imagefsDir, "usr/bin/box64")
                name.startsWith("fex") -> File(imagefsDir, "usr/bin/FEXLoader")
                name.startsWith("turnip") -> File(imagefsDir, "usr/lib/libvulkan_freedreno.so")
                else -> null
            }
            ComponentStatus(
                name = name,
                installed = dir?.exists() == true,
                path = dir?.absolutePath ?: ""
            )
        }
    }

    data class ComponentStatus(
        val name: String,
        val installed: Boolean,
        val path: String
    ) {
        val displayName: String get() = when {
            name.startsWith("rootfs") -> "RootFS"
            name.startsWith("core") -> "Core 运行时库"
            name.startsWith("wine") -> "Wine (ARM64EC)"
            name.startsWith("box64") -> "Box64"
            name.startsWith("fex") -> "FEX-Emu"
            name.startsWith("turnip") -> "Turnip 驱动"
            else -> name
        }
    }
}
