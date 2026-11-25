# Android Plan Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the automatically verifiable Android UI/UX plan, repair the two selection regressions, and leave only explicitly device-dependent acceptance items open.

**Architecture:** Keep Compose, Room and Foliate boundaries intact. Put selection visibility, CFI matching, script detection and pagination geometry in pure functions; persist range CFI for new native annotations while retaining point-CFI readers; move WebView quantisation into the paginator so host size and column count do not feed back into one another.

**Tech Stack:** Kotlin / Jetpack Compose / JUnit 4 + JUnit 5 / Groovy Gradle / JavaScript ES modules / Node.js >=20 / existing `org.json` test dependency.

## Global Constraints

- Base commit: `f9a5c158`; design: `docs/superpowers/specs/2026-11-25-android-plan-closeout-design.md`.
- Do not add dependencies, Room columns or persisted preference keys.
- Do not migrate, delete or rewrite existing point-CFI annotations.
- All new user-visible copy uses `t("...")`, `src/assets/locales/en.json`, `src/assets/locales/zh-CN.json`, then `node scripts/sync-locales-android.js`.
- Use Node.js >=20 for JS tests. On this machine prefix commands with `export PATH="$HOME/AppData/Roaming/nvm/v20.19.2:$PATH"`.
- Run Gradle from `android/` with `-Ptarget=native`; do not claim device validation without an attached device.
- Preserve existing behavior outside selection, native annotation anchors, typography and Foliate column geometry.
- Each task is committed separately only after its tests pass.

---

### Task 1: Restore draggable Foliate selections without removing the bottom sheet

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderSelectionUiPolicy.kt`
- Create: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderSelectionUiPolicyTest.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderSelectionBottomSheet.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `android/app/src/main/assets/foliate/engine.mjs`
- Modify: `scripts/test-foliate-host.js`
- Modify: `src/assets/locales/en.json`
- Modify: `src/assets/locales/zh-CN.json`

**Interfaces:**
- Produces: `ReaderSelectionUiPolicy.showMenu(menuOpen: Boolean, adjusting: Boolean): Boolean`
- Produces: `ReaderSelectionUiPolicy.showHandles(menuOpen: Boolean, adjusting: Boolean, useFoliate: Boolean): Boolean`
- Consumes: existing `FoliateHostBridge.selectionEdges()`, `extendSelection()` and `clearSelection()` signatures.

- [x] **Step 1: Write failing selection-state tests**

```kotlin
package com.koodoreader.reader.shell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSelectionUiPolicyTest {
    @Test
    fun `menu and handles are mutually exclusive`() {
        assertTrue(ReaderSelectionUiPolicy.showMenu(menuOpen = true, adjusting = false))
        assertFalse(ReaderSelectionUiPolicy.showMenu(menuOpen = true, adjusting = true))
        assertTrue(ReaderSelectionUiPolicy.showHandles(true, adjusting = true, useFoliate = true))
        assertFalse(ReaderSelectionUiPolicy.showHandles(true, adjusting = false, useFoliate = true))
    }

    @Test
    fun `closed or native selection never shows handles`() {
        assertFalse(ReaderSelectionUiPolicy.showHandles(false, adjusting = true, useFoliate = true))
        assertFalse(ReaderSelectionUiPolicy.showHandles(true, adjusting = true, useFoliate = false))
    }
}
```

- [x] **Step 2: Run the tests and confirm compilation fails**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.shell.ReaderSelectionUiPolicyTest" -Ptarget=native --console=plain`
Expected: FAIL, unresolved `ReaderSelectionUiPolicy`.

- [x] **Step 3: Implement the pure policy**

```kotlin
package com.koodoreader.reader.shell

object ReaderSelectionUiPolicy {
    fun showMenu(menuOpen: Boolean, adjusting: Boolean): Boolean = menuOpen && !adjusting

    fun showHandles(menuOpen: Boolean, adjusting: Boolean, useFoliate: Boolean): Boolean =
        menuOpen && adjusting && useFoliate
}
```

- [x] **Step 4: Add the adjustment action to the bottom sheet**

Add `onAdjustSelection: (() -> Unit)? = null` to `ReaderSelectionBottomSheet`. Render this row after `actions.forEach` only when non-null:

```kotlin
onAdjustSelection?.let { onAdjust ->
    SelectionActionRow(
        icon = { Icon(painterResource(R.drawable.ic_note), contentDescription = null) },
        label = t("Adjust selection"),
        onClick = onAdjust,
    )
}
```

Add locale entries `"Adjust selection": "Adjust selection"` and `"Adjust selection": "调整选区"`. Reuse the existing `"Done"` translations, then run `node scripts/sync-locales-android.js`.

- [x] **Step 5: Wire adjustment mode without clearing the WebView selection**

In `NativeEpubScreen` add:

```kotlin
var selectionAdjusting by remember { mutableStateOf(false) }
```

Register this after the existing selection `BackHandler` so it wins while adjusting:

```kotlin
BackHandler(enabled = showSelectionMenu && selectionAdjusting) {
    selectionAdjusting = false
}
```

