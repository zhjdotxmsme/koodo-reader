# Android Immersive Reading and Spacing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the native Android shelf and EPUB/PDF reading flow with stable edge-to-edge immersion, shared reader chrome, and complete design tokens without changing book parsing, progress, annotation, search, or sync behavior.

**Architecture:** Keep token values in `:core:designsystem` and Compose bindings/components in `:core:ui`. Add an app-level `ReaderChromeViewModel` for UI-only preferences, a single system-bar/safe-area controller, and shared top/bottom reader chrome; EPUB and PDF retain their engines and callbacks. Use stable ignoring-visibility insets so chrome toggles never resize the book viewport.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, ViewModel + StateFlow, SharedPreferences for UI preferences, WindowCompat/WindowInsetsControllerCompat, JUnit 4/5, Gradle, ADB.

**Design spec:** `.openteams/specs/2026-11-12-android-immersive-spacing-design.html`

## Global Constraints

- Only UI/UX, UI preferences, and rendering parameters may change. Do not modify Book/Chapter/Highlight/Note/Bookmark/ReadingProgress/SearchIndex/ReadingSession schemas.
- Do not change parsers, CFI/page mapping, annotation coordinates, search indexes, import, sync, or file-management behavior.
- Base spacing is exactly `xs=4dp, sm=8dp, md=16dp, lg=24dp, xl=32dp`; semantic 12dp/20dp values are explicit tokens, not ad-hoc literals.
- Reader font defaults to 17sp with a 14–22sp range and 1.6 line-height. Original PDF raster layout is not re-typeset.
- Full-screen OFF: system bars follow chrome. Full-screen ON: system bars stay hidden even for chrome, TOC, and settings; edge swipe can still reveal transient bars.
- Reader viewport padding is stable: `statusBarsIgnoringVisibility + 8dp` top and `navigationBarsIgnoringVisibility + 16dp` bottom. Never apply chrome-dependent Scaffold padding to book content.
- Toolbar animation is 200ms fade + slide; page transition is 250ms; easing is FastOutSlowIn or LinearOutSlowIn.
- No new libraries or Compose androidTest scaffolding. Use existing JVM tests, compilation, APK build, and final ADB verification.
- Work directly on `dev`; do not reset or commit unrelated untracked `.dsh-acl-recovery/`, `.rea/`, `.reverse/`, or `.tools/` content.
- Commit only files listed by the completed task. Run all Gradle commands from `E:/open-source/koodo-reader/android`.

---

## File Map

- `core/designsystem`: pure spacing, shape, typography, motion, size, and elevation values.
- `core/ui/theme`: Compose ColorScheme, Typography, Shapes, Motion, and shell scheme CompositionLocal.
- `core/ui/component`: book-card spacing and reusable press/stagger presentation modifiers.
- `reader/shell/ReaderChromeUiState.kt`: UI preference model, storage interface, StateFlow ViewModel, and Android SharedPreferences adapter.
- `reader/shell/ReaderSystemBarPolicy.kt`: pure hide/show and inset arithmetic, plus Compose system-bar bridge.
- `reader/shell/ReaderChrome.kt`: shared top bar, 2dp bottom progress, and reader color semantics.
- `reader/shell/ReaderPresentation.kt`: reader/custom ColorScheme resolution shared by EPUB and PDF.
- `reader/reader/ReaderSettingsSheet.kt`: full-screen, first-line indent, four margin choices, and scroll-safe settings layout.
- `reader/epubhost/*` and `reader/shell/NativePdfScreen.kt`: UI-only engine host integration.
- `reader/shell/LibraryScreen.kt`, `core/ui/component/KoodoBookCard.kt`: shelf rhythm and first-presentation motion.
- `reader/reader/ReaderTocPage.kt`, `BookAnnotationsScreen.kt`, and existing search rows: list/card rhythm.

---

### Task 1: Lock Design Tokens and Reader Typography Defaults

**Files:**
- Modify: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/SpaceTokens.kt`
- Create: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/SizeTokens.kt`
- Create: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/ElevationTokens.kt`
- Modify: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/TypographyTokens.kt:15-24`
- Modify: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/MotionTokens.kt`
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme/KoodoSpacing.kt`
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme/KoodoShapes.kt`
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme/KoodoTypography.kt`
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme/KoodoMotion.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt`
- Test: `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/SpaceTokensTest.kt`
- Test: `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/ShapeTokensTest.kt`
- Test: `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/TypographyTokensTest.kt`
- Test: `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/DesignSystemSelfCheck.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt`

**Interfaces:**
- Produces: `SpaceTokens`, `SizeTokens.PROGRESS_STROKE`, `ElevationTokens.READER_CHROME`, `MotionTokens.PAGE/STAGGER/PRESS_SCALE`.
- Produces: `ReaderFeatureCodec.effectiveParagraphSpacingPx(state)` and `ReaderFeatureCodec.userStylesheet(state, firstLineIndent=false)`.
- Later tasks use `SpaceTokens.READER_HORIZONTAL_OPTIONS`, `KoodoMotion.Decelerate`, and `KoodoCardShape`.

- [ ] **Step 1: Replace token assertions with the new contract**

Use these assertions in `SpaceTokensTest` and remove the old `xxl`/`LIST_GAP` assertions:

```kotlin
@Test
fun `base scale matches the third round contract`() {
    assertEquals(listOf(4, 8, 16, 24, 32), SpaceTokens.ALL)
    assertEquals(16, SpaceTokens.md)
    assertEquals(24, SpaceTokens.lg)
    assertEquals(32, SpaceTokens.xl)
}

@Test
fun `semantic values cover shelf reader and lists`() {
    assertEquals(12, SpaceTokens.GRID_GAP_HORIZONTAL)
    assertEquals(16, SpaceTokens.GRID_GAP_VERTICAL)
    assertEquals(12, SpaceTokens.LIST_ITEM_VERTICAL)
    assertEquals(20, SpaceTokens.READER_HORIZONTAL_DEFAULT)
    assertEquals(listOf(12, 16, 20, 24), SpaceTokens.READER_HORIZONTAL_OPTIONS)
    assertEquals(56, SpaceTokens.TOP_BAR_HEIGHT)
}
```

Change the line-height assertion in `TypographyTokensTest` and `DesignSystemSelfCheck` to `1.6f`. In `ShapeTokensTest`, retain the Material five-step scale and assert `ShapeTokens.medium == 12`; card mapping is `KoodoCardShape = KoodoShapes.medium`, not `large`.

Replace the font-scale test in `ReaderFeatureStateTest` with:

```kotlin
@Test
fun `reader font size is clamped to 14 through 22 px`() {
    val tiny = ReaderFeatureState(fontScale = 0.01f)
    val huge = ReaderFeatureState(fontScale = 9f)
    assertEquals(14f, ReaderFeatureCodec.fontSizePx(tiny), 0.001f)
    assertEquals(22f, ReaderFeatureCodec.fontSizePx(huge), 0.001f)
}

