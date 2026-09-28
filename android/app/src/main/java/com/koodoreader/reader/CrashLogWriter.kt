package com.koodoreader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获，多渠道兜底，保证"总能拿回日志"，且**绝不影响 App 启动**。
 *
 * 日志落点（任一可行即可取回）：
 *  1. **剪贴板**（首选，最省事）—— 崩溃后自动把整段堆栈塞进剪贴板；
 *     下次任意启动也会把上一次崩溃日志重贴进剪贴板并 Toast。用户随便开个输入框粘贴即可。
 *  2. **内建存储** `/data/data/com.koodoreader/crash.log` —— 无需权限，恒可写（adb 可取）。
 *  3. **外置存储** `/sdcard/Android/data/com.koodoreader/files/crash-logs/crash.log` —— 文件管理器可见。
 *  4. **logcat** tag `KoodoCrashLog`。
 *
 * 设计原则：崩溃记录器自己绝不能成为崩溃源，所有 IO/服务调用都静默吞异常。
 */
object CrashLogWriter {

    private const val TAG = "KoodoCrashLog"
    private var appContext: Context? = null
    private var internalFile: File? = null
    private var externalFile: File? = null
    private var threadHandlerBackup: Thread.UncaughtExceptionHandler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由 [KoodoReaderApp.onCreate] 调用。保证**永不抛出异常**。 */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app

        // 1) 内建存储：恒可写、无需权限（首选的"保底文件"）
        internalFile = try {
            val f = File(app.filesDir, "crash.log")
            runCatching { f.parentFile?.mkdirs() }
            f
        } catch (_: Throwable) { null }

        // 2) 外置存储：文件管理器可见
        externalFile = try {
            val dir = app.getExternalFilesDir("crash-logs")
            if (dir == null) null
            else {
                runCatching { dir.mkdirs() }
                File(dir, "crash.log")
            }
        } catch (_: Throwable) { null }

        // 启动即写一条（任何失败都被吞）
        tryWriteStartup(app)

        // 若上一次会话崩溃留下了日志，立刻把它重贴进剪贴板，方便用户直接取走
        copyLatestCrashToClipboardSafely(app)

        threadHandlerBackup = try { Thread.getDefaultUncaughtExceptionHandler() } catch (_: Throwable) { null }
        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    handleCrash(thread, throwable)
                } catch (_: Throwable) { }
                try {
                    threadHandlerBackup?.uncaughtException(thread, throwable)
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to install handler", t)
        }
        Log.i(TAG, "installed. internal=${internalFile?.absolutePath} external=${externalFile?.absolutePath}")
    }

    // ── 崩溃处理 ─────────────────────────────────────────────────────────────
    private fun handleCrash(thread: Thread, throwable: Throwable) {
        val text = buildCrashText(thread, throwable)
        tryWrite(text)
        copyToClipboard(appContext, text)
        Log.e(TAG, "CRASH\n$text")
    }

    /** 把崩溃文本追加到所有可行文件 + 立即贴剪贴板。 */
    private fun tryWrite(text: String) {
        try { internalFile?.appendText(text) } catch (_: Throwable) { }
        try { externalFile?.appendText(text) } catch (_: Throwable) { }
    }

    private fun buildCrashText(thread: Thread, throwable: Throwable): String = buildString {
        appendLine()
        appendLine("══════════════ CRASH @ ${time()} ══════════════")
        appendLine("  app    : ${contextInfo()}")
        appendLine("  thread : ${thread.name} (id=${thread.id})")
        appendLine("  class  : ${throwable.javaClass.name}")
        appendLine("  message: ${throwable.message}")
        appendLine()
        try {
            val w = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(w, true))
            w.toString().lines().forEach { appendLine("  $it") }
        } catch (_: Throwable) { }

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

    private fun contextInfo(): String {
        val app = appContext ?: return "unknown"
        return try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            "${app.packageName} v${info.versionName}(${info.versionCode}) " +
                "android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun tryWriteStartup(app: Context) {
        val line = buildString {
            appendLine("══════════════════════════════════════════")
            appendLine("  [STARTUP] ${time()}")
            appendLine("  " + contextInfo())
            appendLine("  log paths:")
            appendLine("    internal: ${internalFile?.absolutePath}")
            appendLine("    external: ${externalFile?.absolutePath}")
            if (internalFile == null && externalFile == null) appendLine("    !! NO FILE PATH AVAILABLE — only clipboard/logcat !")
        }
        tryWrite(line)
        Log.i(TAG, "startup\n$line")
    }

    // ── 剪贴板兜底 ───────────────────────────────────────────────────────────
    /** 若存在上一次崩溃，把最新一段贴进剪贴板并 Toast。 */
    private fun copyLatestCrashToClipboardSafely(app: Context) {
        val file = externalFile ?: internalFile ?: return
        val crash = try {
            val text = file.readText()
            val idx = text.lastIndexOf("CRASH @")
            if (idx < 0) null else text.substring(text.lastIndexOf('═', (idx - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it })
        } catch (_: Throwable) {
            null
        }
        if (crash != null && crash.isNotBlank()) {
            copyToClipboard(app, crash)
            showToast(app, "检测到上次崩溃，日志已复制到剪贴板（可粘贴给我）")
        }
    }

    private fun copyToClipboard(app: Context?, text: String) {
        val ctx = app ?: return
        try {
            val run = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("koodo-crash", text))
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run()
            else mainHandler.post { run() }
        } catch (_: Throwable) { }
    }

    private fun showToast(ctx: Context, msg: String) {
        try {
            val run = {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run()
            else mainHandler.post { run() }
        } catch (_: Throwable) { }
    }

    private fun time(): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    } catch (_: Throwable) {
        ""
    }
}
