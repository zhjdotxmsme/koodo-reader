package com.koodoreader.reader

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把异常堆栈写入手机本地文件，**不需要 adb** 也能拿到崩溃原因。
 *
 * 文件位置：`/sdcard/Android/data/com.koodoreader/files/crash-logs/crash.log`
 *   — 可用文件管理器直接访问，或连接 PC 后用 adb pull 获取。
 *
 * 每次崩溃都会追加一条记录（含时间戳 + 完整堆栈 + 设备信息），最多保留 20 条。
 */
object CrashLogWriter {

    private const val TAG = "KoodoCrash"
    private const val MAX_LOG_ENTRIES = 20
    private lateinit var logFile: File

    private var threadHandlerBackup: Thread.UncaughtExceptionHandler? = null

    /** 由 [KoodoReaderApp.onCreate] 调用。 */
    fun install(context: Context) {
        val dir = context.getExternalFilesDir("crash-logs")
            ?: File(context.filesDir, "crash-logs")
        dir.mkdirs()
        logFile = File(dir, "crash.log")

        // 启动即写一条，方便确认日志系统工作正常 + 记录启动时间
        writeStartupLine(context)

        threadHandlerBackup = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable, context)
            } finally {
                // 恢复原来的处理器（通常直接退出）
                threadHandlerBackup?.uncaughtException(thread, throwable)
                // 兜底：写不到系统 log 也至少留一条到文件
            }
        }
        Log.i(TAG, "Crash handler installed, log file: ${logFile.absolutePath}")
    }

    private fun writeStartupLine(context: Context) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val version = try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (_: Exception) { "unknown" }
        val line = buildString {
            appendLine("══════════════════════════════════════════")
            appendLine("  [STARTUP] $time")
            appendLine("  app: ${context.packageName} v$version")
            appendLine("  android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("  device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
        logFile.appendText(line)
    }

    private fun writeCrashLog(
        thread: Thread,
        throwable: Throwable,
        context: Context,
    ) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sb = StringBuilder().apply {
            appendLine()
            appendLine("══════════════ CRASH @ $time ══════════════")

            // 主异常
            appendLine("  thread: ${thread.name} (id=${thread.id})")
            appendLine("  class : ${throwable.javaClass.name}")
            appendLine("  msg   : ${throwable.message}")
            appendLine()

            // 完整堆栈
            val writer = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(writer, true))
            writer.toString().lines().forEach { appendLine("  $it") }

            // cause 链
            var cause = throwable.cause
            var depth = 1
            while (cause != null) {
                appendLine("  ── CAUSE #$depth: ${cause.javaClass.name}: ${cause.message} ──")
                val cw = java.io.StringWriter()
                cause.printStackTrace(java.io.PrintWriter(cw, true))
                cw.toString().lines().forEach { appendLine("    $it") }
                cause = cause.cause
                depth++
            }
            appendLine("══════════════════════════════════════════")
        }

        try {
            logFile.appendText(sb.toString())
            // Trim old entries
            trimLog()
            Log.e(TAG, "Crash log written to ${logFile.absolutePath}")
        } catch (e: Exception) {
            // 最后手段：至少用 system log 记一下
            Log.e(TAG, "CRASH (log file write failed):", throwable)
        }
    }

    private fun trimLog() {
        // 只保留最后 MAX_LOG_ENTRIES 个大块
        val lines = logFile.readLines()
        if (lines.size > 500) {
            val keep = lines.takeLast(500)
            logFile.writeText(keep.joinToString("\n"))
        }
    }
}
