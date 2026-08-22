package com.winfex.core

import android.net.Uri
import android.content.Context
import android.system.Os
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.winfex.model.RatPackage
import com.winfex.model.SelectedPackages
import com.winfex.native.NativeBridge
import com.winfex.ui.common.WinfexAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * MiceWine 风格的 .rat 包管理器。
 *
 * 一个 .rat 文件本质是 tar.xz，结构：
 *   pkg-header            ← key=value 形式的元数据
 *   makeSymlinks.sh       ← 安装时执行的符号链接创建脚本
 *   files/                ← 实际文件
 *
 * 安装流程：
 *   1. 解析 pkg-header 拿到 name/category/version/architecture/vkDriverLib
 *   2. 生成 uuid
 *   3. 解压 tar.xz 到 packages/<category>-<uuid8>/
 *   4. 执行 makeSymlinks.sh
 *   5. 对可执行文件 chmod 0700
 *   6. 写入 packages.json 索引
 *
 * 选中流程：
 *   - SelectedPackages 持久化在 selected_packages.json
 *   - Core 包被选中时，重建 usr/ 符号链接指向 packages/<core>/files/usr
 *   - VulkanDriver 包被选中时，重新生成 vulkan_icd.json
 *
 * .rat 包来源：
 *   - 用户从 SAF 导入：installFromUri()
 *   - assets/prebuilt_rat/ 下预置：在 Application 启动时自动安装（用于内置版本）
 */
object RatPackageManager {

