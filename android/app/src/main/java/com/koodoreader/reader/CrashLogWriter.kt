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
 * 设计原则：**绝不影响 App 启动**。任何写文件、读包信息的失败都必须被静默吞掉，
 * 因为一个"崩溃记录器"自己崩掉 App 是最糟糕的事。
 *
 * 文件位置：`/sdcard/Android/data/com.koodoreader/files/crash-logs/crash.log`
 *   — 手机文件管理器可直接访问，或连 PC 后 `adb shell cat` 取值。
 */
object CrashLogWriter {

    private const val TAG = "KoodoCrashLog"
    private var logFile: File? = null
    private var threadHandlerBackup: Thread.UncaughtExceptionHandler? = null

    /** 由 [KoodoReaderApp.onCreate] 调用。本函数保证**永不抛出异常**。 */
    fun install(context: Context) {
        // logFile 必须在 handler 安装前定下来；失败则整个功能静默禁用，不影响 App。
        val file = try {
            val dir = context.getExternalFilesDir("crash-logs")
                ?: File(context.filesDir, "crash-logs")
            runCatching { dir.mkdirs() }
            File(dir, "crash.log")
        } catch (_: Throwable) {
            runCatching { File(context.filesDir, "crash.log") }.getOrNull()
        }
        if (file == null) {
            Log.w(TAG, "No usable log path; crash logging disabled")
            return
        }
        logFile = file

        // 启动即写一条（安全：失败被吞掉，绝不向上抛）
        runCatching { writeStartupLine(context) }
            .onFailure { Log.w(TAG, "startup line write failed", it) }

        threadHandlerBackup = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeCrashLog(thread, throwable)
                } catch (_: Throwable) {
                    // 记录失败也不能影响默认处理流程
                } finally {
                    threadHandlerBackup?.uncaughtException(thread, throwable)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to install handler", t)
        }
        Log.i(TAG, "Crash handler installed, log file: ${file.absolutePath}")
    }

    private fun writeStartupLine(context: Context) {
        val file = logFile ?: return
        val line = buildString {
            appendLine("══════════════════════════════════════════")
            appendLine("  [STARTUP] ${time()}")
            appendLine("  app   : ${context.packageName} v${safeVersion(context)}")
            appendLine("  android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("  device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
        file.appendText(line)
    }

    private fun safeVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val file = logFile ?: return
        val sb = StringBuilder().apply {
            appendLine()
            appendLine("══════════════ CRASH @ ${time()} ══════════════")
            appendLine("  thread: ${thread.name} (id=${thread.id})")
            appendLine("  class : ${throwable.javaClass.name}")
            appendLine("  msg   : ${throwable.message}")
            appendLine()
            try {
                val w = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(w, true))
                w.toString().lines().forEach { appendLine("  $it") }
            } catch (_: Throwable) { }

            // cause 链
            var cause = throwable.cause
            var depth = 1
            while (cause != null) {
                appendLine("  ── CAUSE #$depth: ${cause.javaClass.name}: ${cause.message} ──")
                try {
                    val cw = java.io.StringWriter()
                    cause.printStackTrace(java.io.PrintWriter(cw, true))
                    cw.toString().lines().forEach { appendLine("    $it") }
                } catch (_: Throwable) { }
                cause = cause.cause
                depth++
            }
            appendLine("══════════════════════════════════════════")
        }
        file.appendText(sb.toString())
        Log.e(TAG, "Crash log written to ${file.absolutePath}")
    }

    private fun time(): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    } catch (_: Throwable) {
        ""
    }
}
