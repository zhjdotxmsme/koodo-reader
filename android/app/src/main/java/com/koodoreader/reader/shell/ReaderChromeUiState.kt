package com.koodoreader.reader.shell

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.koodoreader.core.designsystem.SpaceTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReaderChromeUiState(
    val fullScreen: Boolean = false,
    val firstLineIndent: Boolean = false,
    val horizontalMarginDp: Int = SpaceTokens.READER_HORIZONTAL_DEFAULT,
)

interface ReaderChromePreferenceStore {
    fun load(): ReaderChromeUiState
    fun save(state: ReaderChromeUiState)
}

class ReaderChromeViewModel(private val store: ReaderChromePreferenceStore) : ViewModel() {
    private val mutableState = MutableStateFlow(store.load().sanitized())
    val state: StateFlow<ReaderChromeUiState> = mutableState.asStateFlow()

    fun setFullScreen(value: Boolean) = update { copy(fullScreen = value) }
    fun setFirstLineIndent(value: Boolean) = update { copy(firstLineIndent = value) }
    fun setHorizontalMarginDp(value: Int) = update { copy(horizontalMarginDp = value) }
    fun update(state: ReaderChromeUiState) = update { state }

    private fun update(transform: ReaderChromeUiState.() -> ReaderChromeUiState) {
        val next = mutableState.value.transform().sanitized()
        mutableState.value = next
        store.save(next)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val store = SharedPreferencesReaderChromePreferenceStore(appContext)
            return modelClass.getConstructor(ReaderChromePreferenceStore::class.java)
                .newInstance(store)
        }
    }
}

private class SharedPreferencesReaderChromePreferenceStore(context: Context) : ReaderChromePreferenceStore {
    private val prefs = context.getSharedPreferences("reader_chrome_ui", Context.MODE_PRIVATE)
    override fun load() = ReaderChromeUiState(
        fullScreen = prefs.getBoolean("full_screen", false),
        firstLineIndent = prefs.getBoolean("first_line_indent", false),
        horizontalMarginDp = prefs.getInt("horizontal_margin_dp", SpaceTokens.READER_HORIZONTAL_DEFAULT),
    ).sanitized()
    override fun save(state: ReaderChromeUiState) {
        prefs.edit()
            .putBoolean("full_screen", state.fullScreen)
            .putBoolean("first_line_indent", state.firstLineIndent)
            .putInt("horizontal_margin_dp", state.horizontalMarginDp)
            .apply()
    }
}

private fun ReaderChromeUiState.sanitized() = copy(
    horizontalMarginDp = horizontalMarginDp.takeIf { it in SpaceTokens.READER_HORIZONTAL_OPTIONS }
        ?: SpaceTokens.READER_HORIZONTAL_DEFAULT,
)