Change the edge-query effect keys to `(showSelectionMenu, selectionAdjusting, selectionCfi, selectionExcerpt)` and guard it with:

```kotlin
if (!ReaderSelectionUiPolicy.showHandles(showSelectionMenu, selectionAdjusting, useFoliate)) {
    return@LaunchedEffect
}
```

Keep the existing `LaunchedEffect(showSelectionMenu)` clear-selection behavior unchanged. Replace the sheet and handle block with this complete wiring:

```kotlin
if (ReaderSelectionUiPolicy.showMenu(showSelectionMenu, selectionAdjusting) &&
    FoliateSelectionMenu.visible(currentSelectionText())
) {
    val clipboard = LocalClipboardManager.current
    ReaderSelectionBottomSheet(
        highlighted = isSelectionHighlighted(),
        actions = selectionActions,
        onDismiss = { showSelectionMenu = false },
        onSaveHighlight = { saveHighlight(it) },
        onDeleteHighlight = { deleteHighlight() },
        onAction = { runSelectionAction(it, clipboard) },
        onAdjustSelection = if (useFoliate) {
            { selectionAdjusting = true }
        } else {
            null
        },
    )
}
if (ReaderSelectionUiPolicy.showHandles(showSelectionMenu, selectionAdjusting, useFoliate)) {
    selectionEdges?.let { edges ->
        SelectionDragHandle(
            anchor = edges.start,
            startEdge = true,
            density = density,
            boundsLeft = foliateViewBounds.left,
            boundsTop = foliateViewBounds.top,
        ) { xCss, yCss -> foliateBridge?.extendSelection(xCss, yCss, startEdge = true) }
        SelectionDragHandle(
            anchor = edges.end,
            startEdge = false,
            density = density,
            boundsLeft = foliateViewBounds.left,
            boundsTop = foliateViewBounds.top,
        ) { xCss, yCss -> foliateBridge?.extendSelection(xCss, yCss, startEdge = false) }
    }
    TextButton(
        onClick = { selectionAdjusting = false },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = contentInsets.bottom + SpaceTokens.md.dp),
    ) { Text(t("Done")) }
}
```

- [x] **Step 6: Add a failing JS regression for crossing a selection edge**

Add this regression to `scripts/test-foliate-host.js`, importing the real module:

```javascript
const { selectionRangeIsUsable } = await import(pathToFileURL(ENGINE_MJS).href);
eq('collapsed drag range is rejected before replacing a live selection', selectionRangeIsUsable({ collapsed: true }), false);
eq('non-collapsed drag range remains usable', selectionRangeIsUsable({ collapsed: false }), true);
eq('missing drag range is rejected', selectionRangeIsUsable(null), false);
```

Run: `export PATH="$HOME/AppData/Roaming/nvm/v20.19.2:$PATH" && node scripts/test-foliate-host.js --quiet`
Expected before implementation: FAIL, missing export or assertion failure.

- [x] **Step 7: Reject collapsed drag results before touching the live selection**

Export this helper from `engine.mjs`:

```javascript
export function selectionRangeIsUsable(range) {
    return !!range && !range.collapsed;
}
```

After the existing `r.setStart`/`r.setEnd` calls in `extendSelection`, insert:

```javascript
if (!selectionRangeIsUsable(r)) return { ok: false, clamped: true };
```

Keep `found.sel.removeAllRanges()` after this guard. This preserves the last valid selection when a drag reaches or crosses the fixed end.

- [x] **Step 8: Run task regressions**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.shell.ReaderSelectionUiPolicyTest" --tests "com.koodoreader.reader.epubhost.FoliateSelectionMenuTest" :app:compileNativeHostDebugKotlin -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL.

Run: `export PATH="$HOME/AppData/Roaming/nvm/v20.19.2:$PATH" && node scripts/test-foliate-host.js --quiet`
Expected: all checks pass.

- [x] **Step 9: Commit Task 1**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/ReaderSelectionUiPolicy.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderSelectionUiPolicyTest.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderSelectionBottomSheet.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/main/assets/foliate/engine.mjs scripts/test-foliate-host.js src/assets/locales android/app/src/main/assets/locales
git commit -m "fix(android): restore draggable selections with the sheet"
```

---

### Task 2: Persist native range CFI and use exact highlight matching

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/NativeHighlightMatcher.kt`
- Create: `android/app/src/test/java/com/koodoreader/reader/reader/NativeHighlightMatcherTest.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/ChapterLoaderProgress.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/RoomNoteAnnotationStore.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/NativeNoteHighlights.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/reader/ChapterLoaderProgressTest.kt`

**Interfaces:**
- Produces: `ChapterLoaderProgress.cfiRangeForLocation(loader: ChapterLoader, location: TextLocation): String?`
- Produces: `NativeHighlightMatcher.matches(selectionCfi: String, storedCfi: String): Boolean`
- Produces: `NativeHighlightMatcher.startPointOrNull(cfi: String?): String?`
- Consumes: `CfiAnchor.rangeCfi(start, end)`, `CfiAnchor.startPoint(cfi)` and `CfiAnchor.isRange(cfi)`.

