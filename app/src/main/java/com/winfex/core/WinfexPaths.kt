package com.winfex.core

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 集中管理所有路径常量。结构按 MiceWine 风格：
 *
 *   /data/data/com.winfex/files/
 *     ├── packages/                      ← 所有 .rat 包解压后
 *     │   ├── Core-<uuid>/files/usr/{lib,bin,etc,...}
 *     │   ├── Wine-<uuid>/files/wine/{bin,lib/wine/{x86_64-unix,x86_64-windows,i386-unix,i386-windows},share/wine}
 *     │   ├── Box64-<uuid>/files/usr/bin/box64
 *     │   ├── DXVK-<uuid>/files/{x64,x32}/*.dll
 *     │   ├── VKD3D-<uuid>/files/{x64,x32}/*.dll
 *     │   ├── WineD3D-<uuid>/files/{x64,x32}/*.dll
 *     │   ├── VulkanDriver-<uuid>/files/usr/lib/libvulkan_freedreno.so
 *     │   └── WineUtils-<uuid>/files/wine-utils/{CoreFonts,DirectX,OpenAL,Addons}
 *     │
 *     ├── usr/                           ← 符号链接 → packages/<selectedCore>/files/usr
 *     ├── wine_prefixes/                 ← Wine 前缀
 *     │   └── <id>/
 *     │       ├── drive_c/windows/{system32,syswow64,Fonts}
 *     │       ├── dosdevices/
 *     │       ├── system.reg / user.reg
 *     │       └── winfex.cfg
 *     ├── home/                          ← Wine 进程的 HOME
 *     ├── selected_packages.json         ← 当前选中的包 uuid
 *     ├── vulkan_icd.json                ← 动态生成
 *     ├── pa_default.pa                  ← PulseAudio 配置
 *     ├── mangohud.conf                  ← MangoHud 配置
 *     ├── games.json                     ← 游戏库索引
 *     ├── shortcuts.json                 ← 快捷方式索引
 *     ├── input/<id>.json                ← 输入方案
 *     ├── logs/
 *     └── crash/
 *
 *   /data/data/com.winfex/cache/
 *     └── tmp/                           ← Wine 临时目录
 */
object WinfexPaths {

    lateinit var appContext: Context
        private set

    val baseDir: File          by lazy { File(appContext.filesDir, "") }
    val packagesDir: File      by lazy { File(baseDir, "packages") }
    val usrDir: File           by lazy { File(baseDir, "usr") }          // symlink
    val prefixesDir: File      by lazy { File(baseDir, "wine_prefixes") }
    val homeDir: File          by lazy { File(baseDir, "home") }
    val logsDir: File          by lazy { File(baseDir, "logs") }
    val crashDir: File         by lazy { File(baseDir, "crash") }
    val cacheDir: File         by lazy { File(appContext.cacheDir, "tmp") }

    val selectedPackagesFile: File by lazy { File(baseDir, "selected_packages.json") }
    val vulkanIcdFile: File        by lazy { File(baseDir, "vulkan_icd.json") }
    val pulseConfigFile: File      by lazy { File(baseDir, "pa_default.pa") }
    val mangohudConfigFile: File   by lazy { File(baseDir, "mangohud.conf") }
    val gamesIndexFile: File       by lazy { File(baseDir, "games.json") }
    val shortcutsIndexFile: File   by lazy { File(baseDir, "shortcuts.json") }
    val inputDir: File             by lazy { File(baseDir, "input") }

    val deviceAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    val isArm64: Boolean
        get() = deviceAbi.startsWith("arm64")

    fun init(context: Context) {
        appContext = context.applicationContext
        listOf(packagesDir, prefixesDir, homeDir, logsDir, crashDir, cacheDir, inputDir)
            .forEach { it.mkdirs() }
    }

    fun packageDir(uuid: String, category: String): File =
        File(packagesDir, "$category-${uuid.take(8)}")

    /** 拿到某个包内 files/ 下的子路径 */
    fun packageFilesDir(uuid: String, category: String): File =
        File(packageDir(uuid, category), "files")

    fun prefixDir(id: String): File = File(prefixesDir, id)

    fun logFile(prefix: String): File =
        File(logsDir, "$prefix-${System.currentTimeMillis()}.log")

    fun crashFile(): File = File(crashDir, "crash-${System.currentTimeMillis()}.txt")
}