@Test
fun `default paragraph gap is 1 point 2 times current line height`() {
    val state = ReaderFeatureState()
    assertEquals(17f * 1.6f * 1.2f, ReaderFeatureCodec.effectiveParagraphSpacingPx(state), 0.001f)
    assertEquals("p{margin-bottom:32.64px;}", ReaderFeatureCodec.userStylesheet(state))
}

@Test
fun `first line indent is presentation css only`() {
    val state = ReaderFeatureState(paragraphSpacingPx = 12f)
    assertEquals(
        "p{margin-bottom:12px;text-indent:2em;}",
        ReaderFeatureCodec.userStylesheet(state, firstLineIndent = true),
    )
}
```

Update old empty-stylesheet assertions to expect the default paragraph rule. Explicit 12px/8px rules stay unchanged. Custom CSS is now appended after the automatic paragraph rule, including in `ReadestViewSettingsTest`:

```kotlin
assertEquals("p{margin-bottom:32.64px;}\n$css", on.userStylesheet)
assertEquals("p{margin-bottom:32.64px;}", off.userStylesheet)
```

Apply the same prefix to the custom-CSS assertion in the enhancement round-trip test; do not remove the custom CSS itself.

- [ ] **Step 2: Prove the new tests fail before implementation**

Run:

```bash
./gradlew :core:designsystem:test :app:testNativeHostDebugUnitTest --tests '*ReaderFeatureStateTest*'
```

Expected: FAIL on `SpaceTokens.ALL`, `lineHeightRatio`, 14–22 clamping, `effectiveParagraphSpacingPx`, and `firstLineIndent` being absent.

- [ ] **Step 3: Implement pure tokens and size/elevation values**

Replace `SpaceTokens` with:

```kotlin
package com.koodoreader.core.designsystem

object SpaceTokens {
    const val xs = 4
    const val sm = 8
    const val md = 16
    const val lg = 24
    const val xl = 32
    val ALL = listOf(xs, sm, md, lg, xl)

    const val SCREEN_HORIZONTAL = md
    const val LIST_ITEM_HORIZONTAL = md
    const val LIST_ITEM_VERTICAL = 12
    const val GRID_GAP_HORIZONTAL = 12
    const val GRID_GAP_VERTICAL = md
    const val COVER_TO_TEXT = sm
    const val TEXT_LINE_GAP = xs
    const val GROUP_TITLE_GAP = sm
    const val GROUP_GAP = lg
    const val READER_HORIZONTAL_DEFAULT = 20
    const val READER_TOP_EXTRA = sm
    const val READER_BOTTOM_EXTRA = md
    const val TOP_BAR_HEIGHT = 56
    val READER_HORIZONTAL_OPTIONS = listOf(12, 16, 20, 24)
}
```

Create `SizeTokens.kt` and `ElevationTokens.kt`:

```kotlin
package com.koodoreader.core.designsystem

object SizeTokens {
    const val PROGRESS_STROKE = 2
    const val HAIRLINE = 1
    const val MIN_TOUCH_TARGET = 48
}
```

```kotlin
package com.koodoreader.core.designsystem

object ElevationTokens {
    const val READER_CHROME = 1
}
```

Add `PAGE=250`, `STAGGER=30`, and `PRESS_SCALE=0.98f` to `MotionTokens`; include duration values `FAST/NORMAL/PAGE/SLOW/SHIMMER/HINT` in `ALL`, but not `STAGGER` or `PRESS_SCALE`.

- [ ] **Step 4: Implement Compose bindings and reader typography policy**

Remove `xxl` from `KoodoSpacing`; expose `listItemVertical`, `coverToText`, `textLineGap`, `readerHorizontalDefault`, and the existing grid fields from `SpaceTokens`. Set `KoodoCardShape = KoodoShapes.medium`; keep `ShapeTokens.large=16` for Material containers and `extraLarge=28` for sheets.

Set `KoodoTypography` to:

```kotlin
private val UiFamily = FontFamily.Default
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = UiFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

val KoodoTypography = Typography(
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 26),
    bodyMedium = style(15, 24),
    bodySmall = style(13, 20),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(13, 18),
    labelSmall = style(12, 16),
)
```

Add `val Decelerate = LinearOutSlowInEasing` to `KoodoMotion`. Change `TypographyTokens.lineHeightRatio`, `ReaderFeatureState.lineHeightRatio`, and `ReaderFeatureCodec.fromMap` fallback to `1.6f`. Set:

```kotlin
const val FONT_SCALE_MIN = 14f / 17f
const val FONT_SCALE_MAX = 22f / 17f
private const val PARAGRAPH_LINE_HEIGHT_FACTOR = 1.2f

fun effectiveParagraphSpacingPx(state: ReaderFeatureState): Float {
    val explicit = clampedParagraphSpacing(state)
    return if (explicit > 0f) explicit
    else fontSizePx(state) * clampedLineHeight(state) * PARAGRAPH_LINE_HEIGHT_FACTOR
}