- [x] **Step 1: Write failing matcher tests**

```kotlin
package com.koodoreader.reader.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeHighlightMatcherTest {
    private val oldPoint = "epubcfi(/6/2!/2/0/2/1:3)"
    private val range = "epubcfi(/6/2!/2/0/2,/1:3,/1:5)"
    private val later = "epubcfi(/6/4!/2/0/2,/1:8,/1:9)"

    @Test
    fun `exact range matches`() {
        assertTrue(NativeHighlightMatcher.matches(range, range))
    }

    @Test
    fun `legacy point matches only the same selection start`() {
        assertTrue(NativeHighlightMatcher.matches(range, oldPoint))
        assertFalse(NativeHighlightMatcher.matches(later, oldPoint))
    }

    @Test
    fun `later chapter and partial overlap are not delete matches`() {
        assertFalse(NativeHighlightMatcher.matches(later, range))
        assertFalse(NativeHighlightMatcher.matches("epubcfi(/6/2!/2/0/2,/1:4,/1:6)", range))
    }

    @Test
    fun `blank and malformed values never match`() {
        assertFalse(NativeHighlightMatcher.matches("", oldPoint))
        assertFalse(NativeHighlightMatcher.matches(range, "bad"))
        assertTrue(NativeHighlightMatcher.startPointOrNull("bad") == null)
        assertTrue(oldPoint == NativeHighlightMatcher.startPointOrNull(range))
    }
}
```

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.reader.NativeHighlightMatcherTest" -Ptarget=native --console=plain`
Expected: FAIL, unresolved matcher.

- [x] **Step 2: Implement exact point/range matching**

```kotlin
package com.koodoreader.reader.reader

import com.koodoreader.engine.annotate.CfiAnchor

object NativeHighlightMatcher {
    fun matches(selectionCfi: String, storedCfi: String): Boolean {
        if (selectionCfi.isBlank() || storedCfi.isBlank()) return false
        if (selectionCfi == storedCfi) return true
        if (CfiAnchor.isRange(storedCfi)) return false
        return runCatching {
            CfiAnchor.isRange(selectionCfi) && CfiAnchor.startPoint(selectionCfi) == storedCfi
        }.getOrDefault(false)
    }

    fun startPointOrNull(cfi: String?): String? {
        if (cfi.isNullOrBlank()) return null
        return runCatching { CfiAnchor.startPoint(cfi) }.getOrNull()
    }
}
```

Run the Step 1 command. Expected: PASS.

- [x] **Step 3: Add failing range-construction and range-resume tests**

Append to `ChapterLoaderProgressTest`:

```kotlin
@Test
fun `selection maps to range cfi and resumes at its start`() {
    val loader = FakeLoader(listOf(ChapterDescriptor("idref:a", "One", 1, 10)))
    val location = com.koodoreader.engine.layout.reader.TextLocation("idref:a", 1, 3)
    val range = ChapterLoaderProgress.cfiRangeForLocation(loader, location)
    assertEquals("epubcfi(/6/1!/2/0/0,/1:1,/1:3)", range)
    val restored = ChapterLoaderProgress.initialLocation(loader, range)
    assertEquals("idref:a", restored.chapterId)
    assertEquals(1, restored.start)
    loader.close()
}
```

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.reader.ChapterLoaderProgressTest" -Ptarget=native --console=plain`
Expected: FAIL, missing `cfiRangeForLocation` and range resume fails.

- [x] **Step 4: Implement range construction and point-only resume**

Import `CfiAnchor`. Change `initialLocation` to use this guarded point conversion:

```kotlin
val point = runCatching { CfiAnchor.startPoint(storedCfi) }.getOrNull() ?: return fallback
val pos = CfiAddressing.fromCfi(point) ?: return fallback
```

Refactor offset conversion and add:

```kotlin
fun cfiRangeForLocation(loader: ChapterLoader, location: TextLocation): String? {
    val start = cfiForLocation(loader, location) ?: return null
    if (location.end <= location.start) return start
    val end = cfiForOffset(loader, location.chapterId, location.end) ?: return null
    return runCatching { CfiAnchor.rangeCfi(start, end) }.getOrNull()
}

private fun cfiForOffset(loader: ChapterLoader, chapterId: String, offset: Int): String? {
    val descriptors = loader.descriptors()
    val order = descriptors.indexOfFirst { it.id == chapterId }
    if (order < 0) return null
    val spineIndex = order + 1
    val item = loader.load(chapterId)
    var cursor = 0
    for (block in item.blocks) {
        val end = cursor + block.text.length
        if (offset <= end) {
            val charOffset = (offset - cursor).coerceAtLeast(0)
            return CfiAddressing.toCfi(LayoutPosition(spineIndex, block.elementIndex, charOffset))
        }
        cursor = end + 1
    }
    return CfiAddressing.toCfi(LayoutPosition(spineIndex, -1, 0))
}
```

