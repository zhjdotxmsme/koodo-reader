package com.koodoreader.feature.translate

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [SecretStore] on top of **EncryptedSharedPreferences**.
 *
 * File-level encryption: preference *keys* use AES-256-SIV, *values* use
 * AES-256-GCM, and the master key lives in the Android Keystore (StrongBox
 * where available). Requires `androidx.security:security-crypto` and `minSdk 24`.
 *
 * Nothing in this class logs a secret. [CredentialsStore] wraps the caller's
 * [Logger] and is the only sanctioned logging path for credentials.
 *
 * ## Why the store is opened defensively
 *
 * The master key lives in the Android Keystore, and the encrypted file is
 * useless without it. Three ordinary events make the two disagree:
 *
 *  - the app is reinstalled under a different signature (the Keystore entry is
 *    keyed by the app's signing identity, so the new build gets a new key);
 *  - the user changes their lock-screen credential, which invalidates the
 *    Keystore entry;
 *  - the device is restored from a backup onto different hardware.
 *
 * In each case `EncryptedSharedPreferences.create` throws
 * [javax.crypto.AEADBadTagException] ("Signature/MAC verification failed") —
 * and it throws it from the *constructor*, before any credential is ever
 * asked for.
 *
 * That must not take the app down: a translation API key is an optional
 * convenience, and losing it costs the reader one re-typed key. So the store
 * degrades to an in-memory one instead, and the unreadable file is deleted so
 * the next launch starts clean rather than failing again.
 */
/**
 * Adapts a plain [android.content.SharedPreferences] to the [SecretStore]
 * seam.
 *
 * `EncryptedSharedPreferences` IS a `SharedPreferences`, but it does not
 * implement [SecretStore] — so the encrypted instance is wrapped here, and the
 * same wrapper gives the degraded path a way to keep using whatever the
 * reader had typed.
 */
private class SharedPreferencesSecretStore(
    private val prefs: android.content.SharedPreferences,
) : SecretStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

class EncryptedSecretStore private constructor(
    private val delegate: SecretStore,
) : SecretStore {

    override fun getString(key: String): String? = delegate.getString(key)

    override fun putString(key: String, value: String) = delegate.putString(key, value)

    override fun remove(key: String) = delegate.remove(key)

    override fun keys(): Set<String> = delegate.keys()

    companion object {
        const val FILE_NAME = "koodo_translate_credentials"

        /**
         * Where the credentials go while the encrypted store is unreadable.
         *
         * A different file from [FILE_NAME] on purpose — see the catch block.
         */
        const val FALLBACK_FILE_NAME = "koodo_translate_credentials_plain"

        private const val TAG = "EncryptedSecretStore"

        /**
         * Opens the encrypted store, or returns an in-memory one when the
         * encrypted file cannot be read.
         *
         * The failure is logged with the exception type only — never with the
         * file's contents.
         */
        fun open(context: Context): SecretStore {
            val appContext = context.applicationContext
            return try {
                EncryptedSecretStore(
                    SharedPreferencesSecretStore(
                        EncryptedSharedPreferences.create(
                            appContext,
                            FILE_NAME,
                            MasterKey.Builder(appContext)
                                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                .build(),
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                        ),
                    ),
                )
            } catch (failure: Exception) {
                // The file is unreadable (a different signing identity, an
                // invalidated Keystore key, a restored backup).
                //
                // The encrypted file is deleted, and the fallback writes to a
                // DIFFERENT file: the master key still does not match, so
                // writing back into the encrypted file would fail again on the
                // next launch and every start would pay the cost of the
                // exception. The fallback file holds the credentials the
                // reader re-types in this session; they are readable by the
                // app alone, like the app's own preferences.
                Log.w(TAG, "encrypted credential store unreadable (${failure.javaClass.simpleName}); " +
                    "continuing without stored translation credentials")
                runCatching {
                    appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                }
                EncryptedSecretStore(
                    SharedPreferencesSecretStore(
                        appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE),
                    ),
                )
            }
        }

        /** Production wiring: encrypted store + a redacting logger. */
        fun credentialsStore(context: Context, logger: Logger): CredentialsStore =
            CredentialsStore(open(context), logger)
    }
}