private fun cssPx(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(java.util.Locale.US, value)

fun userStylesheet(state: ReaderFeatureState, firstLineIndent: Boolean = false): String {
    val custom = if (state.customBookCssEnabled) state.customBookCss.trim() else ""
    val indent = if (firstLineIndent) "text-indent:2em;" else ""
    val paragraph = "p{margin-bottom:${cssPx(effectiveParagraphSpacingPx(state))}px;$indent}"
    return listOf(paragraph, custom).filter { it.isNotEmpty() }.joinToString("\n")
}
```

Remove the old `gap`/`para` implementation so there is only one paragraph rule. Update the comments that still claim line-height 1.5. In `DesignSystemSelfCheck`, replace `SpaceTokens.NAMED` on-scale validation with direct checks for `GRID_GAP_HORIZONTAL==12`, `LIST_ITEM_VERTICAL==12`, and `READER_HORIZONTAL_DEFAULT==20`; these semantic values intentionally are not base-scale entries.

- [ ] **Step 5: Run token and reader-settings tests**

Run:

```bash
./gradlew :core:designsystem:test :core:designsystem:selfCheck :app:testNativeHostDebugUnitTest --tests '*ReaderFeatureStateTest*' --tests '*ReadestViewSettingsTest*' :app:compileNativeHostDebugKotlin
```

Expected: all tests PASS, selfCheck prints `OK`, and Kotlin compilation succeeds with no `SpaceTokens.xxl` or `LIST_GAP` references.

- [ ] **Step 6: Commit Task 1**

```bash
git add android/core/designsystem android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt
git commit -m "feat(android): align third-round design tokens"
```

---

### Task 2: Add Testable Reader UI Preferences and System-Bar Policies

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderChromeUiState.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderSystemBarPolicy.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderChromeViewModelTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderSystemBarPolicyTest.kt`

**Interfaces:**
- Produces: `ReaderChromeUiState(fullScreen=false, firstLineIndent=false, horizontalMarginDp=20)`.
- Produces: `ReaderChromeViewModel.state: StateFlow<ReaderChromeUiState>`, `setFullScreen`, `setFirstLineIndent`, `setHorizontalMarginDp`, and `Factory(context)`.
- Produces: `ReaderSystemBarPolicy.shouldHide(fullScreen, chromeVisible)` and `ReaderContentInsetPolicy.topPx/bottomPx`.

- [ ] **Step 1: Write failing preference and system-bar matrix tests**

`ReaderChromeViewModelTest`:

```kotlin
class ReaderChromeViewModelTest {
    private class FakeStore : ReaderChromePreferenceStore {
        var value = ReaderChromeUiState()
        var saves = 0
        override fun load() = value
        override fun save(state: ReaderChromeUiState) { value = state; saves++ }
    }

    @Test
    fun `changes expose stateflow and persist once`() {
        val store = FakeStore()
        val vm = ReaderChromeViewModel(store)
        vm.setFullScreen(true)
        vm.setFirstLineIndent(true)
        vm.setHorizontalMarginDp(24)
        assertEquals(ReaderChromeUiState(true, true, 24), vm.state.value)
        assertEquals(store.value, vm.state.value)
        assertEquals(3, store.saves)
    }

    @Test
    fun `invalid margin falls back to default`() {
        val store = FakeStore()
        store.value = store.value.copy(horizontalMarginDp = 13)
        val vm = ReaderChromeViewModel(store)
        vm.setHorizontalMarginDp(13)
        assertEquals(20, vm.state.value.horizontalMarginDp)
    }
}
```

`ReaderSystemBarPolicyTest` must assert all four combinations and stable density conversion:

```kotlin
@Test
fun `system bar matrix matches immersive contract`() {
    assertFalse(ReaderSystemBarPolicy.shouldHide(false, true))
    assertTrue(ReaderSystemBarPolicy.shouldHide(false, false))
    assertTrue(ReaderSystemBarPolicy.shouldHide(true, true))
    assertTrue(ReaderSystemBarPolicy.shouldHide(true, false))
}

@Test
fun `content insets ignore chrome and preserve system space`() {
    assertEquals(72 + 24, ReaderContentInsetPolicy.topPx(72, 3f))
    assertEquals(72 + 48, ReaderContentInsetPolicy.bottomPx(72, 3f))
}
```

- [ ] **Step 2: Run tests and confirm missing types**

Run:

```bash
./gradlew :app:testNativeHostDebugUnitTest --tests '*ReaderChromeViewModelTest*' --tests '*ReaderSystemBarPolicyTest*'
```

Expected: FAIL because the four production types do not exist.

- [ ] **Step 3: Implement UI preference storage and ViewModel**

Create `ReaderChromeUiState.kt`:

```kotlin
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
```

- [ ] **Step 4: Implement pure system-bar and inset policies**

Create `ReaderSystemBarPolicy.kt`:

```kotlin
package com.koodoreader.reader.shell

import com.koodoreader.core.designsystem.SpaceTokens
import kotlin.math.roundToInt

object ReaderSystemBarPolicy {
    fun shouldHide(fullScreen: Boolean, chromeVisible: Boolean): Boolean =
        fullScreen || !chromeVisible
}

object ReaderContentInsetPolicy {
    fun topPx(statusBarPx: Int, density: Float): Int =
        statusBarPx + (SpaceTokens.READER_TOP_EXTRA * density).roundToInt()
    fun bottomPx(navigationBarPx: Int, density: Float): Int =
        navigationBarPx + (SpaceTokens.READER_BOTTOM_EXTRA * density).roundToInt()
}
```

- [ ] **Step 5: Run the focused tests**

Run:

```bash
./gradlew :app:testNativeHostDebugUnitTest --tests '*ReaderChromeViewModelTest*' --tests '*ReaderSystemBarPolicyTest*'
```

Expected: all preference, fallback, state-matrix, and inset tests PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/ReaderChromeUiState.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderSystemBarPolicy.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderChromeViewModelTest.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderSystemBarPolicyTest.kt
git commit -m "feat(android): add immersive reader ui state"
```

---

### Task 3: Build Shared Edge-to-Edge Reader Chrome and Settings Controls

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativeShellActivity.kt:59-87`
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme/KoodoTheme.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderSystemBars.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderChrome.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderPresentation.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderSettingsSheet.kt`

**Interfaces:**
- Consumes: Task 2 `ReaderChromeUiState`, `ReaderSystemBarPolicy`, and `ReaderContentInsetPolicy`.
- Produces: `ReaderSystemBars(fullScreen, chromeVisible, readerBackground)`, `rememberReaderContentInsets()`.
- Produces: `ReaderTopBar(visible, title, onBack, onClose, actions)`, where `title` is a composable slot and `actions` is a `RowScope` slot.
- Produces: `ReaderBottomProgress(visible, fraction, label, onClick, modifier, actions)`.
- Produces: `rememberReaderColorScheme(kind, customBackgroundArgb)`.
- Settings accepts optional `chromeState` and `onChromeState`; defaults keep both callers compiling until Tasks 4 and 5 wire persistence.

- [ ] **Step 1: Make the Activity globally edge-to-edge**

Immediately after `super.onCreate(savedInstanceState)` in `NativeShellActivity`, add:

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
```

Import `androidx.core.view.WindowCompat`. Do not set it back to `true` anywhere in reader disposal.

- [ ] **Step 2: Expose the shell scheme and transparent system bars**

In `KoodoTheme.kt`, add:

```kotlin
val LocalKoodoShellColorScheme = staticCompositionLocalOf<ColorScheme> { koodoLightScheme() }
```

Provide `LocalKoodoShellColorScheme provides scheme` in the existing `CompositionLocalProvider`. In the existing window `SideEffect`, also set:

```kotlin
statusBarColor = android.graphics.Color.TRANSPARENT
navigationBarColor = android.graphics.Color.TRANSPARENT
if (android.os.Build.VERSION.SDK_INT >= 29) {
    isStatusBarContrastEnforced = false
    isNavigationBarContrastEnforced = false
}
```

These calls are inside `window.apply`; suppress the platform color deprecation at function level if required. Keep the existing animated window background and icon logic.

- [ ] **Step 3: Implement stable insets and system-bar restoration**

Create `ReaderSystemBars.kt` with this behavior:

```kotlin
data class ReaderContentInsets(val top: Dp, val bottom: Dp)

@Composable
fun rememberReaderContentInsets(): ReaderContentInsets {
    val density = LocalDensity.current
    val top = ReaderContentInsetPolicy.topPx(
        WindowInsets.statusBarsIgnoringVisibility.getTop(density), density.density,
    )
    val bottom = ReaderContentInsetPolicy.bottomPx(
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density), density.density,
    )
    return remember(top, bottom, density) {
        ReaderContentInsets(with(density) { top.toDp() }, with(density) { bottom.toDp() })
    }
}

@Composable
fun ReaderSystemBars(fullScreen: Boolean, chromeVisible: Boolean, readerBackground: Color) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = WindowCompat.getInsetsController(window, view)
    val shellScheme = LocalKoodoShellColorScheme.current
    val currentShellScheme = rememberUpdatedState(shellScheme)
    val readerDarkIcons = readerBackground.luminance() > 0.5f

    SideEffect {
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(readerBackground.toArgb()))
        controller.isAppearanceLightStatusBars = readerDarkIcons
        controller.isAppearanceLightNavigationBars = readerDarkIcons
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (ReaderSystemBarPolicy.shouldHide(fullScreen, chromeVisible)) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(view) {
        onDispose {
            val scheme = currentShellScheme.value
            val darkIcons = scheme.background.luminance() > 0.5f
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
            window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
        }
    }
}
```

Use `android.graphics.Color` alias `AndroidColor`; do not add a second `DisposableEffect` keyed by `chromeVisible`.

- [ ] **Step 4: Implement shared top and bottom chrome**

Create `ReaderChrome.kt`:

```kotlin
object ReaderColorTokens {
    const val CHROME_ALPHA = 0.92f
    const val PROGRESS_TRACK_ALPHA = 0.12f
    const val HIGHLIGHT_ALPHA = 0.30f
}

@Composable
fun ReaderTopBar(
    visible: Boolean,
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionTokens.NORMAL, easing = KoodoMotion.Decelerate)) +
            slideInVertically(tween(MotionTokens.NORMAL, easing = KoodoMotion.Decelerate)) { -it },
        exit = fadeOut(tween(MotionTokens.NORMAL)) +
            slideOutVertically(tween(MotionTokens.NORMAL)) { -it },
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA),
                    1f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                )
            ).statusBarsPadding()
        ) {
            TopAppBar(
                modifier = Modifier.height(SpaceTokens.TOP_BAR_HEIGHT.dp),
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = t("Back"))
                    }
                },
                actions = {
                    actions()
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = t("Close"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
fun ReaderBottomProgress(
    visible: Boolean,
    fraction: Float,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(MotionTokens.NORMAL)) + slideInVertically(tween(MotionTokens.NORMAL)) { it },
        exit = fadeOut(tween(MotionTokens.NORMAL)) + slideOutVertically(tween(MotionTokens.NORMAL)) { it },
    ) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    1f to MaterialTheme.colorScheme.surface.copy(alpha = ReaderColorTokens.CHROME_ALPHA),
                )
            ).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).clickable(onClick = onClick)
        ) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(SizeTokens.PROGRESS_STROKE.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ReaderColorTokens.PROGRESS_TRACK_ALPHA),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SpaceTokens.md.dp, vertical = SpaceTokens.sm.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                actions()
            }
        }
    }
}
```

The gradient supplies hierarchy, so do not add shadow elevation on top of it. `ElevationTokens.READER_CHROME` is reserved for a surface-style chrome variant.

- [ ] **Step 5: Implement one reader/custom theme resolver**

Create `ReaderPresentation.kt`:

```kotlin
@Composable
fun rememberReaderColorScheme(kind: ThemeKind, customBackgroundArgb: Int?): ColorScheme {
    val base = remember(kind) { ThemeSpecBridge.readerScheme(kind.builtInPreset()) }
    return remember(kind, customBackgroundArgb) {
        if (customBackgroundArgb == null) return@remember base
        val background = Color(customBackgroundArgb)
        val dark = background.luminance() < 0.5f
        val content = if (dark) Color(0xFFE3E2E6) else Color(0xFF1A1C1E)
        base.copy(
            background = background,
            onBackground = content,
            surface = background,
            onSurface = content,
            primary = content,
            onPrimary = background,
        )
    }
}
```

- [ ] **Step 6: Add settings controls without breaking current callers**

Add these optional parameters to `ReaderSettingsSheet` before `onDismiss`:

```kotlin
chromeState: ReaderChromeUiState = ReaderChromeUiState(),
onChromeState: (ReaderChromeUiState) -> Unit = {},
```

At the start of the sheet Column, add:

```kotlin
ReaderSettingSwitch(
    label = t("Full-screen reading"),
    checked = chromeState.fullScreen,
    onChange = { onChromeState(chromeState.copy(fullScreen = it)) },
)
ReaderSettingSwitch(
    label = t("First-line indent"),
    checked = chromeState.firstLineIndent,
    onChange = { onChromeState(chromeState.copy(firstLineIndent = it)) },
)
Text(t("Page margin"), style = MaterialTheme.typography.titleMedium)
Row(horizontalArrangement = Arrangement.spacedBy(SpaceTokens.sm.dp)) {
    SpaceTokens.READER_HORIZONTAL_OPTIONS.forEach { margin ->
        FilterChip(
            selected = chromeState.horizontalMarginDp == margin,
            onClick = { onChromeState(chromeState.copy(horizontalMarginDp = margin)) },
            label = { Text("$margin dp") },
        )
    }
}
```

Add a private `ReaderSettingSwitch` row with `Text(weight(1f))` and `Switch`. Change sheet content modifiers to:

```kotlin
Modifier.fillMaxWidth()
    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
    .imePadding()
    .verticalScroll(rememberScrollState())
    .padding(horizontal = SpaceTokens.READER_HORIZONTAL_DEFAULT.dp)
    .padding(top = SpaceTokens.sm.dp, bottom = SpaceTokens.md.dp)
```

Set the font slider to `steps=7`. Show the actual automatic paragraph gap instead of a misleading zero:

```kotlin
val paragraphGap = ReaderFeatureCodec.effectiveParagraphSpacingPx(state)
Slider(
    value = paragraphGap,
    onValueChange = { onChange(state.copy(paragraphSpacingPx = it)) },
    valueRange = 0f..maxOf(ReaderFeatureCodec.PARAGRAPH_SPACING_MAX, paragraphGap),
)
```

Add English and Simplified Chinese locale values for `Full-screen reading`, `First-line indent`, and `Page margin` through the existing `src/assets/locales` + `scripts/sync-locales-android.js` workflow.

- [ ] **Step 7: Compile and synchronize locales**

Run:

```bash
node scripts/sync-locales-android.js --check
cd android && ./gradlew :app:compileNativeHostDebugKotlin
```

Expected: locale check and Kotlin compilation PASS; current EPUB/PDF callers still compile because the new settings parameters have defaults.

- [ ] **Step 8: Commit Task 3**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/NativeShellActivity.kt android/core/ui/src/main/kotlin/com/koodoreader/core/ui/theme/KoodoTheme.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderSystemBars.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderChrome.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderPresentation.kt android/app/src/main/java/com/koodoreader/reader/reader/ReaderSettingsSheet.kt src/assets/locales android/app/src/main/assets/locales
git commit -m "feat(android): add shared immersive reader chrome"
```

---

### Task 4: Wire EPUB to Shared Chrome, Safe Padding, Margins, and Indent

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/ReadestViewSettings.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/ReaderAppearanceSheet.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt`

**Interfaces:**
- Consumes: `ReaderChromeViewModel`, `ReaderSystemBars`, `ReaderTopBar`, `ReaderBottomProgress`, `rememberReaderColorScheme`, and `rememberReaderContentInsets`.
- Produces: `ReadestViewSettings.from(kind, features, foliateFontFamily, extraUserStylesheet, horizontalMarginPx: Int? = null, firstLineIndent: Boolean = false)`; null margin preserves legacy behavior for other callers.

- [ ] **Step 1: Add failing EPUB presentation tests**

Append to `ReadestViewSettingsTest`:

```kotlin
@Test
fun `ui margin override is physical pixels and indent is css`() {
    val snapshot = ReadestViewSettings.from(
        ThemeKind.DEFAULT,
        ReaderFeatureState(),
        horizontalMarginPx = 60,
        firstLineIndent = true,
    )
    assertEquals(60, snapshot.marginPx)
    assertTrue(snapshot.userStylesheet.contains("text-indent:2em;"))
}

@Test
fun `null ui margin keeps legacy margin mapping`() {
    val snapshot = ReadestViewSettings.from(
        ThemeKind.DEFAULT,
        ReaderFeatureState(marginHorizontalPx = -8f),
    )
    assertEquals(0, snapshot.marginPx)
}
```

- [ ] **Step 2: Run the EPUB tests and confirm failure**

Run:

```bash
./gradlew :app:testNativeHostDebugUnitTest --tests '*ReadestViewSettingsTest*'
```

Expected: FAIL because the two new optional parameters do not exist.

- [ ] **Step 3: Implement the EPUB display-parameter override**

Add optional parameters to `ReadestViewSettings.from`:

```kotlin
horizontalMarginPx: Int? = null,
firstLineIndent: Boolean = false,
```

Use them in the returned snapshot:

```kotlin
marginPx = horizontalMarginPx
    ?: ReaderFeatureCodec.clampedMargin(features).toInt().coerceAtLeast(0),
```

Build `userCss` from `ReaderFeatureCodec.userStylesheet(features, firstLineIndent)` plus `extraUserStylesheet.trim()` exactly as before. No other snapshot field changes.

- [ ] **Step 4: Connect the ViewModel, reader scheme, and stable insets**

In `NativeEpubScreen`, create the chrome ViewModel with `viewModel(factory = ReaderChromeViewModel.Factory(context))`, collect `state` with lifecycle, and compute:

```kotlin
val chromeState by chromeViewModel.state.collectAsStateWithLifecycle()
val contentInsets = rememberReaderContentInsets()
val horizontalMarginPx = with(LocalDensity.current) { chromeState.horizontalMarginDp.dp.roundToPx() }
```

Replace the preset-only color block with:

```kotlin
val readerScheme = rememberReaderColorScheme(readerThemeKind, features.customBackgroundArgb)
val bgColor = readerScheme.background
val fgColor = readerScheme.onBackground
```

Use `MaterialTheme(colorScheme = readerScheme)` and replace `ReaderImmersiveBars(showChrome || showToc)` with:

```kotlin
ReaderSystemBars(
    fullScreen = chromeState.fullScreen,
    chromeVisible = showChrome || showToc,
    readerBackground = bgColor,
)
```

Add `chromeState.horizontalMarginDp`, `contentInsets`, and `chromeState.firstLineIndent` to the `viewSettings` remember keys; pass `horizontalMarginPx` and `firstLineIndent` to `ReadestViewSettings.from`.

Update the existing `ReaderSettingsSheet` call with the persisted UI state:

```kotlin
ReaderSettingsSheet(
    state = features,
    onChange = { commitFeatures(it) },
    chromeState = chromeState,
    onChromeState = chromeViewModel::update,
    onDismiss = { showReaderSettings = false },
)
```

- [ ] **Step 5: Pad only the Foliate viewport and replace chrome containers**

Change the Foliate `AndroidView` modifier to:

```kotlin
Modifier.fillMaxSize().padding(top = contentInsets.top, bottom = contentInsets.bottom)
```

Do not apply this padding to loading/error overlays or to the outer viewport-measuring Box. The top/bottom values do not depend on `showChrome`.

Replace the existing outer top `AnimatedVisibility` + gradient `Box` + `TopAppBar` wrapper with `ReaderTopBar`; move the existing title slot, TOC/search/more action bodies, `onBack`, and `showChrome=false` close callback into the corresponding parameters. Remove only the old explicit close `IconButton`, because `ReaderTopBar` supplies it. Do not alter any `DropdownMenuItem` callback.

Replace the EPUB bottom bar with:

```kotlin
ReaderBottomProgress(
    visible = showChrome && (pageCount > 0 || (useFoliate && foliateReady) || (useNativeTextReader && chapterLoader != null)),
    fraction = when {
        useFoliate -> foliateFraction.toFloat()
        useNativeTextReader -> nativeFraction.toFloat()
        pageCount > 0 -> (currentPage + 1).toFloat() / pageCount
        else -> 0f
    },
    label = progressLabel,
    onClick = { showToc = true },
)
```

Move the existing `bar` computation to `val progressLabel` immediately before this call without changing its branches or text. Do not consume Scaffold `padding` for book content.

In the page-turn `AnimatedContent` near the current lines 1837–1841, change only its enter/exit durations from `MotionTokens.NORMAL` to `MotionTokens.PAGE` and use `KoodoMotion.Decelerate`. Leave toolbar, hint, and selection-menu durations unchanged.

- [ ] **Step 6: Unify appearance-sheet spacing and margin choices**

In `ReaderAppearanceSheet`, replace `marginPx/onMargin` with `marginDp/onMarginDp`, use `SpaceTokens` for all sheet padding/spacers, add `windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)` and bottom 16dp, and replace the margin Slider with the same four FilterChips used by `ReaderSettingsSheet`. Set the 14–22sp font slider to `steps=7` and the 1.0–2.0 line-height slider to `steps=9`, so 1.6 is an exact selectable value. In `NativeEpubScreen`, pass `chromeState.horizontalMarginDp` and `chromeViewModel::setHorizontalMarginDp`.

- [ ] **Step 7: Run EPUB tests and compilation**

Run:

```bash
./gradlew :app:testNativeHostDebugUnitTest --tests '*ReadestViewSettingsTest*' --tests '*ReaderFeatureStateTest*' :app:compileNativeHostDebugKotlin
```

Expected: tests and compilation PASS. Check `git diff` to confirm no engine `.mjs`, session, CFI, DAO, or progress files changed.

- [ ] **Step 8: Commit Task 4**

```bash
git add android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/main/java/com/koodoreader/reader/epubhost/ReadestViewSettings.kt android/app/src/main/java/com/koodoreader/reader/epubhost/ReaderAppearanceSheet.kt android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt
git commit -m "feat(android): immerse epub reader chrome"
```

---

### Task 5: Wire PDF to the Same Theme, System Bars, Safe Padding, and Progress

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfJsHostBridge.kt:103-110`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/PdfReaderController.kt:237`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderSystemBarPolicyTest.kt`

**Interfaces:**
- Consumes: the same Task 3/4 UI interfaces; no PDF controller API or page-state shape changes.
- PDF raster receives vertical safe padding only. PDF reflow additionally receives horizontal UI margin and typography CSS.

- [ ] **Step 1: Add the PDF UI state and reader theme**

Create and collect `ReaderChromeViewModel` exactly as in Task 4. Create `appearancePrefs`, collect `readerThemeKindFlow`, close it on dispose, and resolve:

```kotlin
val readerScheme = rememberReaderColorScheme(readerThemeKind, features.customBackgroundArgb)
val contentInsets = rememberReaderContentInsets()
```

Wrap the PDF reading UI in `MaterialTheme(colorScheme = readerScheme)` and call:

```kotlin
ReaderSystemBars(
    fullScreen = chromeState.fullScreen,
    chromeVisible = showChrome || showOutline,
    readerBackground = readerScheme.background,
)
```

Update the existing `ReaderSettingsSheet` call with the Task 2 single-write API:

```kotlin
ReaderSettingsSheet(
    state = features,
    onChange = {
        features = it
        featurePrefs.save(it)
    },
    chromeState = chromeState,
    onChromeState = chromeViewModel::update,
    onDismiss = { showReaderSettings = false },
)
```

The private `update(transform)` remains the single StateFlow + persistence path; one UI control produces exactly one preference write.

- [ ] **Step 2: Apply safe padding without changing PDF page geometry**

For raster pages, apply `Modifier.padding(top = contentInsets.top, bottom = contentInsets.bottom)` to the scrolling page container, not to `controller.onViewportWidth`, `drawnWidth`, zoom, highlight rectangles, or `containerSize`. For reflow, additionally apply start/end padding using `chromeState.horizontalMarginDp.dp`.

The container remains `fillMaxSize`; `controller.onViewportWidth(it.width)` continues to receive the original outer width so highlight coordinates do not change.

- [ ] **Step 3: Replace PDF chrome while retaining all actions**

Replace the outer PDF top `AnimatedVisibility` + gradient + `TopAppBar` with `ReaderTopBar`. Reuse the existing title `Column` (book name plus page/zoom subtitle), outline/search/more action callbacks, and remove only the old explicit close button.

Replace the PDF bottom AnimatedVisibility with:

```kotlin
ReaderBottomProgress(
    visible = showChrome,
    fraction = if (state.pageCount > 0) state.page.toFloat() / state.pageCount else 0f,
    label = "${state.page} / ${state.pageCount}",
    onClick = { controller.loadOutline(); showOutline = true },
    modifier = Modifier.align(Alignment.BottomCenter),
    actions = {
        TextButton(onClick = controller::previousPage, enabled = state.page > 1) {
            Text("‹ ${t("Previous page")}")
        }
        TextButton(onClick = controller::nextPage, enabled = state.page < state.pageCount) {
            Text("${t("Next page")} ›")
        }
    },
)
```

These are the exact labels and enabled conditions from the existing `PagerBar`; no new icon resource is needed.

- [ ] **Step 4: Apply reflow typography as display CSS**

On reflow `onPageFinished`, inject a style element using `JSONObject.quote` for the CSS string. Generate CSS and retain its latest value before creating the WebView callback:

```kotlin
val reflowCss = ReaderFeatureCodec.userStylesheet(
    features,
    firstLineIndent = chromeState.firstLineIndent,
) + "\nbody{line-height:${ReaderFeatureCodec.clampedLineHeight(features)};font-size:${ReaderFeatureCodec.fontSizePx(features)}px;margin:0;}"
val currentReflowCss by rememberUpdatedState(reflowCss)
```

Use `currentReflowCss` inside `onPageFinished`, so changes after WebView creation cannot leave stale typography:

```kotlin
view?.evaluateJavascript(
    "document.getElementById('koodo-reader-typography')?.remove();" +
        "var s=document.createElement('style');s.id='koodo-reader-typography';" +
        "s.textContent=${JSONObject.quote(currentReflowCss)};document.head.appendChild(s);",
    null,
)
```

Keep `REFLOW_SELECTION_RELAY_JS` and highlight painting calls in their existing order. Do not modify HTML extraction, selection coordinates, or page mapping.

- [ ] **Step 5: Align PDF reflow font-scale clamps with 14–22sp**

In both `PdfJsHostBridge.reflowHtml` and `PdfReaderController`, replace literal scale clamps `0.7f..2.5f` with `ReaderFeatureCodec.FONT_SCALE_MIN..FONT_SCALE_MAX`. This changes display scale only; no interface or returned HTML shape changes.

- [ ] **Step 6: Compile and run reader policy tests**

Run:

```bash
./gradlew :app:testNativeHostDebugUnitTest --tests '*ReaderSystemBarPolicyTest*' --tests '*ReaderFeatureStateTest*' :app:compileNativeHostDebugKotlin
```

Expected: tests and compilation PASS; there are no unresolved icon resources or `ReaderChromeViewModel.update` visibility errors.

- [ ] **Step 7: Commit Task 5**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderChromeUiState.kt android/app/src/main/java/com/koodoreader/reader/pdfhost/PdfJsHostBridge.kt android/app/src/main/java/com/koodoreader/reader/shell/PdfReaderController.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderSystemBarPolicyTest.kt
git commit -m "feat(android): immerse pdf reader chrome"
```

---

### Task 6: Normalize Shelf Rhythm and First-Presentation Motion

**Files:**
- Create: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/component/KoodoInteractions.kt`
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/component/KoodoBookCard.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryLoadingSkeleton.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderChrome.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderSettingsSheet.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`

**Interfaces:**
- Produces: `Modifier.koodoPressScale()` and `Modifier.staggeredEntrance(index, play)`.
- Motion never consumes gestures and never changes book selection/reorder callbacks.

- [ ] **Step 1: Implement non-consuming press and one-shot stagger modifiers**

Create `KoodoInteractions.kt`:

```kotlin
@Composable
fun Modifier.koodoPressScale(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) MotionTokens.PRESS_SCALE else 1f,
        animationSpec = tween(MotionTokens.FAST, easing = KoodoMotion.Standard),
        label = "koodoPressScale",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }
}

@Composable
fun Modifier.staggeredEntrance(index: Int, play: Boolean): Modifier = composed {
    if (!play) return@composed this
    val progress = remember(play) { Animatable(0f) }
    LaunchedEffect(play) {
        delay(index.coerceAtMost(12) * MotionTokens.STAGGER.toLong())
        progress.animateTo(1f, tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate))
    }
    this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * SpaceTokens.md.dp.toPx()
    }
}
```

- [ ] **Step 2: Apply press feedback, card spacing, and grid spacing**

Add `modifier = Modifier.koodoPressScale()` to every `IconButton`, `TextButton`, `Button`, `FilterChip`, and `DropdownMenuItem` in the core shelf/reader files listed above, preserving all existing callbacks, enabled flags, and ripple. Representative exact calls are:

```kotlin
IconButton(onClick = onClose, modifier = Modifier.koodoPressScale()) {
    Icon(painterResource(R.drawable.ic_close), contentDescription = t("Close"))
}

TextButton(
    onClick = controller::previousPage,
    enabled = state.page > 1,
    modifier = Modifier.koodoPressScale(),
) {
    Text("‹ ${t("Previous page")}")
}
```

For margin chips, place the same modifier before the existing `label` parameter. Do not add the modifier to `Switch`, `Slider`, or the book content gesture layer.

In `KoodoBookCard`, apply `koodoPressScale()` to the clickable container, title top padding `SpaceTokens.COVER_TO_TEXT`, subtitle top padding `SpaceTokens.TEXT_LINE_GAP`, and `MaterialTheme.typography.bodyMedium/bodySmall`. In `KoodoBookRow`, retain the 48dp cover and use the same typography and press modifier.

In `LibraryScreen`, set both TopAppBar variants to `Modifier.height(SpaceTokens.TOP_BAR_HEIGHT.dp)` and title style `titleLarge`. Use:

```kotlin
contentPadding = PaddingValues(
    start = SpaceTokens.SCREEN_HORIZONTAL.dp,
    end = SpaceTokens.SCREEN_HORIZONTAL.dp,
    top = SpaceTokens.md.dp,
    bottom = SpaceTokens.md.dp,
)
horizontalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_HORIZONTAL.dp)
verticalArrangement = Arrangement.spacedBy(SpaceTokens.GRID_GAP_VERTICAL.dp)
```

List rows use horizontal `LIST_ITEM_HORIZONTAL` and vertical `LIST_ITEM_VERTICAL`; list group spacing is `GROUP_GAP`.

- [ ] **Step 3: Ensure stagger runs only on first presentation**

Before the grid, add:

```kotlin
var playEntrance by rememberSaveable { mutableStateOf(true) }
LaunchedEffect(libraryLoading) {
    if (!libraryLoading) {
        delay(MotionTokens.PAGE + MotionTokens.STAGGER * 12L)
        playEntrance = false
    }
}
```

Apply this exact modifier-line diff for grid cards; the existing drag body and keys remain unchanged:

```diff
- modifier = Modifier.pointerInput(book.key, selection.active) {
+ modifier = Modifier.staggeredEntrance(index, play = playEntrance).pointerInput(book.key, selection.active) {
```

Update skeleton spacing to the same grid tokens.

- [ ] **Step 4: Compile shared UI and app**

Run:

```bash
./gradlew :core:ui:compileDebugKotlin :app:compileNativeHostDebugKotlin
```

Expected: PASS; no missing gesture/animation imports and no duplicate pointer-input replacement of drag behavior.

- [ ] **Step 5: Commit Task 6**

```bash
git add android/core/ui/src/main/kotlin/com/koodoreader/core/ui/component/KoodoInteractions.kt android/core/ui/src/main/kotlin/com/koodoreader/core/ui/component/KoodoBookCard.kt android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/LibraryLoadingSkeleton.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderChrome.kt android/app/src/main/java/com/koodoreader/reader/reader/ReaderSettingsSheet.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt
git commit -m "feat(android): refine shelf spacing and motion"
```

---

### Task 7: Normalize Full-Screen TOC, Annotations, and Search Rows

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderTocPage.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/BookAnnotationsScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/AdaptiveReaderLayout.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/NativeTextReaderScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt` (search rows and highlight display only)
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt` (search rows and highlight display only)
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/PdfReflowHighlights.kt`
- Modify: `android/engine/annotate/src/main/kotlin/com/koodoreader/engine/annotate/HighlightPalette.kt`
- Test: `android/engine/annotate/src/test/kotlin/com/koodoreader/engine/annotate/HighlightPaletteTest.kt`

**Interfaces:**
- All jump/edit/delete/recolor/export callbacks and `SearchHit` types remain unchanged.
- Produces: `HighlightPalette.readerCss(stored)` and 30% `paintArgb` output; stored color values and `css(stored)` remain compatible.

- [ ] **Step 1: Make TOC safe and rhythmically consistent**

In `ReaderTocPage`, set the TopAppBar height to `SpaceTokens.TOP_BAR_HEIGHT.dp` and title style to `titleLarge`. Wrap `content` with:

```kotlin
Box(Modifier.padding(scaffoldPadding).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)) {
    content(
        PaddingValues(
            horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
            vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp,
        )
    )
}
```

Keep `BackHandler(onBack=onBack)` and the caller-provided LazyColumn/scroll state. The final list item must remain reachable above the navigation area.

- [ ] **Step 2: Normalize annotation cards and preserve guards**

In `BookAnnotationsScreen`, use 16dp horizontal and 12dp vertical item padding, `SpaceTokens.GROUP_TITLE_GAP` between text rows, and `SpaceTokens.GROUP_GAP` between note/bookmark groups. Use `KoodoCardShape` and `surfaceContainerLow` for note cards. Text hierarchy is bodyMedium for source, bodySmall for note/chapter metadata, labelSmall for timestamps.

Retain this jump guard literally:

```kotlin
note.cfi?.takeIf { it.isNotBlank() }?.let { cfi ->
    TextButton(onClick = { onJump(cfi) }) { Text(t("Jump")) }
}
```

Retain the original edit assignment `editing = note; draft = note.notes.orEmpty()` and all recolor/delete callbacks. Keep palette swatches opaque even though reader highlights are translucent:

```kotlin
val rgb = HighlightPalette.paintArgb(code) and 0xFFFFFF
Box(Modifier.size(20.dp).background(Color(rgb or 0xFF000000), CircleShape))
```

- [ ] **Step 3: Normalize existing search-result rows only**

Apply the shared padding to the three existing EPUB jump calls without changing them:

```kotlin
Modifier.fillMaxWidth()
    .clickable { jumpToFoliateHit(hit) }
    .padding(horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp, vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp)
