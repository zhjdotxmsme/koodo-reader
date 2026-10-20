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

    fun save(bookKey: String, cfi: String, fraction: Double) {
        if (bookKey.isBlank() || cfi.isBlank()) return
        sp.edit()
            .putString(key(bookKey), cfi)
            .putString(fracKey(bookKey), fraction.coerceIn(0.0, 1.0).toString())
            .apply()
    }

    fun fractionOf(bookKey: String): Double =
        sp.getString(fracKey(bookKey), null)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0

    /** bookKey → 0..1, for the /stats `progressByBook` fold. */
    fun allFractions(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        sp.all.forEach { (k, v) ->
            if (!k.startsWith(FRAC_PREFIX)) return@forEach
            val frac = (v as? String)?.toDoubleOrNull() ?: return@forEach
            out[k.removePrefix(FRAC_PREFIX)] = frac.coerceIn(0.0, 1.0)
        }
        return out
    }

    /** 清除某本书的进度（删除书籍/清除数据时）。 */
    fun clear(bookKey: String) {
        sp.edit().remove(key(bookKey)).remove(fracKey(bookKey)).apply()
    }

    private fun key(bookKey: String): String = "cfi:$bookKey"

    private fun fracKey(bookKey: String): String = FRAC_PREFIX + bookKey

    private companion object {
        const val FRAC_PREFIX = "frac:"
    }
}
