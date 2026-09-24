// feature/ocr — scanned-page OCR index + search loop (P6).
//
// The closed loop this file implements:
//
//   scanned page ──▶ ML Kit model available?  ──▶ recognise ──▶ normalise
//        ▲                    │ on demand                    │
//        │                    ▼                              ▼
//        │            OnDemandDownloader            ocr_pages (Room)
//        │                                                   │
//        └──────── reader jumps to the hit ◀── search() ◀─────┘
//
// Desktop context: the web build recognises a *selection* through
// `getOcrResult`/`getOcrResultV2` (public/lib/esearch-ocr + onnxruntime-web,
// PNG-in/text-out, no client-side index) and the desktop app shells out to the
// Windows/macOS OCR engines per image. Native Android does the same per page but
// additionally indexes the recognised text, because a scanned PDF has no text
// layer to search — this is where the "扫描页 OCR 检索" requirement lands.
//
// Persistence lives in `ocr_pages`. The entity/DAO are declared here so the
// module stays self-contained; docs/p6-stats-ocr-design.md §4.2 has the exact
// main-thread patch that moves the table into :core:data's koodo.db.
package com.koodoreader.feature.ocr

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * OCR text of one scanned page. `key` is `bookKey#pageIndex#script`, so
 * re-scanning a page (or scanning it with another script) replaces its row
 * instead of duplicating hits.
 */
@Entity(tableName = "ocr_pages")
data class OcrPageEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "bookKey") val bookKey: String,
    @ColumnInfo(name = "pageIndex") val pageIndex: Int,
    @ColumnInfo(name = "script") val script: String,
    /** Raw engine output (line-joined) — kept for the "copy page text" action. */
    @ColumnInfo(name = "text") val text: String,
    /** Normalised text used for matching (`OcrTextNormalizer.normalize`). */
    @ColumnInfo(name = "normalizedText") val normalizedText: String,
    @ColumnInfo(name = "tokenCount") val tokenCount: Int,
    @ColumnInfo(name = "recognizedAt") val recognizedAt: Long,
    @ColumnInfo(name = "durationMillis") val durationMillis: Long = 0L,
    @ColumnInfo(name = "engine") val engine: String = ENGINE_MLKIT,
) {
    companion object {
        const val ENGINE_MLKIT = "mlkit-text-recognition-v2"
    }
}

@Dao
interface OcrPageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OcrPageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<OcrPageEntity>)

    @Query("SELECT * FROM ocr_pages ORDER BY bookKey ASC, pageIndex ASC")
    suspend fun loadAll(): List<OcrPageEntity>

    @Query("SELECT * FROM ocr_pages WHERE `key` = :key LIMIT 1")
    suspend fun loadByKey(key: String): OcrPageEntity?

    @Query("SELECT * FROM ocr_pages WHERE bookKey = :bookKey ORDER BY pageIndex ASC")
    suspend fun loadForBook(bookKey: String): List<OcrPageEntity>

    /**
     * Coarse prefilter; the repository ranks the candidates in Kotlin so the
     * ranking stays testable. A LIKE scan is fine for one book; a library-wide
     * FTS4/FTS5 `ocr_pages_fts` table is the documented follow-up (design doc §6).
     */
    @Query("SELECT * FROM ocr_pages WHERE normalizedText LIKE '%' || :needle || '%'")
    suspend fun prefilter(needle: String): List<OcrPageEntity>

    @Query("SELECT COUNT(*) FROM ocr_pages WHERE bookKey = :bookKey")
    suspend fun countForBook(bookKey: String): Int

    @Query("SELECT COUNT(*) FROM ocr_pages")
    suspend fun count(): Int

    @Query("DELETE FROM ocr_pages WHERE bookKey = :bookKey")
    suspend fun deleteForBook(bookKey: String)

    @Query("DELETE FROM ocr_pages")
    suspend fun clear()
}

/** Storage seam of [OcrSearchRepository]. */
interface OcrIndexStore {
    suspend fun upsertAll(rows: List<OcrPageEntity>)
    suspend fun all(): List<OcrPageEntity>
    suspend fun forBook(bookKey: String): List<OcrPageEntity>
    suspend fun prefilter(needle: String): List<OcrPageEntity>
    suspend fun deleteForBook(bookKey: String)
    suspend fun size(): Int
}