    private const val TAG = "RatPackageManager"
    private const val PACKAGES_INDEX = "packages.json"
    private const val ASSET_PREBUILT_DIR = "prebuilt_rat"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, RatPackage::class.java)
    private val listAdapter = moshi.adapter<List<RatPackage>>(listType)
    private val selectedAdapter = moshi.adapter(SelectedPackages::class.java)

    private val _packages = MutableStateFlow<List<RatPackage>>(emptyList())
    val packages: StateFlow<List<RatPackage>> = _packages.asStateFlow()

    private val _selected = MutableStateFlow(SelectedPackages())
    val selected: StateFlow<SelectedPackages> = _selected.asStateFlow()

    private val _installing = MutableStateFlow<InstallProgress?>(null)
    val installing: StateFlow<InstallProgress?> = _installing.asStateFlow()

    data class InstallProgress(val name: String, val percent: Int, val message: String)

    /** 启动时加载索引 + 安装 assets 里的预置包 */
    fun warmup() {
        synchronized(this) {
            loadIndex()
            installPrebuiltFromAssetsIfNeeded()
            applySelectionLinks()
        }
    }

    // ===== 查询 =====

    fun allByCategory(category: String): List<RatPackage> =
        _packages.value.filter { it.category == category }

    fun byUuid(uuid: String): RatPackage? =
        _packages.value.firstOrNull { it.uuid == uuid }

    fun selectedFor(category: String): RatPackage? =
        _selected.value.uuidFor(category)?.let { byUuid(it) }

    fun isReady(): Boolean {
        val s = _selected.value
        // 至少要有 Core + Wine + DXVK + VulkanDriver 才算 ready
        // Box64 在 ARM64 上必需，x86_64 设备不需要
        val mandatory = mutableListOf(RatPackage.CAT_CORE, RatPackage.CAT_WINE,
            RatPackage.CAT_DXVK, RatPackage.CAT_VULKAN_DRIVER)
        if (WinfexPaths.isArm64) mandatory += RatPackage.CAT_BOX64
        return mandatory.all { s.uuidFor(it) != null }
    }

    fun missingCategories(): List<String> {
        val s = _selected.value
        val mandatory = mutableListOf(RatPackage.CAT_CORE, RatPackage.CAT_WINE,
            RatPackage.CAT_DXVK, RatPackage.CAT_VULKAN_DRIVER)
        if (WinfexPaths.isArm64) mandatory += RatPackage.CAT_BOX64
        return mandatory.filter { s.uuidFor(it) == null }
    }

    // ===== 选择 =====

    suspend fun select(pkg: RatPackage) = withContext(Dispatchers.IO) {
        val s = _selected.value.copy()
        s.setUuidFor(pkg.category, pkg.uuid)
        saveSelected(s)
        _selected.value = s
        applySelectionLinks()
    }

    suspend fun unselect(category: String) = withContext(Dispatchers.IO) {
        val s = _selected.value.copy()
        s.setUuidFor(category, null)
        saveSelected(s)
        _selected.value = s
        applySelectionLinks()
    }

    /**
     * 应用当前选中状态产生的副作用：
     *   - Core 包选中 → 重建 usr/ 符号链接
     *   - VulkanDriver 包选中 → 重新生成 vulkan_icd.json
     */
    private fun applySelectionLinks() {
        // usr/ → packages/<core>/files/usr
        val core = selectedFor(RatPackage.CAT_CORE)
        val usrLink = WinfexPaths.usrDir
        if (usrLink.exists()) {
            if (usrLink.isDirectory && !OsCompat.isSymlink(usrLink)) {
                usrLink.deleteRecursively()
            } else {
                usrLink.delete()
            }
        }
        if (core != null) {
            val target = File(WinfexPaths.packageFilesDir(core.uuid, core.category), "usr")
            if (target.exists()) {
                try { Os.symlink(target.absolutePath, usrLink.absolutePath) }
                catch (e: Exception) { Log.w(TAG, "symlink usr failed: ${e.message}") }
            }
        }

        // vulkan_icd.json
        val driver = selectedFor(RatPackage.CAT_VULKAN_DRIVER)
        val icdFile = WinfexPaths.vulkanIcdFile
        if (driver != null && !driver.vkDriverLib.isNullOrEmpty()) {
            val soPath = File(WinfexPaths.packageFilesDir(driver.uuid, driver.category),
                driver.vkDriverLib).absolutePath
            val json = """
                {
                  "ICD": {
                    "api_version": "1.3.296",
                    "library_path": "$soPath"
                  },
                  "file_format_version": "1.0.0"
                }
            """.trimIndent()
            icdFile.writeText(json)
        } else {
            if (icdFile.exists()) icdFile.delete()
        }
    }

    // ===== 安装 =====

    /**
     * 从 SAF Uri 安装一个 .rat 文件。
     */
    suspend fun installFromUri(context: Context, uri: Uri): RatPackage? =
        withContext(Dispatchers.IO) {
            val name = queryFileName(context, uri) ?: "package-${System.currentTimeMillis()}.rat"
            val tmp = File(WinfexPaths.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            } ?: return@withContext null
            val installed = installRatFile(tmp)
            tmp.delete()
            installed
        }

    /**
     * 安装一个本地的 .rat 文件。
     */
    suspend fun installRatFile(ratFile: File): RatPackage? = withContext(Dispatchers.IO) {
        if (!ratFile.exists()) return@withContext null
        _installing.value = InstallProgress(ratFile.name, 0, "解析 pkg-header")
        try {
            // 1. 解析 header
            val header = parseRatHeader(ratFile)
                ?: run {
                    _installing.value = null
                    return@withContext null
                }
            _installing.value = InstallProgress(header.name, 5, "生成 UUID")

            // 2. 生成 uuid
            val uuid = UUID.randomUUID().toString()
            val targetDir = WinfexPaths.packageDir(uuid, header.category)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            // 3. 解压 tar.xz 到 targetDir
            _installing.value = InstallProgress(header.name, 10, "解压 tar.xz")
            extractTarXz(ratFile, targetDir) { p ->
                _installing.value = InstallProgress(header.name, 10 + (p * 80 / 100).toInt(), "解压中")
            }

            // 4. 执行 makeSymlinks.sh
            val mklink = File(targetDir, "makeSymlinks.sh")
            if (mklink.exists()) {
                _installing.value = InstallProgress(header.name, 92, "执行 makeSymlinks.sh")
                NativeBridge.nativeChmod(mklink.absolutePath, 0b111_000_000) // 0700
                execScript(mklink, targetDir)
            }

            // 5. 找出所有可执行文件 chmod 0700
            _installing.value = InstallProgress(header.name, 95, "设置可执行权限")
            chmodExecutables(File(targetDir, "files"))

            // 6. 写入索引
            val pkg = RatPackage(
                uuid = uuid,
                name = header.name,
                category = header.category,
                version = header.version,
                architecture = header.architecture,
                vkDriverLib = header.vkDriverLib,
                installedAt = System.currentTimeMillis(),
                sourceFileName = ratFile.name
            )
            val newList = _packages.value.filterNot { it.uuid == uuid } + pkg
            _packages.value = newList.sortedByDescending { it.installedAt }
            saveIndex()

            // 7. 如果是第一个该类别的包，自动选中
            if (_selected.value.uuidFor(pkg.category) == null) {
                val s = _selected.value.copy()
                s.setUuidFor(pkg.category, pkg.uuid)
                saveSelected(s)
                _selected.value = s
                applySelectionLinks()
            }

            _installing.value = null
            Log.i(TAG, "installed ${pkg.category}/${pkg.name} uuid=${pkg.uuid}")
            pkg
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            _installing.value = null
            null
        }
    }

    /** 删除一个已安装的包 */
    suspend fun uninstall(uuid: String) = withContext(Dispatchers.IO) {
        val pkg = byUuid(uuid) ?: return@withContext
        val dir = WinfexPaths.packageDir(uuid, pkg.category)
        if (dir.exists()) dir.deleteRecursively()
        _packages.value = _packages.value.filterNot { it.uuid == uuid }

        // 如果它正在被选中，清空选择
        if (_selected.value.uuidFor(pkg.category) == uuid) {
            val s = _selected.value.copy()
            s.setUuidFor(pkg.category, null)
            saveSelected(s)
            _selected.value = s
            applySelectionLinks()
        }
        saveIndex()
    }

    // ===== 内部：解析 pkg-header =====

    data class ParsedHeader(
        val name: String,
        val category: String,
        val version: String,
        val architecture: String,
        val vkDriverLib: String?
    )

    /**
     * .rat 是 tar.xz。pkg-header 是其中的一个普通文件，位于 tar 流的前几条 entry。
     * 我们只读 header，不全解压。
     */
    private fun parseRatHeader(ratFile: File): ParsedHeader? {
        try {
            ratFile.inputStream().buffered().use { fis ->
                XZCompressorInputStream(fis).buffered().use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextTarEntry
                        var count = 0
                        while (entry != null && count < 5) {
                            if (entry.name == "pkg-header" || entry.name == "./pkg-header") {
                                val text = BufferedReader(InputStreamReader(tar)).readText()
                                return parseHeaderKeyValue(text)
                            }
                            entry = tar.nextTarEntry
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseRatHeader failed", e)
        }
        return null
    }

    private fun parseHeaderKeyValue(text: String): ParsedHeader? {
        var name = ""; var category = ""; var version = ""; var arch = ""; var vkDriver: String? = null
        for (line in text.lineSequence()) {
            val trim = line.trim()
            if (trim.isEmpty() || trim.startsWith("#")) continue
            val eq = trim.indexOf('=')
            if (eq < 0) continue
            val k = trim.substring(0, eq).trim()
            val v = trim.substring(eq + 1).trim().trim('"')
            when (k.lowercase()) {
                "name" -> name = v
                "category" -> category = v
                "version" -> version = v
                "architecture" -> arch = v
                "vkdriverlib" -> vkDriver = v
            }
        }
        if (name.isEmpty() || category.isEmpty()) return null
        return ParsedHeader(name, category, if (version.isEmpty()) "unknown" else version,
            if (arch.isEmpty()) "aarch64" else arch, vkDriver)
    }

    // ===== 内部：解压 tar.xz =====

    private fun extractTarXz(ratFile: File, targetDir: File, onProgress: (Int) -> Unit) {
        ratFile.inputStream().buffered().use { fis ->
            XZCompressorInputStream(fis).buffered().use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    val total = ratFile.length().coerceAtLeast(1)
                    var read = 0L
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        val outPath = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            outPath.mkdirs()
                        } else {
                            outPath.parentFile?.mkdirs()
                            FileOutputStream(outPath).use { out -> tar.copyTo(out) }
                        }
                        // 符号链接
                        if (entry.isSymbolicLink) {
                            val linkTarget = entry.linkName
                            try {
                                if (outPath.exists()) outPath.delete()
                                Os.symlink(linkTarget, outPath.absolutePath)
                            } catch (e: Exception) {
                                Log.w(TAG, "symlink ${entry.name} -> $linkTarget failed: ${e.message}")
                            }
                        }
                        read += entry.size
                        onProgress(((read * 100 / total).toInt()).coerceAtMost(99))
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    private fun execScript(script: File, cwd: File) {
        try {
            val pb = ProcessBuilder("/system/bin/sh", script.absolutePath)
                .directory(cwd)
                .redirectErrorStream(true)
            pb.environment()["WINFEX_PKG_DIR"] = cwd.absolutePath
            val p = pb.start()
            p.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "execScript failed: ${e.message}")
        }
    }

    private fun chmodExecutables(root: File) {
        if (!root.exists()) return
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            // .so / .dll / .reg / .conf 不需要可执行
            if (name.endsWith(".so") || name.endsWith(".dll") ||
                name.endsWith(".reg") || name.endsWith(".conf") ||
                name.endsWith(".json") || name.endsWith(".txt") ||
                name.endsWith(".xml") || name.endsWith(".pak") ||
                name.endsWith(".dat") || name.endsWith(".inf")) return@forEach
            if (name.contains('.')) return@forEach  // 有扩展名的都跳过
            try { NativeBridge.nativeChmod(f.absolutePath, 0b111_000_000) } catch (_: Exception) {}
        }
    }

    // ===== 内部：assets 预置包 =====

    private fun installPrebuiltFromAssetsIfNeeded() {
        val assetList = WinfexAssets.list(ASSET_PREBUILT_DIR) ?: return
        if (assetList.isEmpty()) return

        // 检查是否已安装（用 sourceFileName 比对）
        val installed = _packages.value.map { it.sourceFileName }.toSet()
        for (assetName in assetList) {
            if (!assetName.endsWith(".rat")) continue   // 跳过 README 等非包文件
            if (assetName in installed) continue
            try {
                _installing.value = InstallProgress(assetName, 0, "释放预置包")
                val tmp = File(WinfexPaths.cacheDir, assetName)
                WinfexAssets.open("$ASSET_PREBUILT_DIR/$assetName")?.use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                } ?: continue
                // 同步安装（在 IO 线程之外，但 warmup 已经是同步调用）
                val installed2 = kotlinx.coroutines.runBlocking { installRatFile(tmp) }
                tmp.delete()
                _installing.value = null
                Log.i(TAG, "prebuilt installed: ${installed2?.name}")
            } catch (e: Exception) {
                Log.e(TAG, "prebuilt install failed: $assetName", e)
                _installing.value = null
            }
        }
    }

    // ===== 持久化 =====

    private fun loadIndex() {
        val f = File(WinfexPaths.baseDir, PACKAGES_INDEX)
        if (f.exists()) {
            try {
                _packages.value = listAdapter.fromJson(f.readText()) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "loadIndex failed", e)
            }
        }
        val sf = WinfexPaths.selectedPackagesFile
        if (sf.exists()) {
            try {
                _selected.value = selectedAdapter.fromJson(sf.readText()) ?: SelectedPackages()
            } catch (e: Exception) {
                Log.e(TAG, "loadSelected failed", e)
            }
        }
    }

    private fun saveIndex() {
        val f = File(WinfexPaths.baseDir, PACKAGES_INDEX)
        f.writeText(listAdapter.toJson(_packages.value))
    }

    private fun saveSelected(s: SelectedPackages) {
        WinfexPaths.selectedPackagesFile.writeText(selectedAdapter.toJson(s))
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return uri.lastPathSegment
    }
}
