package com.winfex.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.winfex.core.ImageFsInstaller
import com.winfex.core.WinePrefixManager
import com.winfex.core.WinfexPaths
import com.winfex.core.XServerManager
import com.winfex.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        renderStatus()

        b.btnClearCache.setOnClickListener {
            WinfexPaths.cacheDir.deleteRecursively()
            WinfexPaths.cacheDir.mkdirs()
            com.google.android.material.snackbar.Snackbar
                .make(b.root, "已清理缓存", 1500).show()
        }
        b.btnClearLogs.setOnClickListener {
            WinfexPaths.logsDir.listFiles()?.forEach { it.delete() }
            com.google.android.material.snackbar.Snackbar
                .make(b.root, "已清理日志", 1500).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val sb = StringBuilder()
        sb.append("设备 ABI: ").append(WinfexPaths.deviceAbi).append("\n")
        sb.append("私有目录:\n  ").append(WinfexPaths.baseDir.absolutePath).append("\n\n")

        // X Server 状态
        val xState = XServerManager.state.value
        sb.append("X Server: ").append(when (xState) {
            XServerManager.State.READY -> "✓ 运行中"
            XServerManager.State.STARTING -> "... 启动中"
            XServerManager.State.FAILED -> "✗ 失败"
            XServerManager.State.STOPPED -> "○ 未启动"
        }).append("\n")
        sb.append("  DISPLAY: ").append(XServerManager.displayString()).append("\n")
        sb.append("  socket: ").append(XServerManager.socketFile().absolutePath).append("\n\n")

        sb.append("已安装组件: ").append(ImageFsInstaller.getComponentStatus().size).append(" 个\n")
        sb.append("ImageFS 已安装: ").append(if (ImageFsInstaller.isInstalled()) "✓" else "✗").append("\n")
        sb.append("\n组件状态:\n")
        for (c in ImageFsInstaller.getComponentStatus()) {
            sb.append("  ").append(c.displayName).append(": ")
            sb.append(if (c.installed) "✓ 已安装" else "✗ 未安装")
            sb.append("\n")
        }

        sb.append("\nWine 前缀: ").append(WinePrefixManager.prefixes.value.size).append(" 个\n")

        b.tvStatus.text = sb.toString()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
