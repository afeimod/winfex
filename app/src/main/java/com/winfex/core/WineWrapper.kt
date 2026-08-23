package com.winfex.core

import android.util.Log
import com.winfex.model.WinePrefix
import java.io.File

/**
 * Wine 启动包装器（ARM64EC + FEX DLL 模式）。
 *
 * 架构（对齐 WinNative-Emu）：
 *
 *   - Wine 是 ARM64 原生 ELF，直接 execve 执行（不需要 box64/FEX 翻译 Wine 自身）
 *   - FEX 编译为 Windows DLL（libwow64fex.dll / libarm64ecfex.dll），
 *     被 Wine 的 WoW64/ARM64EC 层进程内加载
 *   - HODLL=libwow64fex.dll 告诉 Wine 用 FEX 做 32 位 x86 PE 翻译
 *   - libarm64ecfex.dll 做 64 位 x86_64 PE 翻译
 *   - 路径重定向靠环境变量（LD_LIBRARY_PATH / PATH / HOME 等）+ LD_PRELOAD libredirect.so
 *   - 不需要 binfmt_misc / FEXLoader / root
 *
 * 启动链路：
 *   app → ProcessBuilder.exec("wine <exe>")
 *            │  env: HODLL=libwow64fex.dll
 *            │       LD_PRELOAD=libredirect.so:libandroid-sysvshm.so
 *            │       LD_LIBRARY_PATH=imagefs/usr/lib:/system/lib64
 *            │       HOME=imagefs/home/xuser
 *            │       WINEPREFIX=imagefs/home/xuser-<id>/.wine
 *            │       DISPLAY=:13
 *            │
 *            ├─ wine (ARM64 原生进程)
 *            │    ├─ 加载 libarm64ecfex.dll (FEX x86_64 JIT)
 *            │    ├─ 加载 libwow64fex.dll  (FEX i386 JIT)
 *            │    ├─ [x86_64 PE] → libarm64ecfex.dll JIT → ARM64 执行
 *            │    └─ [i386 PE]   → libwow64fex.dll JIT  → ARM64 执行
 *            │
 *            └─ wineserver (ARM64 原生)
 *
 * 回退模式（无 FEX DLL 时）：
 *   app → ProcessBuilder.exec("box64 wine <exe>")
 *         Box64 翻译整个 x86_64 Wine 进程
 */
object WineWrapper {

    private const val TAG = "WineWrapper"

    data class LaunchParams(
        val prefix: WinePrefix,
        val exePath: String,
        val arguments: String = "",
        val workdir: String? = null,
        val extraEnv: Map<String, String> = emptyMap()
    )

    /**
     * 启动一个 Windows 程序。
     *
     * 优先使用 ARM64EC + FEX DLL 模式（无 root，性能最优）。
     * 如果 FEX DLL 不存在，回退到 Box64 模式。
     *
     * @return pgid
     */
    fun launch(params: LaunchParams, onLine: (String) -> Unit): Int {
        val useArm64EC = isArm64ECAvailable()
        val env = buildEnv(params.prefix, useArm64EC)
        val argv = buildArgv(params, useArm64EC)
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

        Log.i(TAG, "launching ${params.exePath} (arm64ec=$useArm64EC)")
        return ProcessExecutor.start(spec, logFile, onLine)
    }

