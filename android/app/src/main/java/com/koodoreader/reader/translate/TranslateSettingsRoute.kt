package com.koodoreader.reader.translate

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.koodoreader.feature.translate.ApiPlan
import com.koodoreader.feature.translate.CredentialField
import com.koodoreader.feature.translate.CredentialsStore
import com.koodoreader.feature.translate.DeepLTranslateProvider
import com.koodoreader.feature.translate.EncryptedSecretStore
import com.koodoreader.feature.translate.GoogleTranslateProvider
import com.koodoreader.feature.translate.MicrosoftTranslateProvider
import com.koodoreader.feature.translate.ProviderCredentials
import com.koodoreader.feature.translate.ProviderSelector
import com.koodoreader.feature.translate.TranslationProvider
import com.koodoreader.feature.translate.TranslationSourceId
import com.koodoreader.reader.shell.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Translation & AI (settings page 2026-09-29 spec §3.5).
 *
 * The missing half of the selection-translation popup: a place to actually
 * store the API keys. The form is DATA-DRIVEN — each provider declares its own
 * `credentialFields` (key/label/required/secret/hint) and this screen renders
 * them; the security contract (EncryptedSharedPreferences at rest, redacted
 * logs) lives in [CredentialsStore].
 *
 * Key semantics: the secret field starts EMPTY with a masked placeholder
 * (`***wxyz`); blank = "keep the stored key". Non-secret fields are
 * pre-filled with the stored values so they can be edited in place.
 */

/** Pure form state for one provider card — no Compose in here, JVM-testable. */
class CredentialForm(
    val provider: TranslationProvider,
    private val existing: ProviderCredentials,
) {
    /** Secret field: blank = do not change the stored key. */
    var apiKeyInput: String = ""

    /** Non-secret fields start pre-filled with the stored values. */
    var endpointInput: String = existing.endpoint
    var regionInput: String = existing.region
    var keyTypeInput: String = existing.plan.wireValue

    /** True when every required field is satisfiable (entered or already stored). */
    val isValid: Boolean
        get() = provider.credentialFields.all { field ->
            !field.required || valueFor(field).isNotBlank()
        }

    /** The effective value for [field], honouring blank-means-keep for the key. */
    fun valueFor(field: CredentialField): String = when (field.key) {
        "apiKey" -> if (apiKeyInput.isBlank()) existing.apiKey else apiKeyInput
        "endpoint" -> endpointInput
        "region" -> regionInput
        "keyType" -> keyTypeInput
        else -> ""
    }

    fun toCredentials(): ProviderCredentials = ProviderCredentials(
        apiKey = if (apiKeyInput.isBlank()) existing.apiKey else apiKeyInput.trim(),
        endpoint = endpointInput.trim(),
        region = regionInput.trim(),
        plan = ApiPlan.fromWire(keyTypeInput.ifBlank { existing.plan.wireValue }),
    )

    /** Back to the untouched state (used after "clear"). */
    fun resetTo(defaultPlan: ApiPlan) {
        apiKeyInput = ""
        endpointInput = ""
        regionInput = ""
        keyTypeInput = defaultPlan.wireValue
    }
}

/**
 * The provider list — one owner of the set, shared with
 * [com.koodoreader.reader.translate.rememberTranslationPopupController].
 */
val TRANSLATION_PROVIDERS: List<TranslationProvider> = listOf(
    GoogleTranslateProvider(),
    MicrosoftTranslateProvider(),
    DeepLTranslateProvider(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateSettingsRoute(onBack: () -> Unit) {
    // `LocalContext.current` 是 @Composable 调用——必须在 remember 回调之外求值，
    // 再捕获进 lambda（remember 回调内不得调用 composable）。
    val context = LocalContext.current
    val store: CredentialsStore = remember {
        EncryptedSecretStore.credentialsStore(context.applicationContext, AppLogLogger())
    }
    // `selector` is only used to keep the provider registry in one place with
    // the popup; the form below enumerates TRANSLATION_PROVIDERS directly.
    @Suppress("UNUSED_VARIABLE")
    val selector: ProviderSelector = remember { ProviderSelector(TRANSLATION_PROVIDERS) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Translation")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "阅读器双击选词 →「翻译」使用这里配置的服务；密钥加密存储，不随备份导出",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TRANSLATION_PROVIDERS.forEach { provider ->
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                ProviderCredentialCard(
                    provider = provider,
                    store = store,
                    scope = scope,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderCredentialCard(
    provider: TranslationProvider,
    store: CredentialsStore,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val id = provider.id
    val existing = remember { store.load(id) ?: ProviderCredentials() }
    val form = remember(provider) { CredentialForm(provider, existing) }
    var savedFlash by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(text = id.displayName, style = MaterialTheme.typography.titleMedium)
            if (store.isConfigured(id)) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (savedFlash) {
                Text(
                    text = t("Saved"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        provider.credentialFields.forEach { field ->
            Spacer(Modifier.height(8.dp))
            ProviderField(field = field, form = form, maskedKey = store.maskedApiKey(id))
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = {
                    store.save(id, form.toCredentials())
                    scope.launch {
                        savedFlash = true
                        delay(1500)
                        savedFlash = false
                    }
                },
                enabled = form.isValid,
            ) { Text(t("Save")) }
            if (store.isConfigured(id)) {
                Spacer(Modifier.width(8.dp))
                // `t` 是 @Composable——文案在此处求值，onClick 内只使用结果
                // （点击回调不是 composable 上下文）。
                val clearedText = t("Cleared")
                TextButton(onClick = {
                    store.clear(id)
                    form.resetTo(ApiPlan.FREE)
                    Toast.makeText(context, clearedText, Toast.LENGTH_SHORT).show()
                }) { Text(t("Clear")) }
            }
            if (!form.isValid) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = t("API key required"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProviderField(
    field: CredentialField,
    form: CredentialForm,
    maskedKey: String,
) {
    OutlinedTextField(
        value = form.valueFor(field),
        onValueChange = { newValue ->
            when (field.key) {
                "apiKey" -> form.apiKeyInput = newValue
                "endpoint" -> form.endpointInput = newValue
                "region" -> form.regionInput = newValue
                "keyType" -> form.keyTypeInput = newValue
            }
        },
        label = { Text(field.label) },
        placeholder = {
            Text(if (field.secret && maskedKey.isNotEmpty()) maskedKey else field.hint)
        },
        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
