package com.winfex.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 Java/Kotlin 未捕获异常写到 filesDir/crash/。
 */
object WinfexCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "WinfexCrash"
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sb = StringBuilder()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            sb.append("==== Winfex Crash ====\n")
            sb.append("Time: ${sdf.format(Date())}\n")
            sb.append("Thread: ${t.name} (id=${t.id})\n")
            sb.append("Process: pid=${Process.myPid()} uid=${Process.myUid()}\n")
            sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            sb.append("Android: ${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})\n")
            sb.append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n")
            sb.append("\nStacktrace:\n")
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
            sb.append("\n")

            val file = WinfexPaths.crashFile()
            file.writeText(sb.toString())
            Log.e(TAG, "crash dumped to ${file.absolutePath}", e)
        } catch (_: Throwable) {}
        previous?.uncaughtException(t, e)
    }
}
