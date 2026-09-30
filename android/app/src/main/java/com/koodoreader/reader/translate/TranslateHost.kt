package com.koodoreader.reader.translate

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.koodoreader.feature.translate.CredentialsStore
import com.koodoreader.feature.translate.DeepLTranslateProvider
import com.koodoreader.feature.translate.EncryptedSecretStore
import com.koodoreader.feature.translate.GoogleTranslateProvider
import com.koodoreader.feature.translate.HttpRequest
import com.koodoreader.feature.translate.HttpResponse
import com.koodoreader.feature.translate.HttpTransport
import com.koodoreader.feature.translate.HttpTransportException
import com.koodoreader.feature.translate.Logger
import com.koodoreader.feature.translate.MicrosoftTranslateProvider
import com.koodoreader.feature.translate.ProviderSelector
import com.koodoreader.feature.translate.TranslationHistoryDatabase
import com.koodoreader.feature.translate.TranslationHistoryRepository
import com.koodoreader.feature.translate.TranslationPopupController
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production bindings for `:feature:translate` (docs/p6-translate-architecture.md
 * §6: ":app 提供两个 seam 实现：HttpTransport 与 Logger").
 *
 * The feature module ships the popup state machine, the providers and the
 * credential store; this file is the only place that touches the network
 * stack and `android.util.Log`.
 */

/** [Logger] over android.util.Log, one tag for the whole feature. */
class AppLogLogger(private val tag: String = "KoodoTranslate") : Logger {
    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun warn(message: String) {
        Log.w(tag, message)
    }

    override fun error(message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

/**
 * [HttpTransport] on HttpURLConnection — no new dependency (OkHttp is not on
 * the app's classpath). All IO runs on [Dispatchers.IO]; every failure is
 * rethrown as [HttpTransportException] with a token-free message (the request
 * URL can carry `?key=…`, so only method + host are reported).
 */
class AppHttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val host = runCatching { URL(request.url).host }.getOrDefault("<unknown>")
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = request.method
                for ((name, value) in request.headers) {
                    setRequestProperty(name, value)
                }
                val bodyText = request.body
                if (bodyText != null) {
                    doOutput = true
                    outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
                }
            }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                HttpResponse(status, body)
            } catch (e: Exception) {
                if (e is HttpTransportException) throw e
                throw HttpTransportException("${request.method} $host: ${e.javaClass.simpleName}", e)
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * Assembles the selection-translation popup controller with the production
 * seams. Remembered per composition (reader screen), so the encrypted store
 * and the history Room database survive recomposition but are rebuilt when
 * the reader is re-entered.
 *
 * History recording is wired (bookKey/cfi come from the reader's selection);
 * the credentials live in EncryptedSharedPreferences and are redacted from
 * every log line through [RedactedLogger].
 */
@Composable
fun rememberTranslationPopupController(context: Context): TranslationPopupController {
    val appContext = context.applicationContext
    return remember(appContext) {
        val credentials: CredentialsStore = EncryptedSecretStore.credentialsStore(appContext, AppLogLogger())
        // credentials.logger is a RedactedLogger whose secret set is the store
        // itself — every log path must go through it, never through a raw logger.
        val logger = credentials.logger
        TranslationPopupController(
            selector = ProviderSelector(
                listOf(GoogleTranslateProvider(), MicrosoftTranslateProvider(), DeepLTranslateProvider()),
            ),
            credentialsStore = credentials,
            transport = AppHttpTransport(),
            logger = logger,
            history = TranslationHistoryRepository(
                TranslationHistoryDatabase.get(appContext).historyDao(),
                logger = logger,
            ),
        )
    }
}
