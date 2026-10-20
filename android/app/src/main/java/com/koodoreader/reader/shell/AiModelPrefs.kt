package com.koodoreader.reader.shell

import android.content.Context
import com.koodoreader.feature.translate.AiModelConfig
import com.koodoreader.feature.translate.EncryptedSecretStore

/**
 * OpenAI-compatible assistance model (desktop `aiAssistanceModel`).
 * Secrets live in the same encrypted store as translation keys.
 */
class AiModelPrefs(context: Context) {

    private val store = EncryptedSecretStore(context.applicationContext)

    fun load(): AiModelConfig = AiModelConfig(
        endpoint = store.getString(KEY_ENDPOINT).orEmpty(),
        providerId = store.getString(KEY_PROVIDER).orEmpty(),
        apiKey = store.getString(KEY_API).orEmpty(),
        modelId = store.getString(KEY_MODEL).orEmpty(),
    )

    fun save(config: AiModelConfig) {
        store.putString(KEY_ENDPOINT, config.normalizedEndpoint())
        store.putString(KEY_PROVIDER, config.providerId.trim())
        store.putString(KEY_API, config.apiKey.trim())
        store.putString(KEY_MODEL, config.modelId.trim())
    }

    private companion object {
        const val KEY_ENDPOINT = "ai.endpoint"
        const val KEY_PROVIDER = "ai.provider"
        const val KEY_API = "ai.apiKey"
        const val KEY_MODEL = "ai.modelId"
    }
}
