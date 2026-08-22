package com.winfex.core

import android.util.Log
import com.winfex.native.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 进程执行器：fork + execve 启动外部二进制，pipe 读 stdout/stderr。
 *
 * 与 MiceWine 的 shell_loader.c 不同，我们走直接 execve，不走 /system/bin/sh。
 * 这样省一层解析，且能精确控制 envp / argv。
 */
object ProcessExecutor {

    private const val TAG = "ProcessExecutor"
    private val pipeReaderDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "winfex-pipe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val sessions = mutableMapOf<Int, Session>()

    data class Session(
        val pgid: Int,
        val label: String,
        val startedAt: Long,
        val logFile: File,
        @Volatile var exited: Boolean = false,
        @Volatile var exitCode: Int = -1
    )

    data class ExecSpec(
        val binary: String,
        val argv: List<String>,
        val envp: Map<String, String>,
        val workdir: String?,
        val label: String,
        val cpuMask: Long = 0
    )

    fun start(spec: ExecSpec, logFile: File, onLine: (String) -> Unit = {}): Int {
        val stdoutPipe = createPipe()
        val stderrPipe = createPipe()

        val argv = spec.argv.toTypedArray()
        val envp = spec.envp.entries.map { "${it.key}=${it.value}" }.toTypedArray()

        val outPid = IntArray(1)
        val rc = NativeBridge.nativeExecBinary(
            path = spec.binary,
            argv = argv,
            envp = envp,
            workdir = spec.workdir,
            stdinFd = -1,
            stdoutFd = stdoutPipe.writeFd,
            stderrFd = stderrPipe.writeFd,
            outPid = outPid
        )

        if (rc != 0) {
            closePipe(stdoutPipe)
            closePipe(stderrPipe)
            throw IOException("nativeExecBinary rc=$rc")
        }

        val pgid = outPid[0]
        val session = Session(pgid, spec.label, System.currentTimeMillis(), logFile)
        synchronized(sessions) { sessions[pgid] = session }

        OsCompat.closeFd(stdoutPipe.writeFd)
        OsCompat.closeFd(stderrPipe.writeFd)

        launchOnPipeReader {
            readPipeToLog(stdoutPipe.readFd, logFile, onLine, session)
            readPipeToLog(stderrPipe.readFd, logFile, onLine, session)
        }

        Log.i(TAG, "started ${spec.label} pid=$pgid log=${logFile.name}")
        return pgid
    }

    fun kill(pgid: Int) {
        synchronized(sessions) { sessions.remove(pgid) }
        NativeBridge.nativeKillProcessGroup(pgid)
    }

    fun runningSessions(): List<Session> = synchronized(sessions) { sessions.values.toList() }

    suspend fun waitFor(pgid: Int): Int = withContext(Dispatchers.IO) {
        val session = synchronized(sessions) { sessions[pgid] } ?: return@withContext -1
        while (!session.exited) Thread.sleep(200)
        session.exitCode
    }

    private data class Pipe(val readFd: Int, val writeFd: Int)

    private fun createPipe(): Pipe {
        val pfd = android.os.ParcelFileDescriptor.createPipe()
        return Pipe(pfd[0].fd, pfd[1].fd)
    }

    private fun closePipe(p: Pipe) {
        OsCompat.closeFd(p.readFd)
        OsCompat.closeFd(p.writeFd)
    }

    private fun readPipeToLog(fd: Int, log: File, onLine: (String) -> Unit, session: Session) {
        try {
            FileInputStream(android.os.ParcelFileDescriptor.fromFd(fd).fileDescriptor).use { fis ->
                val buf = ByteArray(4096)
                val sb = StringBuilder()
                FileOutputStream(log, true).use { fos ->
                    while (true) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        sb.append(String(buf, 0, n))
                        fos.write(buf, 0, n)
                        fos.flush()
                        while (true) {
                            val nl = sb.indexOf('\n')
                            if (nl < 0) break
                            val line = sb.substring(0, nl)
                            sb.delete(0, nl + 1)
                            onLine(line)
                        }
                    }
                    if (sb.isNotEmpty()) onLine(sb.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readPipe failed pid=${session.pgid}", e)
        } finally {
            session.exited = true
        }
    }

    private fun launchOnPipeReader(block: suspend () -> Unit) {
        GlobalScope.launch(pipeReaderDispatcher) {
            block()
        }
    }
}

object OsCompat {
    fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) { false }

    fun closeFd(fd: Int) {
        try {
            val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
            try { android.system.Os.close(pfd.fileDescriptor) } catch (_: Throwable) {}
            // detach() 是 API 31+，用 close() 兜底
            try { pfd.close() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
