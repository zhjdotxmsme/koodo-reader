package com.koodoreader.feature.tts

import com.koodoreader.engine.text.TextSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reading-position "断点续播" (resume where you stopped listening) — **pure JVM**
 * (DoD [4]).
 *
 * Desktop counterpart: the reader keeps a Redux string `speechStartText`
 * (`src/store/reducers/reader.tsx`), set by the popup's "read from here" action
 * (`handleSpeechStartText`) and consumed by the TTS container:
 *
 * ```ts
 * getSpeechStartIndex = (nodeTextList: string[]) => {
 *   const speechStartText = this.props.speechStartText;
 *   if (!speechStartText) return -1;
 *   return nodeTextList.findIndex((item) =>
 *     item.includes(speechStartText) || speechStartText.includes(item));
 * };
 * ```
 *
 * That is a *text anchor*, not a numeric offset — deliberately resilient to the
 * text being re-segmented differently (which is exactly what happens when the
 * Android side re-splits the chapter after a font/layout change). Android keeps
 * the same fuzzy match ([TtsResumeMatcher]) and adds what the desktop lacks:
 *
 *  - the anchor is **persisted** (DataStore, per book) instead of living in Redux
 *    memory, so it survives process death;
 *  - it carries `:engine:toc`'s [ReadingPosition] (spine index, CFI, percents), so
 *    the reader can also restore the *page*, not just the sentence;
 *  - a numeric [TtsResumeAnchor.sentenceIndex] acts as a secondary fallback when
 *    the text no longer matches at all.
 *
 * Linkage with the reading engines:
 *  - text in  → [TtsSentenceSource] / [TextSourceSentenceSource] (over
 *    `:engine:text`'s [TextSource], whose `TextDecoder` output is passed as a
 *    `(ByteArray) -> String` so this module never depends on a decoder API shape);
 *  - position → [ReadingPosition] from `:engine:toc` (the same model the P2
 *    progress card persists in Room).
 */

/**
 * Synchronous sink the session controller calls whenever a resume point should be
 * recorded (pause, stop, sentence advance, chapter end).
 *
 * Implemented by the service with [BookmarkResumeController.sink], which launches
 * the suspend write on the service scope — keeping [TtsSessionController] free of
 * coroutines so its tests stay synchronous.
 */
fun interface TtsResumeSink {
    fun onResumePoint(bookKey: String, utteranceIndex: Int, anchorText: String, updatedAt: Long)
}

/**
 * Where listening stopped.
 *
 * @property bookKey stable book key (same key as [ReadingPosition.bookKey]).
 * @property spineIndex chapter index, 0-based.
 * @property cfi chapter-local CFI, or `""` when the reader could not supply one.
 * @property chapterPercent / @property totalPercent reader progress, 0.0–1.0.
 * @property sentenceIndex 0-based index in the chapter's sentence queue.
 * @property anchorText the sentence text (desktop `speechStartText`).
 * @property updatedAt wall-clock timestamp; "continue listening" picks the newest.
 */
data class TtsResumeAnchor(
    val bookKey: String,
    val spineIndex: Int,
    val cfi: String,
    val chapterPercent: Float,
    val totalPercent: Float,
    val sentenceIndex: Int,
    val anchorText: String,
    val updatedAt: Long = 0L,
) {
    init {
        require(bookKey.isNotBlank()) { "bookKey must not be blank" }
        require(spineIndex >= 0) { "spineIndex must be >= 0 (was $spineIndex)" }
        require(sentenceIndex >= 0) { "sentenceIndex must be >= 0 (was $sentenceIndex)" }
        require(chapterPercent in 0f..1f) { "chapterPercent must be in [0,1] (was $chapterPercent)" }
        require(totalPercent in 0f..1f) { "totalPercent must be in [0,1] (was $totalPercent)" }
    }

    /** The position this anchor belongs to ([TtsReadingPosition] ≈ `:engine:toc`'s `ReadingPosition`). */
    fun toReadingPosition(): TtsReadingPosition = TtsReadingPosition(
        bookKey = bookKey,
        spineIndex = spineIndex,
        cfi = cfi,
        chapterPercent = chapterPercent,
        totalPercent = totalPercent,
    )

    /** Strips the anchor to what DataStore needs (kept for symmetry with the codec). */
    fun withUpdatedAt(timestamp: Long): TtsResumeAnchor = copy(updatedAt = timestamp)

    companion object {

        /** Builds an anchor from the reader's position + the sentence being spoken. */
        fun of(
            position: TtsReadingPosition,
            sentenceIndex: Int,
            anchorText: String,
            updatedAt: Long,
        ): TtsResumeAnchor = TtsResumeAnchor(
            bookKey = position.bookKey,
            spineIndex = position.spineIndex,
            cfi = position.cfi,
            chapterPercent = position.chapterPercent,
            totalPercent = position.totalPercent,
            sentenceIndex = sentenceIndex.coerceAtLeast(0),
            anchorText = anchorText,
            updatedAt = updatedAt,
        )

        /**
         * Desktop-only import path: the desktop has no CFI/position for TTS, just
         * the Redux `speechStartText` and the current chapter index.
         */
        fun fromDesktop(
            bookKey: String,
            spineIndex: Int,
            speechStartText: String,
            sentenceIndex: Int = 0,
            updatedAt: Long = 0L,
        ): TtsResumeAnchor = TtsResumeAnchor(
            bookKey = bookKey,
            spineIndex = spineIndex.coerceAtLeast(0),
            cfi = "",
            chapterPercent = 0f,
            totalPercent = 0f,
            sentenceIndex = sentenceIndex.coerceAtLeast(0),
            anchorText = speechStartText,
            updatedAt = updatedAt,
        )
    }
}