class RoomOcrIndexStore(private val dao: OcrPageDao) : OcrIndexStore {
    override suspend fun upsertAll(rows: List<OcrPageEntity>) {
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    override suspend fun all(): List<OcrPageEntity> = dao.loadAll()

    override suspend fun forBook(bookKey: String): List<OcrPageEntity> = dao.loadForBook(bookKey)

    override suspend fun prefilter(needle: String): List<OcrPageEntity> = dao.prefilter(needle)

    override suspend fun deleteForBook(bookKey: String) = dao.deleteForBook(bookKey)

    override suspend fun size(): Int = dao.count()
}

/** Deterministic in-memory index: JVM tests, previews, "do not persist" mode. */
class InMemoryOcrIndexStore : OcrIndexStore {
    private val rows = LinkedHashMap<String, OcrPageEntity>()

    override suspend fun upsertAll(rows: List<OcrPageEntity>) {
        rows.forEach { this.rows[it.key] = it }
    }

    override suspend fun all(): List<OcrPageEntity> =
        rows.values.sortedWith(compareBy({ it.bookKey }, { it.pageIndex }))

    override suspend fun forBook(bookKey: String): List<OcrPageEntity> =
        all().filter { it.bookKey == bookKey }

    override suspend fun prefilter(needle: String): List<OcrPageEntity> =
        all().filter { needle.isEmpty() || it.normalizedText.contains(needle) }

    override suspend fun deleteForBook(bookKey: String) {
        rows.entries.removeIf { it.value.bookKey == bookKey }
    }

    override suspend fun size(): Int = rows.size
}

sealed interface OcrIndexReceipt {
    /** Stored (or refreshed) an indexed page. */
    data class Indexed(
        val key: String,
        val characters: Int,
        val tokens: Int,
        val durationMillis: Long,
    ) : OcrIndexReceipt

    /** The page produced no text — nothing is stored, the reader can skip it. */
    data class EmptyPage(val pageIndex: Int) : OcrIndexReceipt

    /** The on-demand model could not be installed. */
    data class ModelUnavailable(val script: OcrScript, val reason: String) : OcrIndexReceipt

