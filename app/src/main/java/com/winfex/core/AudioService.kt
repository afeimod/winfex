package com.winfex.core

import android.util.Log
import com.winfex.model.WinePrefix
import java.io.File

/**
 * PulseAudio 配置生成 + 启动。
 *
 * 对齐 MiceWine 的 SoundSettingsFragment + generatePAFile 逻辑：
 *   - 生成 pa_default.pa 配置文件
 *   - 启动 pulseaudio 守护进程
 *   - sink 可选 SLES 或 AAudio
 */
object AudioService {

    private const val TAG = "AudioService"

    private var startedPid: Int = -1

    /**
     * 生成 PulseAudio 配置文件。
     */
    fun generateConfig(prefix: WinePrefix) {
        val usrDir = WinfexPaths.usrDir.absolutePath
        val sink = prefix.audioSink.lowercase()
        val sb = StringBuilder()
        sb.append("#!").append(usrDir).append("/bin/pulseaudio -nF\n")
        sb.append(".fail\n\n")
        sb.append("load-module module-device-restore\n")
        sb.append("load-module module-stream-restore\n")
        sb.append("load-module module-card-restore\n")
        sb.append("load-module module-native-protocol-unix\n")
        sb.append("load-module module-default-device-restore\n")
        sb.append("load-module module-rescue-streams\n")
        sb.append("load-module module-always-sink\n")
        sb.append("load-module module-suspend-on-idle\n")
        sb.append("load-module module-intended-roles\n")
        sb.append("load-module module-").append(sink).append("-sink\n\n")
        sb.append(".nofail\n")
        sb.append(".include ").append(usrDir).append("/etc/pulse/default.pa.d\n")
        WinfexPaths.pulseConfigFile.writeText(sb.toString())
        Log.i(TAG, "pulse config generated, sink=$sink")
    }

    /**
     * 启动 PulseAudio 守护进程。如果已经在跑，跳过。
     */
    fun start(prefix: WinePrefix, onLine: (String) -> Unit): Int {
        generateConfig(prefix)
        if (startedPid > 0 && ProcessExecutor.runningSessions().any { it.pgid == startedPid }) {
            return startedPid
        }

        // 从 imagefs 找 pulseaudio
        val paBin = File("${ImageFsInstaller.imagefsDir.absolutePath}/usr/bin/pulseaudio")
        if (!paBin.exists()) {
            Log.w(TAG, "pulseaudio not found in imagefs, skipping")
            return -1
        }

        // 检查 libskcodec.so（Android 系统库，部分设备需要 LD_PRELOAD 才能正常跑 pa）
        val skcodec = File("/system/lib64/libskcodec.so")
        val preload = if (skcodec.exists()) skcodec.absolutePath else null

        val usrDir = WinfexPaths.usrDir.absolutePath
        val paBin = "$usrDir/bin/pulseaudio"
        if (!File(paBin).exists()) {
            Log.w(TAG, "pulseaudio not found at $paBin")
            return -1
        }

        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to "/system/lib64:$usrDir/lib",
            "HOME" to WinfexPaths.homeDir.absolutePath,
            "TMPDIR" to "${WinfexPaths.cacheDir.absolutePath}/tmp",
            "DISPLAY" to ":0"
        )
        if (preload != null) env["LD_PRELOAD"] = preload

        val argv = listOf(paBin, "--start", "--exit-idle=-1",
            "-n", "-F", WinfexPaths.pulseConfigFile.absolutePath)

        val logFile = WinfexPaths.logFile("pulseaudio")
        val spec = ProcessExecutor.ExecSpec(
            binary = paBin,
            argv = argv.drop(1),
            envp = env,
            workdir = usrDir,
            label = "pulseaudio"
        )
        return try {
            startedPid = ProcessExecutor.start(spec, logFile, onLine)
            startedPid
        } catch (e: Exception) {
            Log.e(TAG, "pulseaudio start failed", e); -1
        }
    }
}
