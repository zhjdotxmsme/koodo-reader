package com.koodoreader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用内日志 (应用自带 "logcat"): 每次 [d/i/w/e] 调用同时
 *  1. 走标准 [Log] → 真正的 adb logcat 仍然可用;
 *  2. 存进进程内环形缓冲 → 设置 → 数据 → 日志 屏可随时翻看/复制,
 *     无需电脑. WebView console 捕获也走这里 (见 MainActivity
 *     onConsoleMessage), 所以 pdfjs / EPUB 引擎的 JS 报错同样能看到.
 *
 * 环形缓冲只存最近 [CAPACITY] 条, 超出丢最旧的; 全部内存操作,
 * 崩溃现场取证仍由 [CrashLogWriter] 负责 (它写文件/剪贴板/logcat).
 */
object AppLog {

    private const val CAPACITY = 1000
    private const val STACK_LINE_LIMIT = 12

    private val buffer = ArrayDeque<String>(CAPACITY)
    private val time = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) = log(Log.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(Log.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    private fun log(priority: Int, tag: String, msg: String, tr: Throwable?) {
        // 真正的 logcat 不受影响.
        Log.println(priority, tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "V"
        }
        val sb = StringBuilder()
        sb.append(time.get()!!.format(Date())).append(' ').append(level).append('/').append(tag)
            .append(": ").append(msg)
        if (tr != null) {
            val frames = tr.stackTrace.take(STACK_LINE_LIMIT)
            sb.append("\n  ").append(tr.javaClass.name).append(": ").append(tr.message)
            frames.forEach { f ->
                sb.append("\n    at ").append(f.className).append('.').append(f.methodName)
                    .append('(').append(f.fileName).append(':').append(f.lineNumber).append(')')
            }
        }
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(sb.toString())
        }
    }

    /** 当前缓冲快照 (最旧在前), 供日志屏渲染与复制. */
    fun dump(): List<String> = synchronized(buffer) { buffer.toList() }

    fun dumpText(): String = dump().joinToString("\n")

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** 把日志全文复制到剪贴板; 返回是否成功 (Toast 由调用方决定). */
    fun copyToClipboard(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("app log", dumpText()))
        true
    }.getOrDefault(false)
}
