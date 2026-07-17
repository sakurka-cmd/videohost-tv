package com.videohost.tv.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight file logger — writes to app-private storage so logs survive crashes
 * and can be collected via `adb shell run-as com.videohost.tv cat files/logs/...`
 * or via the in-app "Send logs" button in Settings.
 *
 * Why not Timber/ACRA? — to keep the APK small and dependency-free for TV boxes
 * with limited storage. This logger is ~120 LOC, no external deps.
 *
 * Files:
 *   files/logs/app-YYYY-MM-DD.log   — rolling daily log (INFO+)
 *   files/logs/crash-YYYY-MM-DD-HHMMSS.log — uncaught exception stack traces
 *
 * Retention: keep last 7 days of logs (auto-cleanup on init).
 */
object AppLogger {

    private const val TAG = "UTube"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024  // 2 MB per file, then rolls
    private const val KEEP_DAYS = 7

    private lateinit var logDir: File
    private var currentDate: String = ""
    private var currentFile: File? = null

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        cleanupOldLogs()
        // Install crash handler
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previousHandler))
        d("AppLogger", "initialized, logDir=${logDir.absolutePath}")
    }

    fun v(tag: String = TAG, msg: String) = write("V", tag, msg, null)
    fun d(tag: String = TAG, msg: String) = write("D", tag, msg, null)
    fun i(tag: String = TAG, msg: String) = write("I", tag, msg, null)
    fun w(tag: String = TAG, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String = TAG, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        // Mirror to logcat (so `adb logcat` also works)
        when (level) {
            "V" -> Log.v(tag, msg)
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg, t)
            "E" -> Log.e(tag, msg, t)
        }
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$ts $level/$tag: $msg" + (t?.let { "\n" + getStackTrace(it) } ?: "")
            synchronized(this) {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                if (dateStr != currentDate || currentFile == null) {
                    currentDate = dateStr
                    currentFile = File(logDir, "app-$dateStr.log")
                }
                val f = currentFile!!
                // Roll if too big
                if (f.exists() && f.length() > MAX_LOG_SIZE) {
                    val rolled = File(logDir, "app-$dateStr-${System.currentTimeMillis() % 100000}.log")
                    f.renameTo(rolled)
                    currentFile = File(logDir, "app-$dateStr.log")
                }
                currentFile!!.appendText(line + "\n")
            }
        } catch (_: Exception) {
            // Never let logging crash the app
        }
    }

    private fun cleanupOldLogs() {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
            logDir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
    }

    private fun getStackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** Collect all logs + crash logs into a single string for sharing. */
    fun collectForShare(): String {
        return try {
            val sb = StringBuilder()
            sb.append("=== UTube logs dump ===\n")
            sb.append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            sb.append("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            sb.append("App version: 2.0.6 (code 14)\n\n")

            // Crash logs first (most important)
            val crashes = logDir.listFiles { f -> f.name.startsWith("crash-") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (crashes.isNotEmpty()) {
                sb.append("=== CRASH LOGS (last ${crashes.size}) ===\n\n")
                for (c in crashes.take(3)) {
                    sb.append("--- ${c.name} ---\n")
                    sb.append(c.readText())
                    sb.append("\n")
                }
            } else {
                sb.append("=== NO CRASH LOGS ===\n\n")
            }

            // App logs (last 2 days)
            val apps = logDir.listFiles { f -> f.name.startsWith("app-") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            sb.append("=== APP LOGS (last ${apps.size} files) ===\n\n")
            for (a in apps.take(2)) {
                sb.append("--- ${a.name} ---\n")
                // Last 50 KB of each file (to keep shareable size reasonable)
                val text = a.readText()
                if (text.length > 50000) {
                    sb.append("... (truncated, showing last 50KB) ...\n")
                    sb.append(text.takeLast(50000))
                } else {
                    sb.append(text)
                }
                sb.append("\n")
            }

            sb.toString()
        } catch (e: Exception) {
            "Failed to collect logs: ${e.message}"
        }
    }

    /** Delete all logs (after user has shared them). */
    fun clearLogs() {
        try {
            logDir.listFiles()?.forEach { it.delete() }
            currentFile = null
            currentDate = ""
        } catch (_: Exception) {}
    }

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.US).format(Date())
                val crashFile = File(logDir, "crash-$ts.log")
                val pw = PrintWriter(crashFile)
                pw.println("=== UTube crash ===")
                pw.println("Time: $ts")
                pw.println("Thread: ${t.name} (id=${t.id})")
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                pw.println("App version: 2.0.6 (code 14)")
                pw.println()
                e.printStackTrace(pw)
                pw.close()
                // Also write to main log
                e("CrashHandler", "UNCAUGHT EXCEPTION on ${t.name}", e)
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
    }
}