Make `cfiForLocation` delegate to `cfiForOffset(loader, location.chapterId, location.start)` so start/end cannot drift.

- [x] **Step 5: Store range CFI only for highlights and notes**

In `RoomNoteAnnotationStore.persist` replace the CFI assignment with:

```kotlin
val cfi = if (color == null) {
    ChapterLoaderProgress.cfiForLocation(loader, location)
} else {
    ChapterLoaderProgress.cfiRangeForLocation(loader, location)
} ?: return
```

In `isHighlighted`, replace the `compare(...) >= 0` block with:

```kotlin
return highlights.any { entity ->
    entity.cfi?.let { stored -> NativeHighlightMatcher.matches(selectionCfi, stored) } == true
}
```

The selection CFI used for native annotation lookup must come from `cfiRangeForLocation`; update that call accordingly.

- [x] **Step 6: Use the same matcher in NativeEpubScreen and collapse ranges for point consumers**

For native text selection, `currentSelectionCfi()` returns `ChapterLoaderProgress.cfiRangeForLocation(loader, loc).orEmpty()`.

Replace the native branch of `isSelectionHighlighted()` with:

```kotlin
return storedCfis.any { stored -> NativeHighlightMatcher.matches(cfi, stored) }
```

Replace `deleteHighlight()` target lookup with:

```kotlin
val target = notes.firstOrNull { note ->
    note.cfi?.let { stored -> NativeHighlightMatcher.matches(cfi, stored) } == true
}
```

At native-only `CfiAddressing.fromCfi` consumers in `NativeNoteHighlights` and `NativeEpubScreen`, use:

```kotlin
val point = NativeHighlightMatcher.startPointOrNull(cfi) ?: return@mapNotNull null
val pos = CfiAddressing.fromCfi(point, layout) ?: return@mapNotNull null
```

In `jumpToCfi`, retain the original range for Foliate. For native branches use `NativeHighlightMatcher.startPointOrNull(cfi) ?: return`, then pass that point to `initialLocation` and `resumePage`.

- [x] **Step 7: Run annotation and reader regressions**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.reader.NativeHighlightMatcherTest" --tests "com.koodoreader.reader.reader.ChapterLoaderProgressTest" --tests "com.koodoreader.reader.reader.TextReaderViewModelTest" --tests "com.koodoreader.reader.shell.HighlightReAnchorTest" :app:compileNativeHostDebugKotlin -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL.

- [x] **Step 8: Commit Task 2**

```bash
git add android/app/src/main/java/com/koodoreader/reader/reader/NativeHighlightMatcher.kt android/app/src/test/java/com/koodoreader/reader/reader/NativeHighlightMatcherTest.kt android/app/src/main/java/com/koodoreader/reader/reader/ChapterLoaderProgress.kt android/app/src/main/java/com/koodoreader/reader/reader/RoomNoteAnnotationStore.kt android/app/src/main/java/com/koodoreader/reader/reader/NativeNoteHighlights.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/test/java/com/koodoreader/reader/reader/ChapterLoaderProgressTest.kt
git commit -m "fix(android): match native highlights by exact range"
```

---

### Task 3: Make zero the default tracking and support negative native tracking

**Files:**
- Modify: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/CjkTypography.kt`
- Modify: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/LayoutEngine.kt`
- Modify: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/LayoutTokens.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt`
- Test: `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/CjkTypographyTest.kt`
- Test: `android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/LayoutEngineTest.kt`
- Test: `android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/LayoutTokensTest.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt`

**Interfaces:**
- Preserves: `ReaderTypography.letterSpacingPx(density, fontScale, extraSp): Float`.
- Changes validation to finite tracking with `fontSizePx + letterSpacingPx > 0`; no new public setting.

- [x] **Step 1: Write failing zero/negative tracking tests**

Add to `LayoutEngineTest`:

```kotlin
@Test
fun `negative tracking is accepted while the cell advance stays positive`() {
    val options = PaginatorOptions(fontSizePx = 17f, letterSpacingPx = -0.3f)
    assertEquals(-0.3f, options.letterSpacingPx, EPS)
}

@Test
fun `tracking that consumes the font advance is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
        PaginatorOptions(fontSizePx = 0.2f, letterSpacingPx = -0.2f)
    }
}
```

Import `org.junit.jupiter.api.Assertions.assertThrows` in `LayoutEngineTest`, matching its existing JUnit 5 tests.

Change the CJK typography default assertion to `assertEquals(0f, CjkTypography.LETTER_SPACING_SP)`. Change default reader CSS assertions so no `letter-spacing` declaration is emitted at default state; `letterSpacingSp = 0.1f` emits `0.1px`, and native options at density 3 produce `0.3px`.

- [x] **Step 2: Confirm the new tests fail**

Run: `cd android && ./gradlew :core:designsystem:test --tests "com.koodoreader.core.designsystem.CjkTypographyTest" :engine:layout:test --tests "com.koodoreader.engine.layout.LayoutEngineTest" :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.shell.ReaderFeatureStateTest" -Ptarget=native --console=plain`
Expected: FAIL on default `0.5f`, negative tracking rejection and old CSS assertions.

