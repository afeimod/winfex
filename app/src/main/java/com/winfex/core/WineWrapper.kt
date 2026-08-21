package com.winfex.core

import android.util.Log
import com.winfex.model.RatPackage
import com.winfex.model.WinePrefix
import com.winfex.native.NativeBridge
import java.io.File

/**
 * Wine 启动包装器。完全对齐 MiceWine 的 WineWrapper.java + EnvVars.java 设计。
 *
 * 启动链：
 *   1. 准备环境变量（PATH / LD_LIBRARY_PATH / WINEPREFIX / VK_ICD / GALLIUM_DRIVER / BOX64_* / DXVK_* / PULSE_*）
 *   2. 显式调用 box64 wine（ARM64 设备）或直接 wine（x86_64 设备）
 *   3. 通过 explorer /desktop=shell 实现虚拟桌面
 *
 * 完整 env vars 参考 MiceWine：
 *   PATH=<usrDir>/bin:<winePkg>/files/wine/bin:<winePkg>/files/wine/lib/wine/x86_64-unix:<box64Pkg>/files/usr/bin
 *   LD_LIBRARY_PATH=/system/lib64:<usrDir>/lib
 *   WINEPREFIX=<prefixesDir>/<id>
 *   WINEARCH=win64
 *   WINEDEBUG=-all
 *   WINEDLLOVERRIDES=mscoree,mshtml,msvcp60=d
 *   WINEESYNC=1
 *   WINEFSYNC=1
 *   VK_ICD_FILENAMES=<baseDir>/vulkan_icd.json
 *   GALLIUM_DRIVER=zink
 *   ZINK_DEBUG=compact
 *   ZINK_DESCRIPTORS=lazy
 *   TU_DEBUG=<preset>
 *   DXVK_ASYNC=1
 *   DXVK_STATE_CACHE_PATH=<home>/.cache/dxvk-shader-cache
 *   DXVK_HUD=<csv>
 *   VKD3D_FEATURE_LEVEL=12_0
 *   BOX64_LOG=<level>
 *   BOX64_CPUNAME="ARM64 CPU"
 *   BOX64_DYNAREC_BIGBLOCK=<0/1>
 *   BOX64_DYNAREC_STRONGMEM=<0/1/2>
 *   BOX64_DYNAREC_SAFEFLAGS=<0/1/2>
 *   BOX64_MMAP32=<0/1>
 *   PULSE_LATENCY_MSEC=60
 *   MANGOHUD=1 (可选)
 *   HOME=<homeDir>
 *   TMPDIR=<cacheDir>/tmp
 *   DISPLAY=:13                          ← 与系统其他 X server (XSDL 的 :0 等) 隔离
 *   XDG_RUNTIME_DIR=<cacheDir>
 */
object WineWrapper {

    private const val TAG = "WineWrapper"

    data class LaunchParams(
        val prefix: WinePrefix,
        val exePath: String,
        val arguments: String = "",
        val workdir: String? = null,
        val desktopShell: Boolean = true,
        val extraEnv: Map<String, String> = emptyMap()
    )

    /**
     * 启动一个 Windows 程序。
     * @return pgid
     */
    fun launch(params: LaunchParams, onLine: (String) -> Unit): Int {
        val env = buildEnv(params.prefix)
        val argv = buildArgv(params)
        val workdir = params.workdir ?: File(params.exePath).parent
            ?: WinfexPaths.prefixDir(params.prefix.id).absolutePath

        val logFile = WinfexPaths.logFile("wine-${params.prefix.id}")
        val spec = ProcessExecutor.ExecSpec(
            binary = argv.first(),
            argv = argv.drop(1),
            envp = env,
            workdir = workdir,
            label = "wine:${params.prefix.id}",
            cpuMask = params.prefix.cpuAffinityMask
        )

        return ProcessExecutor.start(spec, logFile, onLine)
    }

