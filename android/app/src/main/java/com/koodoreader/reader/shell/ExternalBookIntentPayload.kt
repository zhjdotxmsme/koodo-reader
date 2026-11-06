package com.koodoreader.reader.shell

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Parsed VIEW/SEND book intent (content/file URI + optional MIME). */
data class ExternalBookIntentPayload(
    val uri: Uri,
    val mimeType: String?,
) {
    companion object {
        fun from(intent: Intent): ExternalBookIntentPayload? {
            if (intent.action == Intent.ACTION_SEND) {
                val stream = getParcelableUri(intent, Intent.EXTRA_STREAM) ?: return null
                return ExternalBookIntentPayload(stream, intent.type)
            }
            val data = intent.data ?: return null
            return when (data.scheme?.lowercase()) {
                "content", "file" -> ExternalBookIntentPayload(data, intent.type)
                else -> null
            }
        }

        private fun getParcelableUri(intent: Intent, key: String): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key) as? Uri
            }
    }
}