- [x] **Step 3: Implement the new default and validation**

Set:

```kotlin
const val LETTER_SPACING_SP = 0f
```

In `PaginatorOptions.init` and `LayoutTokens.init` replace the non-negative requirement with:

```kotlin
require(letterSpacingPx.isFinite()) { "letterSpacingPx must be finite" }
require(fontSizePx + letterSpacingPx > 0f) {
    "fontSizePx + letterSpacingPx must be > 0"
}
```

For desktop-config parsing, preserve compatibility with existing non-negative desktop values and permit the Android negative range without allowing the advance to collapse:

```kotlin
fun letterSpacingOf(value: Any?): Float =
    floatOf(value, LETTER_SPACING_DEFAULT).coerceIn(-0.1f, LETTER_SPACING_MAX)
```

Update KDoc and tests that previously asserted negative tracking always throws; the remaining failure case is a non-positive advance.

- [x] **Step 4: Keep column quantisation safe**

`CjkColumn.fit` already returns a degenerate fit when `fontSizePx + letterSpacingPx <= 0`; keep that guard. In `ReaderTypography.paginatorOptions`, pass the clamped setting unchanged and do not introduce a second clamp. `AndroidTextMeasurer.runWidth` and `TextLineRenderer.drawLine` already apply signed tracking between characters; leave their formulas unchanged.

- [x] **Step 5: Run typography regressions**

Run the Step 2 command. Expected: BUILD SUCCESSFUL.

Run: `cd android && ./gradlew :engine:layout:test :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.shell.ReaderFeatureStateTest" -Ptarget=native --console=plain`
Expected: all tests pass.

- [x] **Step 6: Commit Task 3**

```bash
git add android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/CjkTypography.kt android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/CjkTypographyTest.kt android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/LayoutEngine.kt android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/LayoutTokens.kt android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/LayoutEngineTest.kt android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/LayoutTokensTest.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt
git commit -m "fix(android): support signed tracking with a zero default"
```

---

### Task 4: Apply per-block CJK widow/orphan control in native pagination

**Files:**
- Create: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/ReaderScript.kt`
- Create: `android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/ReaderScriptTest.kt`
- Modify: `android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/LayoutEngine.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt`
- Test: `android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/LayoutEngineTest.kt`

**Interfaces:**
- Produces: `ReaderScript.widowsOrphans(text: String, fallback: Int): Int`.
- Produces: `PaginatorOptions.languageAwareWidowsOrphans: Boolean = false`.
- Android `ReaderTypography.paginatorOptions()` sets the flag to true; generic engine callers retain old behavior by default.

- [x] **Step 1: Write failing script-classification tests**

```kotlin
package com.koodoreader.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderScriptTest {
    @Test
    fun `cjk and balanced mixed text use one line`() {
        assertEquals(1, ReaderScript.widowsOrphans("中文段落", 2))
        assertEquals(1, ReaderScript.widowsOrphans("中文 ab", 2))
        assertEquals(1, ReaderScript.widowsOrphans("かな 한글", 2))
    }

    @Test
    fun `latin dominant and non letters keep fallback`() {
        assertEquals(2, ReaderScript.widowsOrphans("English with 中", 2))
        assertEquals(2, ReaderScript.widowsOrphans("1234", 2))
        assertEquals(2, ReaderScript.widowsOrphans("", 2))
    }
}
```

Run: `cd android && ./gradlew :engine:layout:test --tests "com.koodoreader.engine.layout.ReaderScriptTest" --console=plain`
Expected: FAIL, unresolved `ReaderScript`.

- [x] **Step 2: Implement deterministic script classification**

```kotlin
package com.koodoreader.engine.layout