    /**
     * 检查 ARM64EC + FEX DLL 是否可用。
     *
     * 条件：
     *   1. Wine 二进制是 ARM64 ELF（不是 x86_64）
     *   2. libwow64fex.dll 存在于 wineprefix system32
     *   3. libarm64ecfex.dll 存在于 wineprefix system32
     */
    private fun isArm64ECAvailable(): Boolean {
        val imagefs = ImageFsInstaller.imagefsDir.absolutePath
        val wineBin = File("$imagefs/opt/wine/bin/wine")
        if (!wineBin.exists()) return false

        // 检查 FEX DLL 是否存在
        val prefixDir = WinfexPaths.prefixesDir
        val system32 = File("$prefixDir/default/drive_c/windows/system32")
        val fex32 = File(system32, "libwow64fex.dll")
        val fex64 = File(system32, "libarm64ecfex.dll")

        // DLL 可能还没安装到 prefix，也检查 opt/wine/lib 里有没有
        val wineLib = File("$imagefs/opt/wine/lib/wine")
        val fex32InWine = File(wineLib, "libwow64fex.dll")
        val fex64InWine = File(wineLib, "libarm64ecfex.dll")

        return fex32.exists() && fex64.exists() || fex32InWine.exists() && fex64InWine.exists()
    }