```

```kotlin
Modifier.fillMaxWidth()
    .clickable { jumpToNativeSearchHit(hit) }
    .padding(horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp, vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp)
```

```kotlin
Modifier.fillMaxWidth()
    .clickable { jumpToHit(hit) }
    .padding(horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp, vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp)
```

For the PDF `TextButton(onClick = { onJump(index) })`, keep that callback and add:

```kotlin
contentPadding = PaddingValues(
    horizontal = SpaceTokens.LIST_ITEM_HORIZONTAL.dp,
    vertical = SpaceTokens.LIST_ITEM_VERTICAL.dp,
)
```

Use `SpaceTokens.xs` between chapter/snippet/location Text elements. Do not introduce a new `SearchHit` model or rename fields in Foliate/PDF result types. Preserve loading, empty, error, and retry branches.

- [ ] **Step 4: Apply 30% highlight opacity to every reader display path**

First change `HighlightPaletteTest` to assert the new display contract:

```kotlin
@Test
fun `reader paint always uses thirty percent alpha`() {
    val paint = HighlightPalette.paintArgb(0x9988FF88L)
    assertEquals(0x4D88FF88L, paint)
    assertEquals("rgba(136,255,136,0.3)", HighlightPalette.readerCss(0x9988FF88L))
}
```

Then implement the palette additions without changing persisted colors or legacy `css(stored)`:

```kotlin
const val READER_ALPHA = 0.30f
private const val READER_ALPHA_INT = 0x4D