/** Outcome of a resume attempt. */
data class TtsResumePoint(
    /** The stored anchor, or `null` when the book was never listened to. */
    val anchor: TtsResumeAnchor?,
    /** Sentence to start from; `0` when nothing (usable) was stored. */
    val sentenceIndex: Int,
    /** True when [sentenceIndex] came from the text anchor rather than the numeric fallback. */
    val matchedByText: Boolean,
) {
    /** True when the caller should actually resume instead of starting at the top. */
    val resumes: Boolean get() = anchor != null && sentenceIndex > 0
}

/**
 * Desktop-parity resume matching.
 *
 * The match is intentionally fuzzy in both directions
 * (`item.includes(anchor) || anchor.includes(item)`), because the anchor may be a
 * user *selection* that is longer or shorter than one sentence — the desktop
 * behaviour the Android port must reproduce.
 */
object TtsResumeMatcher {

    /**
     * @return the index of the first sentence matching [anchorText], or `-1` when
     *   nothing matches.
     */
    fun findSentenceIndex(sentences: List<String>, anchorText: String): Int {
        val anchor = anchorText.trim()
        if (anchor.isEmpty()) return -1
        return sentences.indexOfFirst { sentence ->
            val candidate = sentence.trim()
            candidate.isNotEmpty() && (candidate.contains(anchor) || anchor.contains(candidate))
        }
    }

    /**
     * Resolves the sentence to start from.
     *
     * Order:
     *  1. text anchor (desktop behaviour);
     *  2. stored [TtsResumeAnchor.sentenceIndex] when it is still inside the queue —
     *     an Android addition, so a chapter whose text changed slightly (or was
     *     re-segmented) still resumes roughly in place;
     *  3. `0` — the desktop's "no anchor found → start at the beginning" fallback.
     */
    fun resolve(sentences: List<String>, anchor: TtsResumeAnchor?): TtsResumePoint {
        if (anchor == null) return TtsResumePoint(null, 0, matchedByText = false)
        val textIndex = findSentenceIndex(sentences, anchor.anchorText)
        if (textIndex >= 0) return TtsResumePoint(anchor, textIndex, matchedByText = true)
        if (anchor.sentenceIndex in sentences.indices) {
            return TtsResumePoint(anchor, anchor.sentenceIndex, matchedByText = false)
        }
        return TtsResumePoint(anchor, 0, matchedByText = false)
    }
}

/**
 * Bridge from the chapter text to the sentence queue.
 *
 * Implemented over `:engine:text`'s [TextSource]; a test can implement it with a
 * plain list.
 */
interface TtsSentenceSource {

    /** Chapter text, decoded. */
    fun chapterText(): String

    /** Sentence queue with offsets (what [TtsSessionController.load] consumes). */
    fun utterances(): List<TtsUtterance>

    /** Just the sentence strings (what [TtsResumeMatcher] works on). */
    fun sentences(): List<String> = utterances().map { it.text }
}

/**
 * [TtsSentenceSource] over `:engine:text`.
 *
 * @param source the chapter's byte source — the reader passes the window
 *   `ChapterSplitter` produced for the current chapter (not the whole book).
 * @param maxChunkLength [TtsConfig.chunkLength]; `0` = derive from the language.
 * @param decode `:engine:text`'s decoder (`TextDecoder::decode`) or a charset-aware
 *   lambda; kept as a function so this module does not pin the decoder signature.
 *   Last parameter on purpose, so the call site reads
 *   `TextSourceSentenceSource(source) { bytes -> … }`.
 */
