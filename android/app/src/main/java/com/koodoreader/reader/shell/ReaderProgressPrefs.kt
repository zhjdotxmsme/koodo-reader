package com.koodoreader.reader.shell

import android.content.Context
import android.content.SharedPreferences

/**
 * 阅读进度持久化（bookKey → 位置 CFI）。
 *
 * 与桌面一致的口径：桌面把阅读进度存在 ConfigService（`recordLocation`
 * 出去的 CFI），**不改 books 表**——原生侧同样放在 SharedPreferences，
 * 让 schema.lock 冻结的五张表保持逐字干净（同 [LibraryPrefs] 的理由）。
 *
 * 存的是 [com.koodoreader.engine.layout.CfiAddressing] 产出的位置 CFI；
 * 打开书时经 `ReaderSession.resumePage(cfi)` 还原到对应页，翻页时写回。
 */
class ReaderProgressPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("reader_progress", Context.MODE_PRIVATE)

    /** 该书最后阅读位置的 CFI；从未读过返回 null。 */
    fun cfiOf(bookKey: String): String? =
        sp.getString(key(bookKey), null)?.takeIf { it.isNotBlank() }

    /** 记录位置（翻页后调用）。 */
    fun save(bookKey: String, cfi: String) {
        if (bookKey.isBlank() || cfi.isBlank()) return
        sp.edit().putString(key(bookKey), cfi).apply()
    }

    /** 清除某本书的进度（删除书籍/清除数据时）。 */
    fun clear(bookKey: String) {
        sp.edit().remove(key(bookKey)).apply()
    }

    private fun key(bookKey: String): String = "cfi:$bookKey"
}
