package com.koodoreader.feature.translate

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [SecretStore] on top of **EncryptedSharedPreferences**.
 *
 * File-level encryption: preference *keys* use AES-256-SIV, *values* use
 * AES-256-GCM, and the master key lives in the Android Keystore (StrongBox
 * where available). Requires `androidx.security:security-crypto` and `minSdk 24`.
 *
 * Nothing in this class logs. [CredentialsStore] wraps the caller's [Logger] and
 * is the only sanctioned logging path for credentials.
 */
class EncryptedSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        FILE_NAME,
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    companion object {
        const val FILE_NAME = "koodo_translate_credentials"

        /** Production wiring: encrypted store + a redacting logger. */
        fun credentialsStore(context: Context, logger: Logger): CredentialsStore =
            CredentialsStore(EncryptedSecretStore(context), logger)
    }
}