object ReaderScript {
    fun widowsOrphans(text: String, fallback: Int): Int {
        var cjk = 0
        var latin = 0
        for (ch in text) {
            when {
                isCjk(ch) -> cjk++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }
        return if (cjk > 0 && cjk >= latin) 1 else fallback
    }

    private fun isCjk(ch: Char): Boolean = when {
        ch in '\u3400'..'\u4DBF' -> true
        ch in '\u4E00'..'\u9FFF' -> true
        ch in '\uF900'..'\uFAFF' -> true
        ch in '\u3040'..'\u30FF' -> true
        ch in '\uAC00'..'\uD7A3' -> true
        else -> false
    }
}
```

- [x] **Step 3: Add a failing paginator behavior test**

Construct the same five-line fixture as the existing widow test, using five CJK lines, and compare:

```kotlin
@Test
fun `language aware cjk pagination allows a single trailing line`() {
    val fiveLines = "甲".repeat(40)
    val result = LayoutEngine(
        TextMeasurers.mono(),
        PaginatorOptions(languageAwareWidowsOrphans = true),
    ).layout(doc(TextBlock.of(fiveLines, elementIndex = 0)), tokens())
    assertEquals(2, result.pageCount)
    assertEquals(4, result.pages[0].lines.size)
    assertEquals(1, result.pages[1].lines.size)
}
```

Run this test before implementation. Expected: FAIL because the flag is absent or old behavior produces `3 + 2`.

- [x] **Step 4: Resolve the limit once per block**

Add `val languageAwareWidowsOrphans: Boolean = false` to `PaginatorOptions`. Immediately after `val block = flatBlock.block` in `LayoutEngine.layout`, compute:

```kotlin
val blockWidowsOrphans = if (options.languageAwareWidowsOrphans) {
    ReaderScript.widowsOrphans(block.text, options.widowsOrphans)
} else {
    options.widowsOrphans
}
```

Use `blockWidowsOrphans` at the three existing orphan/widow decisions. Set `languageAwareWidowsOrphans = true` in `ReaderTypography.paginatorOptions`. `FlatBlock.block` is the existing `TextBlock`; do not add a duplicate text field.

- [x] **Step 5: Run native layout regressions**

Run: `cd android && ./gradlew :engine:layout:test :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.shell.ReaderFeatureStateTest" -Ptarget=native --console=plain`
Expected: all tests pass, including existing Latin `2/2` cases.

- [x] **Step 6: Commit Task 4**

```bash
git add android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/ReaderScript.kt android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/ReaderScriptTest.kt android/engine/layout/src/main/kotlin/com/koodoreader/engine/layout/LayoutEngine.kt android/engine/layout/src/test/kotlin/com/koodoreader/engine/layout/LayoutEngineTest.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt
git commit -m "feat(android): relax native widow control for cjk"
```

---

### Task 5: Quantise real Foliate single-page and spread geometry

**Files:**
- Modify: `android/app/src/main/assets/foliate/paginator.js`
- Modify: `android/app/src/main/assets/foliate/engine.mjs`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/ReadestViewSettings.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateHostBridge.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `scripts/test-foliate-host.js`
- Test: `android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt`

**Interfaces:**
- Extends: `ReadestViewSettings.Snapshot` with `letterSpacingPx: Double`.
- Extends: `getHorizontalColumnMetrics` input with `cellFontSize: number` and `cellLetterSpacing: number`.
- Preserves returned keys `columnGap`, `sidePaddingLeft`, `sidePaddingRight`, `columnWidth`, `availableWidth`.
- Removes Compose `ReaderCjkColumn.foliateSidePadFor` consumption; the Kotlin helper remains for compatibility tests.

- [x] **Step 1: Add failing real-paginator geometry tests**

In `scripts/test-foliate-host.js`, import `getHorizontalColumnMetrics` with minimal browser globals:

```javascript
globalThis.HTMLElement = class {};
globalThis.customElements = { define: () => {} };
const PAGINATOR_JS = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'paginator.js');
const { getHorizontalColumnMetrics } = await import(pathToFileURL(PAGINATOR_JS).href);
delete globalThis.customElements;
delete globalThis.HTMLElement;
```

Add these assertions:

```javascript
const single = getHorizontalColumnMetrics({
    width: 360, marginLeft: 16, marginRight: 16, gap: 18,
    columnWidth: 360, columnCount: 1, columnGap: null,
    cellFontSize: 17, cellLetterSpacing: 0,
});
eq('single page available width is a whole CJK run', single.availableWidth % 17, 0);
check('single page side padding stays finite', Number.isFinite(single.sidePaddingLeft) && Number.isFinite(single.sidePaddingRight));

const spread = getHorizontalColumnMetrics({
    width: 720, marginLeft: 32, marginRight: 32, gap: 36,
    columnWidth: 360, columnCount: 2, columnGap: null,
    cellFontSize: 17, cellLetterSpacing: 0,
});
eq('spread column available width is a whole CJK run', spread.availableWidth % 17, 0);
check('spread keeps two finite columns', spread.columnWidth > 0 && spread.columnGap > 0);

const tracked = getHorizontalColumnMetrics({
    width: 360, marginLeft: 16, marginRight: 16, gap: 18,
    columnWidth: 360, columnCount: 1, columnGap: null,
    cellFontSize: 17, cellLetterSpacing: -0.1,
});
check('signed tracking keeps a positive advance', tracked.availableWidth > 0 && Number.isFinite(tracked.availableWidth));

const degenerate = getHorizontalColumnMetrics({
    width: 360, marginLeft: 16, marginRight: 16, gap: 18,
    columnWidth: 360, columnCount: 1, columnGap: null,
    cellFontSize: 0, cellLetterSpacing: 0,
});
eq('degenerate cell metrics preserve the original width', degenerate.availableWidth, 326);
```

Run with Node >=20. Expected: FAIL because cell inputs are ignored and spread is not quantised.

- [x] **Step 2: Implement one pure geometry quantiser in paginator.js**

Add this helper beside `getHorizontalColumnMetrics`:

```javascript
const quantizeColumnMetrics = (metrics, fontSize, letterSpacing) => {
    if (!Number.isFinite(fontSize) || !Number.isFinite(letterSpacing)) return metrics;
    const cell = fontSize + letterSpacing;
    if (!(fontSize > 0) || !(cell > 0)) return metrics;
    const available = metrics.availableWidth;
    if (!Number.isFinite(available) || available <= 0) return metrics;
    const cells = Math.floor((available + letterSpacing) / cell);
    if (cells < 4) return metrics;
    const width = cells * fontSize + Math.max(0, cells - 1) * letterSpacing;
    if (!Number.isFinite(width) || width <= 0 || width > available) return metrics;
    const extra = available - width;
    return {
        ...metrics,
        columnGap: metrics.columnGap + extra,
        sidePaddingLeft: metrics.sidePaddingLeft + extra / 2,
        sidePaddingRight: metrics.sidePaddingRight + extra / 2,
        columnWidth: width,
        availableWidth: width,
    };
};
```

Compute the existing raw metrics first, then return `quantizeColumnMetrics(raw, cellFontSize, cellLetterSpacing)` from every branch. Apply quantisation only for horizontal, paginated layouts; scrolled and vertical flows never pass cell metrics.

- [x] **Step 3: Pass cell metrics through the real paginator layout**

Add `cell-font-size` and `cell-letter-spacing` to `observedAttributes`, and handle them in `attributeChangedCallback` by calling `this.render()`.

In `#beforeRender`, read finite values and include them in `layout`:

```javascript
const cellFontSize = Number.parseFloat(this.getAttribute('cell-font-size'));
const cellLetterSpacing = Number.parseFloat(this.getAttribute('cell-letter-spacing'));
```

Add both to the `layout` object. Extend `columnize` destructuring with `cellFontSize, cellLetterSpacing` and pass them to `getHorizontalColumnMetrics` only when `!vertical`.

- [x] **Step 4: Send actual body font and tracking from Kotlin**

Add `val letterSpacingPx: Double = 0.0` as the last `ReadestViewSettings.Snapshot` property and populate it in `from` with:

```kotlin
letterSpacingPx = ReaderFeatureCodec.letterSpacingCssPx(features).toDouble(),
```

In `FoliateHostBridge.setViewSettings`, add the JSON bridge field:

```kotlin
.put("letterSpacingPx", snapshot.letterSpacingPx)
```

In `engine.mjs#setViewSettings`, derive the body size using the same Android factor as `readest-style.mjs`:

```javascript
const cellFontSize = Number(appearance.defaultFontSize || 13.6) * 1.25;
const cellLetterSpacing = Number(appearance.letterSpacingPx || 0);
```

Set both renderer attributes after `gap`:

```javascript
view.renderer.setAttribute('cell-font-size', String(cellFontSize));
view.renderer.setAttribute('cell-letter-spacing', String(cellLetterSpacing));
```

Add a `ReadestViewSettingsTest` assertion that negative and positive settings round-trip into the snapshot.

- [x] **Step 5: Remove host-side Foliate padding compensation**

Delete the `cjkSidePad` calculation in `NativeEpubScreen` and restore the AndroidView padding to:

```kotlin
.padding(
    start = contentInsets.left,
    end = contentInsets.right,
    top = contentInsets.top,
    bottom = contentInsets.bottom,
)
```

Do not remove `contentInsets` or the `onGloballyPositioned` selection bounds callback. Remove only now-unused `ReaderCjkColumn` imports from this file.

- [x] **Step 6: Run geometry and host regressions**

Run: `export PATH="$HOME/AppData/Roaming/nvm/v20.19.2:$PATH" && node scripts/test-foliate-host.js --quiet`
Expected: all checks pass, including single, spread, signed tracking and degenerate geometry.

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.epubhost.ReadestViewSettingsTest" :core:designsystem:test --tests "com.koodoreader.core.designsystem.ReaderCjkColumnTest" :app:compileNativeHostDebugKotlin -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL.

- [x] **Step 7: Commit Task 5**

```bash
git add android/app/src/main/assets/foliate/paginator.js android/app/src/main/assets/foliate/engine.mjs android/app/src/main/java/com/koodoreader/reader/epubhost/ReadestViewSettings.kt android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateHostBridge.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt scripts/test-foliate-host.js
git commit -m "feat(android): quantise foliate single and spread columns"
```

---

### Task 6: Full regression, release checks and truthful documentation closeout

**Files:**
- Modify: `docs/android-uiux-upgrade-2026-11-19.md`
- Modify: `docs/superpowers/plans/2026-11-22-android-p0-p1-remainder.md`
- Modify: `docs/superpowers/plans/2026-11-25-android-plan-closeout.md`

**Interfaces:**
- Consumes all previous task outputs; no production API changes.

- [x] **Step 1: Run all JS and desktop regressions**

Run:

```bash
export PATH="$HOME/AppData/Roaming/nvm/v20.19.2:$PATH"
node scripts/check-dict-catalog.js
node scripts/test-foliate-host.js --quiet
node scripts/test-pdfengine.js
CI=true node node_modules/react-scripts/bin/react-scripts.js test --watchAll=false --runInBand
```

Expected: dictionary catalog OK; every Foliate/PDF check passes; 18 desktop suites pass with no failed tests.

- [x] **Step 2: Run Android unit tests and Debug build**

Run:

```bash
cd android
./gradlew :benchmarks:compileBenchmarkKotlin :core:designsystem:test :core:designsystem:selfCheck :engine:annotate:test :engine:layout:test :core:importer:test :core:ui:testDebugUnitTest :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native --console=plain
```

Expected: BUILD SUCCESSFUL and zero failed tests.

- [x] **Step 3: Verify Release compilation and merged permission boundary**

Run: `cd android && ./gradlew :app:assembleNativeHostRelease :app:processNativeHostReleaseMainManifest -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native --console=plain`
Expected: BUILD SUCCESSFUL.

Result (2026-11-25, after the release compile fix `fix(android): compile release without the debug icon class`): **BUILD SUCCESSFUL in 4m 40s** — both `:app:assembleNativeHostRelease` (including `:app:compileNativeHostReleaseKotlin`) and `:app:processNativeHostReleaseMainManifest` pass. The earlier failure (`DoKitInitializer.kt:8`/`:82` referenced `MainIconDoKitView`, absent from the release `dokitx-no-op:3.5.0` artifact; pre-existing from `9bedf9a9`, unrelated to Tasks 1–6) was fixed by removing the compile-time reference: the icon class name is a constant resolved via `Class.forName` and `launchFloating` is dispatched reflectively, with the `enabledProvider` guard unchanged so release still never calls DoKit. `:app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.debug.DoKitInitializerTest"` → 3/3 pass.

Read `android/app/build/intermediates/merged_manifest/nativeHostRelease/AndroidManifest.xml`.

Expected: no `android.permission.SYSTEM_ALERT_WINDOW` and no DoKit component in the release merged manifest. Debug continues to declare the permission only in `src/debug/AndroidManifest.xml`.

Confirmed (2026-11-25): the merged `nativeHostRelease` manifest declares only INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE (+`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) and the app's own dynamic-receiver permission — no `SYSTEM_ALERT_WINDOW`, no DoKit component.

- [x] **Step 4: Update implementation records**

In `docs/android-uiux-upgrade-2026-11-19.md` add a 2026-11-25 closeout section containing:

- selection adjustment mode and collapsed-range guard;
- exact native point/range matching;
- default zero and signed tracking;
- per-block CJK `1/1` versus Latin `2/2`;
- paginator-internal single/spread quantisation;
- exact final test and build results.

Correct the earlier `0.5sp → 0` record only if needed to distinguish the old false record from the actual 2026-11-25 implementation. Keep the existing device-run history intact.

In the remainder plan and this plan, mark only completed steps. Leave these explicitly unchecked/open: actual device column measurement, haptic feel, PDF device behavior, highlight re-anchor device cases, backup device round-trip and performance baseline values.

- [x] **Step 5: Check the final diff and generated files**

Run: `git status --short && git diff --check`
Expected: only intentional source/locale/test/docs changes before the final commit; no whitespace errors. Delete only temporary files created by this execution, not pre-existing user files.

- [x] **Step 6: Commit documentation closeout**

```bash
git add docs/android-uiux-upgrade-2026-11-19.md docs/superpowers/plans/2026-11-22-android-p0-p1-remainder.md docs/superpowers/plans/2026-11-25-android-plan-closeout.md
git commit -m "docs(android): record automated plan closeout"
```

---

## Execution Notes

- If a task exposes a pre-existing unrelated failure, record it and keep the task open until its own covering tests are green; do not rewrite unrelated subsystems.
- Do not mark the whole Android plan complete solely because JVM/JS tests pass. The final state is “automatically verifiable scope complete; device acceptance pending” until a real device run supplies the remaining evidence.

---

## Status (2026-11-25 closeout execution)

- Tasks 1–5 complete and committed: `16a75dae`, `3465915d`, `16dc7c2f` + `07ca6f94`, `6d91f9d3`, `b8d2b455`.
- Task 6: Steps 1–3 green — desktop `react-scripts test` 18/18 suites, 222/222 tests; Android Gradle unit suites 1056 tests across `:core:designsystem`(179), `:engine:annotate`(128), `:engine:layout`(228), `:core:importer`(110), `:core:ui`(3), `:app` nativeHostDebug(408), 0 failures; `:app:assembleNativeHostDebug` BUILD SUCCESSFUL. Step 3 closed 2026-11-25 after the release compile fix: `:app:assembleNativeHostRelease` + `:app:processNativeHostReleaseMainManifest` BUILD SUCCESSFUL (4m 40s), the merged `nativeHostRelease` manifest still contains no `SYSTEM_ALERT_WINDOW` and no DoKit component, and `DoKitInitializerTest` passes 3/3 (see the Step 3 annotation above and `docs/android-uiux-upgrade-2026-11-19.md` §5.11). Steps 4–6 executed with this documentation closeout.
- Still open (device acceptance pending): actual device column measurement, haptic feel, PDF device behavior, highlight re-anchor device cases, backup device round-trip, performance baseline values.