class TextSourceSentenceSource(
    private val source: TextSource,
    private val maxChunkLength: Int = 0,
    private val decode: (ByteArray) -> String,
) : TtsSentenceSource {

    private val chunkLength: Int? get() = maxChunkLength.takeIf { it > 0 }

    override fun chapterText(): String = try {
        decode(source.readAll())
    } catch (e: IllegalStateException) {
        // Source larger than Int.MAX_VALUE cannot be materialised; the reader is
        // expected to hand us a chapter-sized window. Read a bounded prefix rather
        // than failing the whole session.
        decode(source.read(0L, MAX_FALLBACK_BYTES))
    }

    override fun utterances(): List<TtsUtterance> = TtsSentenceSplitter.splitWithOffsets(chapterText(), chunkLength)

    companion object {
        /** 2 MiB prefix used when a source refuses `readAll()`. */
        const val MAX_FALLBACK_BYTES = 2 * 1024 * 1024
    }
}

/** Persistence port for [TtsResumeAnchor] (DataStore on Android, in-memory in tests). */
interface TtsResumeStore {

    /** The anchor stored for [bookKey], or `null`. */
    suspend fun load(bookKey: String): TtsResumeAnchor?

    /** Upserts [anchor]. */
    suspend fun save(anchor: TtsResumeAnchor)

    /** Forgets [bookKey] (book deleted, or the user finished it). */
    suspend fun clear(bookKey: String)

    /** Book key of the most recently updated anchor — the "continue listening" entry point. */
    suspend fun lastBookKey(): String? = null
}

/** In-memory [TtsResumeStore] — unit tests and previews. */
class InMemoryTtsResumeStore : TtsResumeStore {

    private val anchors = LinkedHashMap<String, TtsResumeAnchor>()

    override suspend fun load(bookKey: String): TtsResumeAnchor? = anchors[bookKey]

    override suspend fun save(anchor: TtsResumeAnchor) {
        anchors[anchor.bookKey] = anchor
    }

    override suspend fun clear(bookKey: String) {
        anchors.remove(bookKey)
    }

    override suspend fun lastBookKey(): String? =
        anchors.values.maxByOrNull { it.updatedAt }?.bookKey

    /** Number of stored anchors (test convenience). */
    val size: Int get() = anchors.size
}

/**
 * Records and restores listening positions.
 *
 * The class is the *only* place that knows how an anchor is shaped, which keeps the
 * service thin and — more importantly — lets the resume behaviour be unit-tested
 * against an [InMemoryTtsResumeStore] on a plain JVM.
 */
class BookmarkResumeController(
    private val store: TtsResumeStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Persists a resume point.
     *
     * @param position the reader's current position, or `null` when the reader has
     *   not reported one (the anchor then keeps spine 0 / percents 0, like the
     *   desktop `fromDesktop` import path).
     * @param updatedAt the session's timestamp, when the caller already has one
     *   ([TtsResumeSink] does); `null` → the controller's clock.
     */
    suspend fun record(
        bookKey: String,
        utteranceIndex: Int,
        anchorText: String,
        position: TtsReadingPosition? = null,
        updatedAt: Long? = null,
    ): TtsResumeAnchor {
        val timestamp = updatedAt ?: clock()
        val anchor = if (position != null) {
            TtsResumeAnchor.of(position, utteranceIndex, anchorText, timestamp)
        } else {
            TtsResumeAnchor.fromDesktop(bookKey, 0, anchorText, utteranceIndex, timestamp)
        }
        store.save(anchor)
        return anchor
    }

    /** Resolves where to start speaking in [sentences]. */
    suspend fun restore(bookKey: String, sentences: List<String>): TtsResumePoint =
        TtsResumeMatcher.resolve(sentences, store.load(bookKey))

    /** Same as [restore], straight from a `:engine:text`-backed source. */
    suspend fun restore(bookKey: String, source: TtsSentenceSource): TtsResumePoint =
        restore(bookKey, source.sentences())

    /** Forgets the book's anchor. */
    suspend fun clear(bookKey: String) {
        store.clear(bookKey)
    }

    /** Book key of the most recent session, for the "continue listening" card. */
    suspend fun lastBookKey(): String? = store.lastBookKey()

    /**
     * Adapter for [TtsSessionController]: turns its synchronous callbacks into
     * suspend writes on [scope].
     *
     * @param positionProvider called at record time, so the anchor always carries
     *   the position the reader is showing *now* (desktop reads
     *   `rendition.getPosition()` at the same moment).
     */
    fun sink(scope: CoroutineScope, positionProvider: () -> TtsReadingPosition? = { null }): TtsResumeSink =
        TtsResumeSink { bookKey, utteranceIndex, anchorText, updatedAt ->
            scope.launch {
                record(
                    bookKey = bookKey,
                    utteranceIndex = utteranceIndex,
                    anchorText = anchorText,
                    position = positionProvider(),
                    updatedAt = updatedAt,
                )
            }
        }
}
