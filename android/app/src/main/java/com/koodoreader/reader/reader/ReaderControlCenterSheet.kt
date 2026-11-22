package com.koodoreader.reader.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koodoreader.core.designsystem.SpaceTokens
import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.core.ui.theme.KoodoTheme
import com.koodoreader.reader.shell.ReaderChromeUiState
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.ShellAppearancePrefs
import com.koodoreader.reader.shell.darkThemeOf
import com.koodoreader.reader.shell.t

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun ReaderControlCenterSheet(
    format: ReaderFormatKind,
    useFoliate: Boolean,
    initialTab: ReaderControlCenterTab? = null,
    readerThemeKind: ThemeKind,
    onTheme: (ThemeKind) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    state: ReaderFeatureState,
    onFeatureChange: (ReaderFeatureState) -> Unit,
    chromeState: ReaderChromeUiState,
    onChromeState: (ReaderChromeUiState) -> Unit,
    marginDp: Int,
    onMarginDp: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val appearance = remember { ShellAppearancePrefs(context) }
    val appMode by appearance.appThemeModeFlow.collectAsStateWithLifecycle()
    val appDark = appMode.darkThemeOf(isSystemInDarkTheme())
    val tabs = remember(format) { ReaderControlCenterTabs.forFormat(format) }
    var selectedTab by remember(format, initialTab) {
        mutableStateOf(ReaderControlCenterTabs.defaultTab(format, initialTab))
    }
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.78f
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    KoodoTheme(darkTheme = appDark) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            scrimColor = Color.Black.copy(alpha = 0.55f),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = SpaceTokens.READER_HORIZONTAL_DEFAULT.dp)
                    .padding(bottom = SpaceTokens.md.dp),
            ) {
                Text(t("Reading options"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(SpaceTokens.sm.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SpaceTokens.sm.dp),
                ) {
                    tabs.forEach { tab ->
                        FilterChip(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            label = { Text(t(tab.titleKey())) },
                        )
                    }
                }
                Spacer(Modifier.height(SpaceTokens.md.dp))
                when (selectedTab) {
                    ReaderControlCenterTab.APPEARANCE -> AppearanceSection(
                        format = format,
                        useFoliate = useFoliate,
                        readerThemeKind = readerThemeKind,
                        onTheme = onTheme,
                        fontScale = fontScale,
                        onFontScale = onFontScale,
                        lineHeight = ReaderFeatureCodec.clampedLineHeight(state),
                        onLineHeight = { onFeatureChange(state.copy(lineHeightRatio = it)) },
                        letterSpacingSp = ReaderFeatureCodec.clampedLetterSpacing(state),
                        onLetterSpacing = {
                            onFeatureChange(state.copy(letterSpacingSp = it))
                        },
                        marginDp = marginDp,
                        onMarginDp = onMarginDp,
                        pdfReflow = state.pdfReflow,
                    )
                    ReaderControlCenterTab.LAYOUT -> LayoutSection(
                        format = format,
                        useFoliate = useFoliate,
                        state = state,
                        onChange = onFeatureChange,
                    )
                    ReaderControlCenterTab.PDF -> PdfDisplaySection(
                        state = state,
                        onChange = onFeatureChange,
                    )
                    ReaderControlCenterTab.ADVANCED -> AdvancedSection(
                        state = state,
                        onChange = onFeatureChange,
                        chromeState = chromeState,
                        onChromeState = onChromeState,
                    )
                }
            }
        }
    }
}