fun argb(color: HighlightColor, alpha: Int = READER_ALPHA_INT): Long {
    val rgb = color.hex.removePrefix("#").toLong(16)
    return ((alpha.toLong() and 0xFF) shl 24) or rgb
}

fun paintArgb(stored: Long?): Long {
    val resolved = when {
        stored == null || stored == LEGACY_INVISIBLE_YELLOW -> argb(HighlightColor.YELLOW)
        stored in 1L..8L -> argb(HighlightColor.fromCode(stored))
        (stored ushr 24) and 0xFF != 0L -> stored
        else -> argb(HighlightColor.YELLOW)
    }
    return (READER_ALPHA_INT.toLong() shl 24) or (resolved and 0xFFFFFF)
}

fun readerCss(stored: Long?): String {
    val paint = paintArgb(stored)
    val red = (paint ushr 16) and 0xFF
    val green = (paint ushr 8) and 0xFF
    val blue = paint and 0xFF
    return "rgba($red,$green,$blue,$READER_ALPHA)"
}
```

Replace `HighlightPalette.css` with `readerCss` in `NativeEpubScreen.highlightCss`, the EPUB selection-add-highlight call, and `PdfReflowHighlights.jsonForPage`. Existing Canvas/PDF overlay calls to `paintArgb` automatically use the same alpha. Do not alter note colors in Room or export JSON.

- [ ] **Step 5: Apply 250ms page motion and run annotation-related tests**

In `AdaptiveReaderLayout` and the page-turn `AnimatedContent` in `NativeTextReaderScreen`, replace only page enter/exit `tween(MotionTokens.NORMAL)` with `tween(MotionTokens.PAGE, easing = KoodoMotion.Decelerate)`. Do not change sidebar, toolbar, hint, or selection-menu durations. PDF raster keeps its existing no-page-transition policy.

Run:

```bash
./gradlew :engine:annotate:test :app:compileNativeHostDebugKotlin :app:testNativeHostDebugUnitTest --tests '*BookAnnotations*' --tests '*PdfReflowHighlightsTest*'
```

Expected: palette tests, compilation, and all selected existing tests PASS. If a test unrelated to these UI files fails, record its exact output rather than changing business code to force it green.

- [ ] **Step 6: Commit Task 7**

```bash
git add android/app/src/main/java/com/koodoreader/reader/reader/ReaderTocPage.kt android/app/src/main/java/com/koodoreader/reader/reader/BookAnnotationsScreen.kt android/app/src/main/java/com/koodoreader/reader/reader/AdaptiveReaderLayout.kt android/app/src/main/java/com/koodoreader/reader/reader/NativeTextReaderScreen.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/PdfReflowHighlights.kt android/engine/annotate/src/main/kotlin/com/koodoreader/engine/annotate/HighlightPalette.kt android/engine/annotate/src/test/kotlin/com/koodoreader/engine/annotate/HighlightPaletteTest.kt
git commit -m "feat(android): align reader lists and contents"
```

---

### Task 8: Full Regression, APK, and Device Acceptance

**Files:**
- Modify only if a reproduced UI regression requires a fix: files from Tasks 1–7.
- Update after verification: `.superpowers/sdd/progress.md` (ignored progress log, not required in the commit).

**Interfaces:**
- Produces: tested `app-nativeHost-debug.apk`, test logs, and a checked device matrix. No product API changes.

- [ ] **Step 1: Review the complete diff for scope**

Run from repository root:

```bash
git diff --stat f13d9f0c..HEAD
git diff --check f13d9f0c..HEAD
git status --short
```

Expected: only planned UI/preferences/tests/locales plus the plan/spec documents. No Room schema, parser session, CFI mapper, search index, sync target, or unrelated untracked directory is staged.

- [ ] **Step 2: Run the complete automated verification**

Run:

```bash
cd android
./gradlew :core:designsystem:test :core:designsystem:selfCheck :engine:annotate:test :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native
```

Expected: exit code 0, selfCheck `OK`, zero unit-test failures, and APK generated at `app/build/outputs/apk/nativeHost/debug/app-nativeHost-debug.apk`.

- [ ] **Step 3: Install without wiping data**

Run from repository root:

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" devices
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r android/app/build/outputs/apk/nativeHost/debug/app-nativeHost-debug.apk
```

