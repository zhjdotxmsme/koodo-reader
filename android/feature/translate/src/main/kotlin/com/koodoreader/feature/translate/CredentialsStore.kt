package com.koodoreader.feature.translate

/**
 * Key/value seam in front of the encrypted storage.
 *
 * Production: [EncryptedSecretStore] (androidx.security EncryptedSharedPreferences,
 * AES-256-SIV keys / AES-256-GCM values, master key in the Android Keystore).
 * Unit tests and previews: [InMemorySecretStore].
 */
interface SecretStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

class InMemorySecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    private val values = LinkedHashMap<String, String>(initial)

    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun keys(): Set<String> = values.keys.toSet()
}

/**
 * Translation-source credentials (API keys / endpoints) for the three built-in
 * sources.
 *
 * Security contract — enforced by construction, not by convention:
 *
 *  * values are persisted through [SecretStore] only (encrypted at rest);
 *  * **every** log line this class emits goes through [logger], a
 *    [RedactedLogger] whose secret set is *the store itself*. A token that was
 *    saved (or loaded) can therefore never appear verbatim in an info-level
 *    line, even when a caller interpolates it into the message
 *    (CLAUDE.md: 不要将令牌、密码…记录到 info 级别日志 / P6 acceptance #2);
 *  * UI-facing summaries use [maskedApiKey] (`***wxyz`), never the raw value.
 *
 * Keys are namespaced `p6.translate.cred.<pluginKey>.<field>` so the desktop
 * plugin ids stay the migration key (`google-translate-plugin`, …).
 */
class CredentialsStore(
    private val secretStore: SecretStore,
    rawLogger: Logger = NoopLogger,
) {

    private val knownTokensCache = LinkedHashSet<String>()
    private var scanned = false

    /**
     * Log through this object, never through a raw logger: it redacts every
     * token the store has seen.
     */
    val logger: Logger = RedactedLogger(rawLogger, secretSource = { knownTokens() })

    fun save(id: TranslationSourceId, credentials: ProviderCredentials) {
        secretStore.putString(fieldKey(id, FIELD_API_KEY), credentials.apiKey)
        secretStore.putString(fieldKey(id, FIELD_ENDPOINT), credentials.endpoint)
        secretStore.putString(fieldKey(id, FIELD_REGION), credentials.region)
        secretStore.putString(fieldKey(id, FIELD_PLAN), credentials.plan.wireValue)
        registerSecret(credentials.apiKey)
        logger.info(
            "translation credentials saved: source=${id.pluginKey} " +
                "apiKey=${TokenRedaction.mask(credentials.apiKey)} " +
                "endpoint=${credentials.endpoint.ifBlank { "<default>" }} region=${credentials.region}",
        )
    }

    fun load(id: TranslationSourceId): ProviderCredentials? {
        val apiKey = secretStore.getString(fieldKey(id, FIELD_API_KEY))
        val endpoint = secretStore.getString(fieldKey(id, FIELD_ENDPOINT))
        val region = secretStore.getString(fieldKey(id, FIELD_REGION))
        val plan = secretStore.getString(fieldKey(id, FIELD_PLAN))
        if (apiKey == null && endpoint == null && region == null && plan == null) {
            return null
        }
        registerSecret(apiKey)
        return ProviderCredentials(
            apiKey = apiKey.orEmpty(),
            endpoint = endpoint.orEmpty(),
            region = region.orEmpty(),
            plan = ApiPlan.fromWire(plan),
        )
    }

    fun require(id: TranslationSourceId): ProviderCredentials = load(id) ?: ProviderCredentials()

    fun clear(id: TranslationSourceId) {
        load(id)?.apiKey?.let { knownTokensCache.remove(it) }
        listOf(FIELD_API_KEY, FIELD_ENDPOINT, FIELD_REGION, FIELD_PLAN).forEach { field ->
            secretStore.remove(fieldKey(id, field))
        }
        logger.info("translation credentials cleared: source=${id.pluginKey}")
    }

    /** Sources with a non-blank API key — drives the popup's source chips. */
    fun configuredSources(): Set<TranslationSourceId> =
        TranslationSourceId.entries.filter { !load(it)?.apiKey.isNullOrBlank() }.toSet()

    /** Secrets handed to the redactor. Never logged, never rendered. */
    fun knownTokens(): Set<String> {
        if (!scanned) {
            scanned = true
            secretStore.keys()
                .filter { it.startsWith(KEY_PREFIX) && it.endsWith(".$FIELD_API_KEY") }
                .mapNotNullTo(knownTokensCache) { secretStore.getString(it)?.takeIf { value -> value.isNotBlank() } }
        }
        return knownTokensCache.toSet()
    }

    /** `***wxyz` for settings screens; empty string when nothing is stored. */
    fun maskedApiKey(id: TranslationSourceId): String = TokenRedaction.mask(load(id)?.apiKey)

    fun isConfigured(id: TranslationSourceId): Boolean = !load(id)?.apiKey.isNullOrBlank()

    /** Log/UI-safe one-liner (never contains a token). */
    fun describe(id: TranslationSourceId): String {
        val credentials = load(id) ?: return "${id.pluginKey}: not configured"
        return "${id.pluginKey}: apiKey=${TokenRedaction.mask(credentials.apiKey)} " +
            "endpoint=${credentials.endpoint.ifBlank { "<default>" }} plan=${credentials.plan.wireValue} " +
            "region=${credentials.region.ifBlank { "<none>" }}"
    }

    private fun registerSecret(secret: String?) {
        if (!secret.isNullOrBlank() && secret.length >= TokenRedaction.MIN_SECRET_LENGTH) {
            knownTokensCache.add(secret)
            scanned = true
        }
    }

    companion object {
        const val KEY_PREFIX = "p6.translate.cred."
        const val FIELD_API_KEY = "apiKey"
        const val FIELD_ENDPOINT = "endpoint"
        const val FIELD_REGION = "region"
        const val FIELD_PLAN = "plan"

        fun fieldKey(id: TranslationSourceId, field: String): String = "$KEY_PREFIX${id.pluginKey}.$field"
    }
}
