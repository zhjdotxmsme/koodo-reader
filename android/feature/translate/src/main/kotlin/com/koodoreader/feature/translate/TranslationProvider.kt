package com.koodoreader.feature.translate

/**
 * The three translation sources that ship built into the app (P6).
 *
 * Desktop ships 25 translation plugins in
 * `src/utils/plugins/renderer/translation/` plus a renderer plugin registry.
 * Per the P6 card only the three core sources are promoted to native features;
 * the remaining plugin sources stay desktop-only (no plugin registry, no
 * JavaScript bridge). [pluginKey] keeps the desktop plugin id so settings can be
 * migrated 1:1.
 */
enum class TranslationSourceId(val pluginKey: String, val displayName: String) {
    GOOGLE("google-translate-plugin", "Google Translate"),
    MICROSOFT("azure-translate-plugin", "Microsoft Translator"),
    DEEPL("deepl-translate-plugin", "DeepL"),
    ;

    companion object {
        fun fromPluginKey(key: String): TranslationSourceId? =
            entries.firstOrNull { it.pluginKey.equals(key, ignoreCase = true) }
    }
}

/** DeepL splits its API by plan; desktop stores this as `keyType: "free" | ""`. */
enum class ApiPlan(val wireValue: String) {
    FREE("free"),
    PRO("pro"),
    ;

    companion object {
        fun fromWire(value: String?): ApiPlan = if (value.equals(FREE.wireValue, ignoreCase = true)) FREE else PRO
    }
}

/**
 * Credentials + endpoint for one source. Mirrors the desktop plugin `config`
 * object field-for-field (`apiKey`, `endpoint`, `location`, `keyType`).
 */
data class ProviderCredentials(
    val apiKey: String = "",
    val endpoint: String = "",
    val region: String = "",
    val plan: ApiPlan = ApiPlan.FREE,
) {
    fun hasApiKey(): Boolean = apiKey.isNotBlank()
}

/** Field descriptor used to render the settings form and to validate input. */
data class CredentialField(
    val key: String,
    val label: String,
    val required: Boolean,
    val secret: Boolean,
    val hint: String = "",
)

enum class FailureReason {
    EMPTY_INPUT,
    MISSING_CREDENTIALS,

    /** No provider configured at all (AI assistant path). */
    NOT_CONFIGURED,
    NETWORK,
    HTTP_STATUS,
    MALFORMED_RESPONSE,
}

sealed interface TranslationOutcome {
    data class Success(
        val text: String,
        val provider: TranslationSourceId,
        val detectedSourceLanguage: String? = null,
    ) : TranslationOutcome

    data class Failure(
        val provider: TranslationSourceId,
        val reason: FailureReason,
        val detail: String,
    ) : TranslationOutcome
}

/**
 * One translation source.
 *
 * The contract is split so that everything except the network hop is a pure
 * function: [buildRequest] and [parseResponse] are unit-testable on the JVM,
 * and [translate] only glues them together through an injected [HttpTransport].
 */
interface TranslationProvider {

    val id: TranslationSourceId
    val credentialFields: List<CredentialField>
    val defaultEndpoint: String

    fun buildRequest(
        text: String,
        from: String,
        to: String,
        credentials: ProviderCredentials,
    ): HttpRequest

    fun parseResponse(response: HttpResponse): TranslationOutcome

    suspend fun translate(
        text: String,
        from: String,
        to: String,
        credentials: ProviderCredentials,
        transport: HttpTransport,
    ): TranslationOutcome {
        if (text.isBlank()) {
            return TranslationOutcome.Failure(id, FailureReason.EMPTY_INPUT, "nothing selected")
        }
        if (!credentials.hasApiKey()) {
            return TranslationOutcome.Failure(
                id,
                FailureReason.MISSING_CREDENTIALS,
                "missing API key for ${id.displayName}",
            )
        }
        if (LanguageCodes.sameLanguage(from, to)) {
            // Desktop Google plugin short-circuits identical languages; doing it
            // for every source saves a round trip and a quota unit.
            return TranslationOutcome.Success(text, id, from)
        }
        return try {
            parseResponse(transport.execute(buildRequest(text, from, to, credentials)))
        } catch (e: HttpTransportException) {
            TranslationOutcome.Failure(id, FailureReason.NETWORK, e.message ?: "network error")
        }
    }
}