    data class Failed(val reason: String) : OcrIndexReceipt
}

/** One search result, ready to be shown as "page N of <book>" with context. */
data class OcrHit(
    val bookKey: String,
    val pageIndex: Int,
    val script: OcrScript,
    val score: Double,
    val snippet: String,
    val matchedTokens: Int,
)

/**
 * Scanned-page OCR indexing + search.
 *
 * `engine` and `downloader` are seams: the JVM tests drive the loop with a fake
 * engine and [InMemoryOnDemandDownloader]; the app passes
 * platform/MlKitOcrProvider.kt and platform/MlKitModelDownloader.kt.
 */
class OcrSearchRepository(
    private val engine: OcrEngine,
    private val store: OcrIndexStore = InMemoryOcrIndexStore(),
    private val downloader: OnDemandDownloader? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Recognises one scanned page and indexes the result. Never throws: the
     * reader loop calls this per page and must be able to continue with the
     * next one (a failed page only loses its own text).
     */
    suspend fun indexPage(request: OcrRequest, image: OcrImage): OcrIndexReceipt {
        downloader?.let { downloader ->
            val pack = OcrModelCatalog.packFor(request.script)
            if (downloader.state(pack) != DownloadState.Installed) {
                when (val result = downloader.ensureInstalled(pack)) {
                    is DownloadResult.Failed ->
                        return OcrIndexReceipt.ModelUnavailable(request.script, result.reason)
                    DownloadResult.Unsupported ->
                        return OcrIndexReceipt.ModelUnavailable(request.script, "downloader unsupported")
                    else -> Unit
                }
            }
        }

        val result = try {
            engine.recognize(request, image)
        } catch (failure: Exception) {
            return OcrIndexReceipt.Failed(failure.message ?: failure::class.java.simpleName)
        }

        val rawText = result.rawText
        val normalized = OcrTextNormalizer.normalize(rawText)
        if (normalized.isBlank()) return OcrIndexReceipt.EmptyPage(request.pageIndex)

        val tokens = OcrTextNormalizer.tokens(normalized)
        val entity = OcrPageEntity(
            key = pageKey(request.bookKey, request.pageIndex, request.script),
            bookKey = request.bookKey,
            pageIndex = request.pageIndex,
            script = request.script.name,
            text = rawText,
            normalizedText = normalized,
            tokenCount = tokens.size,
            recognizedAt = nowMillis(),
            durationMillis = result.durationMillis,
        )
        store.upsertAll(listOf(entity))
        return OcrIndexReceipt.Indexed(
            key = entity.key,
            characters = normalized.length,
            tokens = tokens.size,
            durationMillis = result.durationMillis,
        )
    }

    /**
     * Ranked search over the indexed pages.
     *
     * Scoring (deterministic, no fuzzy matching):
     *   * fraction of the query tokens present in the page — weight 0.75;
     *   * +0.25 when the whole normalised query appears verbatim.
     * Hits below [minScore] are dropped, which keeps a 5-token query from
     * returning pages that merely repeat one common word.
     */
    suspend fun search(
        query: String,
        bookKey: String? = null,
        limit: Int = 20,
        minScore: Double = DEFAULT_MIN_SCORE,
        snippetRadius: Int = DEFAULT_SNIPPET_RADIUS,
    ): List<OcrHit> {
        val normalizedQuery = OcrTextNormalizer.normalize(query)
        val queryTokens = OcrTextNormalizer.tokens(normalizedQuery).distinct()
        if (queryTokens.isEmpty()) return emptyList()

        val candidates = if (bookKey != null) {
            store.forBook(bookKey)
        } else {
            store.prefilter(queryTokens.maxByOrNull { it.length } ?: normalizedQuery)
        }

        return candidates.mapNotNull { row ->
            val score = score(row, normalizedQuery, queryTokens)
            if (score < minScore) return@mapNotNull null
            OcrHit(
                bookKey = row.bookKey,
                pageIndex = row.pageIndex,
                script = runCatching { OcrScript.valueOf(row.script) }.getOrDefault(OcrScript.LATIN),
                score = score,
                snippet = snippet(row.normalizedText, normalizedQuery, queryTokens, snippetRadius),
                matchedTokens = queryTokens.count { row.normalizedText.contains(it, ignoreCase = true) },
            )
        }
            .sortedWith(
                compareByDescending<OcrHit> { it.score }
                    .thenBy { it.bookKey }
                    .thenBy { it.pageIndex },
            )
            .take(limit.coerceAtLeast(1))
    }

    /** Number of indexed pages of a book (progress of the scan-to-index pass). */
    suspend fun indexedPages(bookKey: String): Int = store.forBook(bookKey).size

    suspend fun size(): Int = store.size()

    /** Drops the OCR index of a book (called when the book is deleted). */
    suspend fun forgetBook(bookKey: String) = store.deleteForBook(bookKey)

    private fun score(row: OcrPageEntity, normalizedQuery: String, queryTokens: List<String>): Double {
        val matched = queryTokens.count { row.normalizedText.contains(it, ignoreCase = true) }
        val fraction = matched.toDouble() / queryTokens.size
        val verbatim = if (normalizedQuery.isNotEmpty() &&
            row.normalizedText.contains(normalizedQuery, ignoreCase = true)
        ) {
            1.0
        } else {
            0.0
        }
        return (fraction * 0.75) + (verbatim * 0.25)
    }

    private fun snippet(
        text: String,
        normalizedQuery: String,
        queryTokens: List<String>,
        radius: Int,
    ): String {
        val index = when {
            normalizedQuery.isNotEmpty() -> text.indexOf(normalizedQuery, ignoreCase = true)
            else -> -1
        }.takeIf { it >= 0 }
            ?: queryTokens.asSequence()
                .map { text.indexOf(it, ignoreCase = true) }
                .filter { it >= 0 }
                .minOrNull()
            ?: 0
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + normalizedQuery.length.coerceAtMost(text.length) + radius)
            .coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    companion object {
        const val DEFAULT_MIN_SCORE: Double = 0.34
        const val DEFAULT_SNIPPET_RADIUS: Int = 40

        fun pageKey(bookKey: String, pageIndex: Int, script: OcrScript): String =
            "$bookKey#$pageIndex#${script.name}"
    }
}