    /** 仅初始化 prefix（wineboot --init） */
    fun initPrefix(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "wineboot",
                arguments = "--init",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath,
                desktopShell = false
            ), onLine
        )
    }

    /** 关闭 prefix（wineboot --end） */
    fun shutdownPrefix(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "wineboot",
                arguments = "--end",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath,
                desktopShell = false
            ), onLine
        )
    }

    /** 运行 winecfg */
    fun runWinecfg(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "winecfg",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath,
                desktopShell = false
            ), onLine
        )
    }

    /** 运行 regedit */
    fun runRegedit(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "regedit",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath,
                desktopShell = false
            ), onLine
        )
    }

    // ===== env =====

    fun buildEnv(prefix: WinePrefix): Map<String, String> {
        val sel = RatPackageManager.selected.value
        val winePkg = sel.wineUuid?.let { RatPackageManager.byUuid(it) }
            ?: throw IllegalStateException("未选中 Wine 包")
        val box64Pkg = sel.box64Uuid?.let { RatPackageManager.byUuid(it) }
        val corePkg = sel.coreUuid?.let { RatPackageManager.byUuid(it) }
        val driverPkg = sel.vulkanDriverUuid?.let { RatPackageManager.byUuid(it) }
        val utilsPkg = sel.wineUtilsUuid?.let { RatPackageManager.byUuid(it) }

        val usrDir = WinfexPaths.usrDir.absolutePath
        val wineFilesDir = WinfexPaths.packageFilesDir(winePkg.uuid, winePkg.category)
        val wineBinDir = File(wineFilesDir, "wine/bin").absolutePath
        val wineLibUnixDir = File(wineFilesDir, "wine/lib/wine/x86_64-unix").absolutePath

        val box64BinDir = if (box64Pkg != null) {
            File(WinfexPaths.packageFilesDir(box64Pkg.uuid, box64Pkg.category), "usr/bin").absolutePath
        } else ""

        val env = LinkedHashMap<String, String>()

        // PATH
        env["PATH"] = buildString {
            append(usrDir).append("/bin:")
            append(wineBinDir).append(":")
            append(wineLibUnixDir).append(":")
            if (box64BinDir.isNotEmpty()) append(box64BinDir).append(":")
            append("/system/bin:/system/xbin")
        }

        // LD_LIBRARY_PATH
        env["LD_LIBRARY_PATH"] = "/system/lib64:${usrDir}/lib"

        // Wine
        env["WINEPREFIX"] = WinfexPaths.prefixDir(prefix.id).absolutePath
        env["WINEARCH"] = "win64"
        env["WINEDEBUG"] = "-all"
        env["WINEDLLOVERRIDES"] = "mscoree,mshtml=d;winemenubuilder.exe=d"
        env["WINEESYNC"] = if (prefix.esync) "1" else "0"
        env["WINEFSYNC"] = if (prefix.fsync) "1" else "0"

        // Display
        // 默认 :13，与系统上可能存在的 XSDL/其他 X server 的 :0 隔离
        // socket 在 $TMPDIR/.X11-unix/X13
        env["DISPLAY"] = XServerManager.displayString()
        env["XDG_RUNTIME_DIR"] = WinfexPaths.cacheDir.absolutePath

        // Home / Temp
        env["HOME"] = WinfexPaths.homeDir.absolutePath
        env["TMPDIR"] = "${WinfexPaths.cacheDir.absolutePath}/tmp"

        // Vulkan
        if (driverPkg != null) {
            env["VK_ICD_FILENAMES"] = WinfexPaths.vulkanIcdFile.absolutePath
            env["VK_DRIVER_FILES"] = WinfexPaths.vulkanIcdFile.absolutePath
        }
        env["GALLIUM_DRIVER"] = "zink"
        env["ZINK_DEBUG"] = "compact"
        env["ZINK_DESCRIPTORS"] = "lazy"
        if (prefix.tuDebugPreset.isNotEmpty()) {
            env["TU_DEBUG"] = prefix.tuDebugPreset
        }

        // AdrenoTools 自定义驱动
        if (!prefix.adrenotoolsDriverPath.isNullOrEmpty()) {
            val driverFile = File(prefix.adrenotoolsDriverPath)
            env["USE_ADRENOTOOLS"] = "1"
            env["ADRENOTOOLS_CUSTOM_DRIVER_DIR"] = "${driverFile.parent}/"
            env["ADRENOTOOLS_CUSTOM_DRIVER_NAME"] = driverFile.name
            env["LD_PRELOAD"] = "/system/lib64/libEGL.so"
        }

        // DXVK
        env["DXVK_ASYNC"] = "1"
        env["DXVK_STATE_CACHE_PATH"] = "${WinfexPaths.homeDir.absolutePath}/.cache/dxvk-shader-cache"
        env["DXVK_HUD"] = prefix.dxvkHud.joinToString(",")

        // VKD3D
        env["VKD3D_FEATURE_LEVEL"] = prefix.vkd3dFeatureLevel

        // Box64（仅 ARM64）
        if (WinfexPaths.isArm64) {
            env["BOX64_LOG"] = prefix.box64Log
            env["BOX64_CPUNAME"] = "ARM64 CPU"
            env["BOX64_DYNAREC_BIGBLOCK"] = prefix.box64DynarecBigBlock.toString()
            env["BOX64_DYNAREC_STRONGMEM"] = prefix.box64DynarecStrongMem.toString()
            env["BOX64_DYNAREC_SAFEFLAGS"] = prefix.box64DynarecSafeFlags.toString()
            env["BOX64_MMAP32"] = prefix.box64Mmap32.toString()
        }

        // PulseAudio
        env["PULSE_LATENCY_MSEC"] = prefix.pulseLatencyMs.toString()

        // MangoHud
        if (prefix.mangoHud) {
            env["MANGOHUD"] = "1"
            env["MANGOHUD_CONFIGFILE"] = WinfexPaths.mangohudConfigFile.absolutePath
        }

        return env
    }

    // ===== argv =====

    private fun buildArgv(params: LaunchParams): List<String> {
        val sel = RatPackageManager.selected.value
        val winePkg = sel.wineUuid?.let { RatPackageManager.byUuid(it) }
            ?: throw IllegalStateException("未选中 Wine 包")
        val wineBin = File(WinfexPaths.packageFilesDir(winePkg.uuid, winePkg.category),
            "wine/bin/wine64").absolutePath
        if (!File(wineBin).exists()) {
            throw IllegalStateException("Wine 二进制未找到: $wineBin")
        }

        val isArm64 = WinfexPaths.isArm64
        val box64Pkg = sel.box64Uuid?.let { RatPackageManager.byUuid(it) }
        val box64Bin = if (isArm64 && box64Pkg != null) {
            File(WinfexPaths.packageFilesDir(box64Pkg.uuid, box64Pkg.category),
                "usr/bin/box64").absolutePath
        } else null

        return buildList {
            if (box64Bin != null) {
                add(box64Bin)
            }
            add(wineBin)

            if (params.desktopShell) {
                val res = params.prefix.desktopResolution
                if (res.isNullOrEmpty()) {
                    add("explorer")
                    add("/desktop=shell")
                } else {
                    add("explorer")
                    add("/desktop=shell,$res")
                }
            }
            add(params.exePath)
            if (params.arguments.isNotBlank()) {
                // 简单按空格拆分，不处理引号。复杂场景用户应直接传 List
                addAll(params.arguments.split(" ").filter { it.isNotEmpty() })
            }
        }
    }
}
