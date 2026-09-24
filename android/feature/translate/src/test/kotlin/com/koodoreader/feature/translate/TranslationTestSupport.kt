package com.koodoreader.feature.translate

/**
 * Shared test doubles. Kept in the test source set so the production sources stay
 * dependency-free (no test library leaks into the app).
 */
class RecordingLogger : Logger {

    val entries = mutableListOf<String>()

    val infos: List<String> get() = entries.filter { it.startsWith("INFO ") }
    val warnings: List<String> get() = entries.filter { it.startsWith("WARN ") }
    val errors: List<String> get() = entries.filter { it.startsWith("ERROR ") }

    fun text(): String = entries.joinToString("\n")

    override fun info(message: String) {
        entries += "INFO $message"
    }

    override fun warn(message: String) {
        entries += "WARN $message"
    }

    override fun error(message: String, throwable: Throwable?) {
        entries += "ERROR $message"
    }
}

class FakeHttpTransport(
    private val responder: (HttpRequest) -> HttpResponse,
) : HttpTransport {

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responder(request)
    }

    companion object {
        fun returning(body: String, status: Int = 200): FakeHttpTransport =
            FakeHttpTransport { HttpResponse(status, body) }

        fun failing(message: String = "connection reset"): FakeHttpTransport =
            FakeHttpTransport { throw HttpTransportException(message) }
    }
}

/** In-memory [TranslationHistoryDao] with the same semantics as the SQL queries. */
class FakeTranslationHistoryDao : TranslationHistoryDao {

    private val rows = LinkedHashMap<String, TranslationHistoryEntity>()

    var upsertCount: Int = 0
        private set

    override suspend fun upsert(entry: TranslationHistoryEntity) {
        upsertCount++
        rows[entry.key] = entry
    }

    override suspend fun upsertAll(entries: List<TranslationHistoryEntity>) {
        entries.forEach { upsert(it) }
    }

    override suspend fun recent(limit: Int): List<TranslationHistoryEntity> =
        rows.values.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun search(query: String, limit: Int): List<TranslationHistoryEntity> =
        rows.values
            .filter {
                it.sourceText.contains(query, ignoreCase = true) ||
                    it.translatedText.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override suspend fun findByKey(key: String): TranslationHistoryEntity? = rows[key]

    override suspend fun forBook(bookKey: String, limit: Int): List<TranslationHistoryEntity> =
        rows.values.filter { it.bookKey == bookKey }.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun beyondNewest(keep: Int): List<TranslationHistoryEntity> =
        rows.values.sortedByDescending { it.createdAt }.drop(keep)

    override suspend fun deleteByKey(key: String) {
        rows.remove(key)
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun count(): Long = rows.size.toLong()
}

/** Monotonic fake clock so ordering assertions are deterministic. */
class FakeClock(private var now: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long {
        now += 1_000
        return now
    }
}

object TestTokens {
    /** Shape-compatible with the `sk-` rule and long enough for value matching. */
    const val GOOGLE: String = "AIzaSyD-EXAMPLE-9f3c1a77b2e4d5f608a1"
    const val DEEPL: String = "deepl-key-3f9c1a77b2e4d5f608a1c2d3"
    const val MICROSOFT: String = "ms-sub-7c1f9a2b3d4e5f60718293a4b5"
}
