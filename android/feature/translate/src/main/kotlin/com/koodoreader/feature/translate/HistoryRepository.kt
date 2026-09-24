package com.koodoreader.feature.translate

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.security.MessageDigest
import java.util.UUID

/**
 * Selection-translation history (`translation_history`).
 *
 * Column naming follows the `core/data` convention (desktop `schema.lock` style:
 * camelCase quoted columns) even though this table has no desktop counterpart —
 * the desktop keeps translation results in the plugin cache, not in SQLite. The
 * Room schema for this table is therefore Android-owned; when the entity is
 * merged into `KoodoDatabase` (see docs/p6-translate-architecture.md) it is a
 * pure `ALTER`-free additive migration.
 */
@Entity(
    tableName = "translation_history",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["bookKey"]),
        Index(value = ["provider"]),
    ],
)
data class TranslationHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "sourceText") val sourceText: String,
    @ColumnInfo(name = "translatedText") val translatedText: String,
    @ColumnInfo(name = "sourceLang") val sourceLang: String? = null,
    @ColumnInfo(name = "targetLang") val targetLang: String,
    /** [TranslationSourceId.pluginKey] — survives provider renames. */
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "bookKey") val bookKey: String? = null,
    @ColumnInfo(name = "cfi") val cfi: String? = null,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
)

@Dao
interface TranslationHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TranslationHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<TranslationHistoryEntity>)

    @Query("SELECT * FROM translation_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TranslationHistoryEntity>

    @Query(
        "SELECT * FROM translation_history " +
            "WHERE sourceText LIKE '%' || :query || '%' OR translatedText LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<TranslationHistoryEntity>

    @Query("SELECT * FROM translation_history WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): TranslationHistoryEntity?

    @Query("SELECT * FROM translation_history WHERE bookKey = :bookKey ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forBook(bookKey: String, limit: Int): List<TranslationHistoryEntity>

    /** Rows older than the newest [keep] entries — the pruning work list. */
    @Query("SELECT * FROM translation_history ORDER BY createdAt DESC LIMIT -1 OFFSET :keep")
    suspend fun beyondNewest(keep: Int): List<TranslationHistoryEntity>

    @Query("DELETE FROM translation_history WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM translation_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM translation_history")
    suspend fun count(): Long
}

/**
 * History persistence + retrieval on top of [TranslationHistoryDao].
 *
 * Pure Kotlin (the DAO is injected), so the whole behaviour — dedupe key,
 * ordering, search matching, retention — is unit-tested against a fake DAO
 * without Room, the Android SDK or a device.
 */
class TranslationHistoryRepository(
    private val dao: TranslationHistoryDao,
    private val logger: Logger = NoopLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /**
     * Stores one translation. The same selection translated again by the same
     * provider into the same target language updates the existing row (and its
     * timestamp) instead of duplicating it.
     *
     * Logs only counts/lengths: the selected text is book content and the
     * credentials are never passed to this layer.
     */
    suspend fun record(
        sourceText: String,
        translatedText: String,
        targetLang: String,
        provider: TranslationSourceId,
        sourceLang: String? = null,
        bookKey: String? = null,
        cfi: String? = null,
    ): TranslationHistoryEntity {
        val entry = TranslationHistoryEntity(
            key = stableKey(sourceText, targetLang, provider),
            sourceText = sourceText,
            translatedText = translatedText,
            sourceLang = sourceLang,
            targetLang = targetLang,
            provider = provider.pluginKey,
            bookKey = bookKey,
            cfi = cfi,
            createdAt = clock(),
        )
        dao.upsert(entry)
        if (dao.count() > maxEntries) {
            prune()
        }
        logger.info(
            "translation history recorded: provider=${provider.pluginKey} target=$targetLang " +
                "sourceChars=${sourceText.length} translatedChars=${translatedText.length}",
        )
        return entry
    }

    suspend fun recent(limit: Int = DEFAULT_PAGE_SIZE): List<TranslationHistoryEntity> =
        dao.recent(limit)

    suspend fun search(query: String, limit: Int = DEFAULT_PAGE_SIZE): List<TranslationHistoryEntity> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) {
            dao.recent(limit)
        } else {
            dao.search(trimmed, limit)
        }
    }

    suspend fun forBook(bookKey: String, limit: Int = DEFAULT_PAGE_SIZE): List<TranslationHistoryEntity> =
        dao.forBook(bookKey, limit)

    /** Drops everything beyond the newest [keep] rows; returns the removed count. */
    suspend fun prune(keep: Int = maxEntries): Int {
        val excess = dao.beyondNewest(keep)
        excess.forEach { dao.deleteByKey(it.key) }
        if (excess.isNotEmpty()) {
            logger.info("translation history pruned: removed=${excess.size} keep=$keep")
        }
        return excess.size
    }

    suspend fun clear() {
        dao.deleteAll()
        logger.info("translation history cleared")
    }

    suspend fun count(): Long = dao.count()

    /**
     * Dedupe key: SHA-256 over `provider|target|text` (trimmed, case-folded).
     * Deterministic across launches and free of the raw selection text, so the
     * key can be logged or exported without leaking book content.
     */
    fun stableKey(sourceText: String, targetLang: String, provider: TranslationSourceId): String {
        val material = "${provider.pluginKey}|${targetLang.trim().lowercase()}|${sourceText.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "${provider.pluginKey.take(12)}-${hex.take(32)}"
    }

    /** In-memory matcher with the same semantics as the SQL `LIKE` query. */
    fun matches(entry: TranslationHistoryEntity, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return true
        }
        return entry.sourceText.contains(needle, ignoreCase = true) ||
            entry.translatedText.contains(needle, ignoreCase = true)
    }

    /** Creates a fresh id (exposed for tests that need the injected factory). */
    fun newId(): String = idFactory()

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
