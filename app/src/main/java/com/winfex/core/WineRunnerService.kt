package com.winfex.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.winfex.R

/**
 * Wine 运行前台服务：保持 Wine 进程在用户切后台时不被系统杀。
 *
 * 启动流程：
 *   1. 收到 startForegroundService 调用，带 pgid + label
 *   2. 确保 X server 已就绪（如果还没起，启动它）
 *   3. 注册前台通知
 *   4. 等待 Wine 进程退出
 *   5. Wine 退出后，如果还有其他会话就保留 X server，否则延迟 30 秒后停掉
 *
 * X server 生命周期由 [XServerManager] 管理，本 Service 只负责保活 Wine。
 */
class WineRunnerService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pgid = intent?.getIntExtra(EXTRA_Pgid, -1) ?: -1
        val label = intent?.getStringExtra(EXTRA_Label) ?: "Wine"
        val autoStartX = intent?.getBooleanExtra(EXTRA_AutoStartX, true) ?: true
        if (pgid <= 0) {
            stopSelf(); return START_NOT_STICKY
        }

        // 同步确保 X server 就绪（Wine 启动失败的话至少 X server 在跑）
        if (autoStartX && XServerManager.state.value != XServerManager.State.READY) {
            Thread({
                val ok = XServerManager.start(this, XServerManager.StartMode.AUTO)
                if (!ok) {
                    android.util.Log.w(TAG, "X server failed to start, Wine may not display")
                }
            }, "xserver-starter").start()
        }

        val notif = buildNotification(label, "pid=$pgid · ${XServerManager.displayString()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        Thread({
            ProcessExecutor.waitFor(pgid)
            // Wine 退出后，给一个宽限期让其他会话能继续用 X server
            Thread.sleep(30_000)
            if (ProcessExecutor.runningSessions().isEmpty()) {
                XServerManager.stop()
            }
            stopSelf()
        }, "wine-runner-watcher-$pgid").start()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Service 被杀时，如果还有 Wine 在跑，让 X server 继续活
        if (ProcessExecutor.runningSessions().isEmpty()) {
            XServerManager.stop()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Wine 运行时",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 Windows 程序在后台继续运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(label: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Winfex - $label")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_wine)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "WineRunnerService"
        const val CHANNEL_ID = "winfex_runtime"
        const val NOTIFICATION_ID = 0x1701
        const val EXTRA_Pgid = "winfex.pgid"
        const val EXTRA_Label = "winfex.label"
        const val EXTRA_AutoStartX = "winfex.autostart_x"

        fun start(context: Context, pgid: Int, label: String, autoStartX: Boolean = true) {
            val i = Intent(context, WineRunnerService::class.java).apply {
                putExtra(EXTRA_Pgid, pgid)
                putExtra(EXTRA_Label, label)
                putExtra(EXTRA_AutoStartX, autoStartX)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
