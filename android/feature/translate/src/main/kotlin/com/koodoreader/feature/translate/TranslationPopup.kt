package com.koodoreader.feature.translate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PopupStatus {
    IDLE,
    TRANSLATING,
    DONE,
    FAILED,

    /** Selected source has no API key yet — the UI links to the settings screen. */
    NEEDS_CREDENTIALS,
}

/** Selection geometry in view coordinates, used to place the popup. */
data class SelectionAnchor(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val selectionFromBottomToTop: Boolean = false,
) {
    companion object {
        val NONE = SelectionAnchor(0f, 0f, 0f, 0f)
    }
}

/** One entry of the popup's source switcher (P6 acceptance #1: 多源切换). */
data class SourceChip(
    val id: TranslationSourceId,
    val displayName: String,
    val configured: Boolean,
    val active: Boolean,
    val credentialHint: String,
)

data class TranslationPopupState(
    val visible: Boolean = false,
    val selection: String = "",
    val sourceLanguage: String = LanguageCodes.AUTO,
    val targetLanguage: String = GoogleTranslateProvider.DEFAULT_TARGET,
    val activeSource: TranslationSourceId = TranslationSourceId.GOOGLE,
    val status: PopupStatus = PopupStatus.IDLE,
    val translatedText: String? = null,
    val failureDetail: String? = null,
    val pinned: Boolean = false,
    val recordedInHistory: Boolean = false,
    val anchor: SelectionAnchor = SelectionAnchor.NONE,
) {
    val hasResult: Boolean get() = !translatedText.isNullOrEmpty()

    /** What the popup body shows: the translation once available, else the selection. */
    fun displayText(): String = translatedText?.takeIf { it.isNotEmpty() } ?: selection
}

/**
 * State machine behind the selection-translation popup.
 *
 * Deliberately free of Android/Compose types: the Compose surface
 * (`TranslationPopupUi.kt`) only renders [state] and forwards intents, and the
 * whole flow — show, switch source, translate, fail, dismiss — is unit-tested on
 * the JVM. Nothing here logs credentials; provider calls go through
 * [CredentialsStore] + [RedactedLogger].
 */
class TranslationPopupController(
    private val selector: ProviderSelector,
    private val credentialsStore: CredentialsStore,
    private val transport: HttpTransport,
    private val logger: Logger = NoopLogger,
    private val history: TranslationHistoryRepository? = null,
    private val defaultTargetLanguage: String = GoogleTranslateProvider.DEFAULT_TARGET,
    private val defaultSourceLanguage: String = LanguageCodes.AUTO,
) {

    private val _state = MutableStateFlow(
        TranslationPopupState(
            targetLanguage = defaultTargetLanguage,
            sourceLanguage = defaultSourceLanguage,
            activeSource = selector.active,
        ),
    )

    val state: StateFlow<TranslationPopupState> = _state.asStateFlow()

    private var pendingBookKey: String? = null
    private var pendingCfi: String? = null

    /** Called by the reader when a new selection is made. */
    fun show(
        selection: String,
        targetLanguage: String = defaultTargetLanguage,
        bookKey: String? = null,
        cfi: String? = null,
        anchor: SelectionAnchor = SelectionAnchor.NONE,
    ): TranslationPopupState {
        pendingBookKey = bookKey
        pendingCfi = cfi
        val resolved = selector.resolve(credentialsStore.configuredSources())
        selector.select(resolved)
        _state.value = _state.value.copy(
            visible = selection.isNotBlank(),
            selection = selection,
            targetLanguage = targetLanguage,
            activeSource = resolved,
            status = PopupStatus.IDLE,
            translatedText = null,
            failureDetail = null,
            recordedInHistory = false,
            anchor = anchor,
        )
        return _state.value
    }

    fun dismiss(): TranslationPopupState {
        _state.value = _state.value.copy(visible = false, status = PopupStatus.IDLE)
        return _state.value
    }

    fun setPinned(pinned: Boolean): TranslationPopupState {
        _state.value = _state.value.copy(pinned = pinned)
        return _state.value
    }

    fun sources(): List<SourceChip> {
        val configured = credentialsStore.configuredSources()
        return selector.providers.map { provider ->
            SourceChip(
                id = provider.id,
                displayName = provider.id.displayName,
                configured = configured.contains(provider.id),
                active = provider.id == selector.active,
                credentialHint = provider.credentialFields.firstOrNull { it.secret }?.hint.orEmpty(),
            )
        }
    }

    /** Source switcher; keeps the current selection so the caller can re-translate. */
    fun switchProvider(id: TranslationSourceId): Boolean {
        if (!selector.select(id)) {
            logger.warn("translation source ${id.pluginKey} is not registered")
            return false
        }
        _state.value = _state.value.copy(activeSource = id, status = PopupStatus.IDLE, failureDetail = null)
        logger.info("translation source switched: ${id.pluginKey}")
        return true
    }

    fun cycleProvider(): TranslationSourceId {
        val next = selector.next()
        _state.value = _state.value.copy(activeSource = next, status = PopupStatus.IDLE, failureDetail = null)
        return next
    }

    /** Switch + translate in one step (popup chip tap). */
    suspend fun switchProviderAndTranslate(id: TranslationSourceId): TranslationPopupState {
        switchProvider(id)
        return translate()
    }

    /** Runs the translation for [TranslationPopupState.selection]. */
    suspend fun translate(): TranslationPopupState {
        val current = _state.value
        if (current.selection.isBlank()) {
            return current
        }
        val sourceId = selector.resolve(credentialsStore.configuredSources())
        selector.select(sourceId)
        val credentials = credentialsStore.require(sourceId)
        if (!credentials.hasApiKey()) {
            _state.value = current.copy(
                activeSource = sourceId,
                status = PopupStatus.NEEDS_CREDENTIALS,
                failureDetail = "Add an API key for ${sourceId.displayName} to translate.",
                translatedText = null,
            )
            return _state.value
        }
        _state.value = current.copy(activeSource = sourceId, status = PopupStatus.TRANSLATING, failureDetail = null)
        val outcome = selector.provider(sourceId).translate(
            text = current.selection,
            from = current.sourceLanguage,
            to = current.targetLanguage,
            credentials = credentials,
            transport = transport,
        )
        return when (outcome) {
            is TranslationOutcome.Success -> {
                val recorded = record(outcome, current)
                _state.value = _state.value.copy(
                    status = PopupStatus.DONE,
                    translatedText = outcome.text,
                    failureDetail = null,
                    recordedInHistory = recorded,
                )
                _state.value
            }

            is TranslationOutcome.Failure -> {
                _state.value = _state.value.copy(
                    status = PopupStatus.FAILED,
                    translatedText = null,
                    failureDetail = outcome.detail,
                )
                logger.warn("translation failed: source=${sourceId.pluginKey} reason=${outcome.reason}")
                _state.value
            }
        }
    }

    /** Text handed to the clipboard / share sheet. */
    fun copyPayload(): String? {
        val current = _state.value
        if (!current.hasResult) {
            return null
        }
        return buildString {
            append(current.selection)
            append('\n')
            append(current.translatedText)
            append("\n— ")
            append(current.activeSource.displayName)
        }
    }

    private suspend fun record(outcome: TranslationOutcome.Success, current: TranslationPopupState): Boolean {
        val repository = history ?: return false
        repository.record(
            sourceText = current.selection,
            translatedText = outcome.text,
            targetLang = current.targetLanguage,
            provider = outcome.provider,
            sourceLang = outcome.detectedSourceLanguage ?: current.sourceLanguage,
            bookKey = pendingBookKey,
            cfi = pendingCfi,
        )
        return true
    }
}