Expected: one connected OPPO device and `Success`. Do not uninstall or clear application data.

- [ ] **Step 4: Verify EPUB immersion and layout**

On the device, verify in order:

1. Open EPUB: no transient legacy error page; background reaches physical edges.
2. Full-screen OFF + chrome hidden: system bars hidden; top/bottom text padding remains visible.
3. Center tap: system bars and chrome appear together; 200ms fade/slide; book viewport does not repaginate or jump.
4. Close chrome with × and center tap: both hide again.
5. Full-screen ON: chrome, full-screen TOC, and settings do not restore persistent system bars; edge swipe reveals transient bars.
6. Light, dark, eye-protection, and custom backgrounds: system icons have readable contrast and no black/white bar flash.
7. Margin choices 12/16/20/24 and first-line indent update the presentation; font range is 14–22 and line-height default is 1.6.
8. Scroll settings to the final control; navigation/gesture area does not cover it.
9. Scroll a long TOC to the final item and jump; verify reading position still works.
10. Long-press selection, highlight, note, and return-to-source behave as before.

- [ ] **Step 5: Verify PDF and shelf**

PDF:

1. Open raster PDF, zoom, change pages, and toggle chrome; page geometry and highlight positions do not shift when chrome appears.
2. Verify top/bottom safe padding, 2dp progress line, pager buttons, full-screen behavior, and outline final item.
3. Open PDF reflow; verify horizontal margin, typography, selection, note, and return-to-source.
4. Rotate landscape/portrait and leave/re-enter; system bars restore correctly and page position remains intact.

Shelf:

1. Measure visual rhythm: 56dp title bar, 16dp outer margin, 12dp horizontal grid gap, 16dp vertical grid gap.
2. Cards show 8dp cover-to-title and 4dp text-line spacing; 12dp card rounding and readable typography.
3. First presentation staggers by 30ms; scrolling does not replay it; press scale stays 0.98 with ripple and drag/multi-select still works.
4. Return from reader: shell system bars and icon contrast are restored.

Capture screenshots only for final evidence and remove temporary screenshots/UI dumps after inspection.

- [ ] **Step 6: Run remaining pre-existing device checks**

Run:

```bash
cd android
./gradlew :benchmarks:connectedBenchmarkAndroidTest
```

Expected: connected benchmark completes; record actual results. Perform external-intent open and process-restoration smoke checks. Do not remove Foliate/pdf.js or decommission any engine as part of this UI task.

- [ ] **Step 7: Commit any verified regression fixes and report**

If no product fix was needed, there is no empty commit. If a fix was needed, rerun the exact failing scenario and Task 8 Step 2 before committing only that fix. Final report must list test totals, APK install result, device matrix results, benchmark result, and any unverified item explicitly.