/** Desktop language codes; `Automatic`/`auto` mean "let the provider decide". */
object LanguageCodes {
    const val AUTO = "auto"
    private const val DESKTOP_AUTO = "Automatic"

    fun isAuto(code: String?): Boolean =
        code.isNullOrBlank() || code.equals(AUTO, ignoreCase = true) || code.equals(DESKTOP_AUTO, ignoreCase = true)

    fun sameLanguage(from: String?, to: String?): Boolean =
        !isAuto(from) && !isAuto(to) && from.equals(to, ignoreCase = true)

    /** DeepL (and some Microsoft endpoints) want upper-case language tags. */
    fun upperTag(code: String): String = code.trim().uppercase()

    /**
     * DeepL tag mapping: `zh-CN` -> `ZH`, `zh-TW`/`zh-Hant` -> `ZH-HANT`,
     * `en-us` -> `EN-US`, `en` -> `EN`.
     */
    fun deeplTag(code: String): String {
        val normalized = code.trim().replace('_', '-')
        val primary = normalized.substringBefore('-').lowercase()
        val subtag = normalized.substringAfter('-', "").lowercase()
        return when (primary) {
            "zh" -> if (subtag.startsWith("tw") || subtag.startsWith("hk") || subtag.startsWith("hant")) {
                "ZH-HANT"
            } else {
                "ZH"
            }
            else -> if (subtag.isEmpty()) primary.uppercase() else "${primary.uppercase()}-${subtag.uppercase()}"
        }
    }
}

internal object UrlEncoding {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val ch = byte.toInt().toChar()
            if (UNRESERVED.indexOf(ch) >= 0) {
                out.append(ch)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    /** `query("api-version" to "3.0", "to" to "zh-CN")` -> `?api-version=3.0&to=zh-CN`. */
    fun query(vararg params: Pair<String, String?>): String {
        val encoded = params
            .filter { it.second != null && it.second!!.isNotEmpty() }
            .joinToString("&") { "${encode(it.first)}=${encode(it.second!!)}" }
        return if (encoded.isEmpty()) "" else "?$encoded"
    }
}

/**
 * Active source + switching order for the selection-translation popup.
 *
 * Selection rules (desktop parity): the configured source wins when it is
 * usable, otherwise fall back to the first usable source, otherwise keep the
 * configured one so the UI can show "add an API key".
 */
class ProviderSelector(
    val providers: List<TranslationProvider>,
    initialActive: TranslationSourceId? = null,
) {
    init {
        require(providers.isNotEmpty()) { "at least one translation provider is required" }
    }

    var active: TranslationSourceId = initialActive?.takeIf { id -> providers.any { it.id == id } }
        ?: providers.first().id
        private set

    fun activeProvider(): TranslationProvider = provider(active)

    fun provider(id: TranslationSourceId): TranslationProvider =
        providers.firstOrNull { it.id == id } ?: error("provider ${id.pluginKey} is not registered")

    fun select(id: TranslationSourceId): Boolean {
        if (providers.none { it.id == id }) {
            return false
        }
        active = id
        return true
    }

    fun isRegistered(id: TranslationSourceId): Boolean = providers.any { it.id == id }

    /** Cycles in registration order (used by the popup's "next source" chip). */
    fun next(): TranslationSourceId {
        val index = providers.indexOfFirst { it.id == active }
        active = providers[(index + 1) % providers.size].id
        return active
    }

    /** Providers whose credentials are present, registration order preserved. */
    fun usable(configured: Set<TranslationSourceId>): List<TranslationProvider> =
        providers.filter { configured.contains(it.id) }

    /** Resolves the source to use for the next translation. */
    fun resolve(configured: Set<TranslationSourceId>): TranslationSourceId =
        when {
            configured.contains(active) -> active
            usable(configured).isNotEmpty() -> usable(configured).first().id
            else -> active
        }
}