    /** 仅初始化 prefix */
    fun initPrefix(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "wineboot",
                arguments = "--init",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath
            ), onLine
        )
    }

    fun shutdownPrefix(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "wineboot",
                arguments = "--end",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath
            ), onLine
        )
    }

    fun runWinecfg(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        return launch(
            LaunchParams(
                prefix = prefix,
                exePath = "winecfg",
                workdir = WinfexPaths.prefixDir(prefix.id).absolutePath
            ), onLine
        )
    }

    // ===== env =====

    fun buildEnv(prefix: WinePrefix, useArm64EC: Boolean): Map<String, String> {
        val imagefs = ImageFsInstaller.imagefsDir.absolutePath
        val wineBin = "$imagefs/opt/wine/bin"
        val usrLib = "$imagefs/usr/lib"

        val env = LinkedHashMap<String, String>()

        // 身份与路径（对齐 WinNative GuestProgramLauncherComponent）
        env["HOME"] = "$imagefs/home/xuser"
        env["USER"] = "xuser"
        env["TMPDIR"] = "$imagefs/usr/tmp"
        env["WINEPREFIX"] = WinfexPaths.prefixDir(prefix.id).absolutePath
        env["WINEARCH"] = "win64"
        env["WINEDEBUG"] = "-all"
        env["WINEDLLOVERRIDES"] = "mscoree,mshtml=d;winemenubuilder.exe=d"
        env["WINEESYNC"] = if (prefix.esync) "1" else "0"
        env["WINEFSYNC"] = if (prefix.fsync) "1" else "0"

        // PATH（Wine bin 优先）
        env["PATH"] = "$wineBin:$imagefs/usr/bin:/system/bin:/system/xbin"

        // 库路径 + LD_PRELOAD 重定向
        env["LD_LIBRARY_PATH"] = "/system/lib64:$usrLib"

        // LD_PRELOAD: libredirect.so + libandroid-sysvshm.so
        val ldPreload = StringBuilder()
        val libredirect = File("$usrLib/libredirect.so")
        if (libredirect.exists()) ldPreload.append(libredirect.absolutePath)
        val sysvshm = File("$usrLib/libandroid-sysvshm.so")
        if (sysvshm.exists()) {
            if (ldPreload.isNotEmpty()) ldPreload.append(":")
            ldPreload.append(sysvshm.absolutePath)
        }
        if (ldPreload.isNotEmpty()) {
            env["LD_PRELOAD"] = ldPreload.toString()
        }

        // Display
        env["DISPLAY"] = XServerManager.displayString()
        env["XDG_RUNTIME_DIR"] = WinfexPaths.cacheDir.absolutePath

        // Vulkan / 图形
        env["VK_ICD_FILENAMES"] = "$imagefs/etc/vulkan/vulkan_icd.json"
        env["VK_DRIVER_FILES"] = "$imagefs/etc/vulkan/vulkan_icd.json"
        env["GALLIUM_DRIVER"] = "zink"
        env["ZINK_DEBUG"] = "compact"
        env["ZINK_DESCRIPTORS"] = "lazy"
        if (prefix.tuDebugPreset.isNotEmpty()) {
            env["TU_DEBUG"] = prefix.tuDebugPreset
        }

        // DXVK
        env["DXVK_ASYNC"] = "1"
        env["DXVK_STATE_CACHE_PATH"] = "$imagefs/home/xuser/.cache/dxvk-shader-cache"
        env["DXVK_HUD"] = prefix.dxvkHud.joinString(",")

        // VKD3D
        env["VKD3D_FEATURE_LEVEL"] = prefix.vkd3dFeatureLevel

        // ARM64EC + FEX DLL 模式
        if (useArm64EC) {
            // HODLL = 32位 x86 模拟器 DLL（FEX）
            env["HODLL"] = "libwow64fex.dll"
            // 64位 x86_64 模拟器 DLL 通过 Wine 注册表配置（libarm64ecfex.dll）
            // 不需要环境变量，Wine ARM64EC 层自动加载
        }

        // Box64（回退模式）
        if (!useArm64EC) {
            env["BOX64_DYNAREC"] = "1"
            env["BOX64_NOBANNER"] = "1"
            env["BOX64_X11GLX"] = "1"
            env["BOX64_NORCFILES"] = "1"
            env["BOX64_LOG"] = prefix.box64Log
            env["BOX64_DYNAREC_BIGBLOCK"] = prefix.box64DynarecBigBlock.toString()
            env["BOX64_DYNAREC_STRONGMEM"] = prefix.box64DynarecStrongMem.toString()
            env["BOX64_MMAP32"] = prefix.box64Mmap32.toString()
            env["BOX64_LD_LIBRARY_PATH"] = "$usrLib/x86_64-linux-gnu"
        }

        // PulseAudio
        env["PULSE_LATENCY_MSEC"] = prefix.pulseLatencyMs.toString()

        // 字体 / 资源
        env["FONTCONFIG_PATH"] = "$imagefs/etc/fonts"
        env["XDG_DATA_DIRS"] = "$imagefs/usr/share"
        env["XDG_CONFIG_DIRS"] = "$imagefs/etc/xdg"

        // 加密
        env["OPENSSL_CONF"] = "$imagefs/etc/tls/openssl.cnf"
        env["SSL_CERT_FILE"] = "$imagefs/etc/tls/cert.pem"

        // SysV 共享内存桥
        env["ANDROID_SYSVSHM_SERVER"] = "$imagefs/sysvshm_server"

        // FEX 配置（ARM64EC 模式下可选，用于 App 级调优）
        if (useArm64EC) {
            env["FEX_TSOENABLED"] = "1"
            env["FEX_MULTIBLOCK"] = "1"
        }

        return env
    }

    // ===== argv =====

    private fun buildArgv(params: LaunchParams, useArm64EC: Boolean): List<String> {
        val imagefs = ImageFsInstaller.imagefsDir.absolutePath
        val wineBin = "$imagefs/opt/wine/bin/wine"

        if (!File(wineBin).exists()) {
            throw IllegalStateException(
                "Wine 二进制未找到: $wineBin\n" +
                "请确保 assets/components/wine-arm64ec.tar.xz 已正确解压到 imagefs/opt/wine/"
            )
        }

        return buildList {
            if (useArm64EC) {
                // ARM64EC 模式：直接执行原生 ARM64 Wine
                // FEX 通过 HODLL/libarm64ecfex.dll 进程内翻译 x86 PE
                add(wineBin)
            } else {
                // 回退模式：用 Box64 翻译整个 x86_64 Wine
                val box64Bin = "$imagefs/usr/bin/box64"
                if (File(box64Bin).exists()) {
                    add(box64Bin)
                    if (params.prefix.box64Log != "0") {
                        add("--enable-log")
                    }
                }
                add(wineBin)
            }

            // explorer /desktop=shell,WxH（非工具命令时）
            if (params.exePath != "wineboot" && params.exePath != "winecfg" &&
                params.exePath != "regedit") {
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
                addAll(params.arguments.split(" ").filter { it.isNotEmpty() })
            }
        }
    }
}
