# P0/P1 剩余项实施计划（不含真机部分）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/android-uiux-upgrade-2026-11-19.md` §3.5 执行清单中剩余的 1 项 P0（P0-2 WebView 轨收尾）和 4 项 P1（#10 页边标注、#12 选词菜单可配置、#13 滑杆离散化+触感+字距、#14 书架卡片分档）。

**Architecture:** 全部沿用既有分层：排版决策进 `core/designsystem`（纯 JVM 可测），设置状态进 `ReaderFeatureState`/codec（SharedPreferences 持久化），UI 接线在 `:app` 各 Screen。每个 Task 独立可测、独立提交。

**Tech Stack:** Kotlin / Jetpack Compose / Groovy Gradle / org.json（既有 testImplementation）。

## Global Constraints

- 工作目录 `E:/open-source/koodo-reader`，就地工作在 `dev` 分支（基线 `b738e30c`），允许每 Task 一提交；commit 信息 `feat(android): …` / `fix(android): …` 英文 subject。
- **本计划不含任何真机步骤**。真机验证统一列入文末附录清单，后续设备可用时单独执行。
- 不新增第三方依赖；不改动第三轮沉浸化链路（`ReaderChrome`/`ReaderSystemBars`）的既有契约。
- 所有新用户可见文案走 `t("key")` + `src/assets/locales/en.json` + `zh-CN.json` + `node scripts/sync-locales-android.js`（字节级拷贝，android 资产不接受源外 key）。
- 设置项一律按既有模式：`ReaderFeatureState.kt` 加字段 + `KEY_*` 常量 + codec 往返（参考 `KEY_PARAGRAPH_SPACING` / `KEY_PDF_CROP` 的完整路径）。
- 凡 JVM 可测的逻辑必须抽纯函数放 `reader/shell/` 或 `core/designsystem/` 并配单测；Compose 层只做接线。
- 已知事实（不要重复实现）：native 文本轨列宽量化**已接线**——`ReaderTypography.paginatorOptions()` 已设 `quantizeColumnToCells = true`（`ReaderTypography.kt:132`），`PagedDocumentSession.kt:222` 与 `NativeTextSession.kt:46` 已透传，`engine:layout` 有 `CjkColumnQuantisationTest`。P0-2 只剩 WebView(foliate) 轨。

## 背景速览（来源：android-uiux-upgrade-2026-11-19.md §3.5）

| 清单项 | 现状 | 本计划 Task |
|---|---|---|
| P0-2 列宽量化接两轨 | native 轨已通；WebView 轨未接 | Task 1 |
| P1-13 滑杆离散化+触感 | 滑杆连续（行距 steps=9），无触感、无字距设置 | Task 2 |
| P1-12 划词菜单动作可配置 | 菜单动作硬编码（NativeEpubScreen.kt:2118-2191） | Task 3 |
| P1-14 书架卡片分档 | 网格/列表卡片无尺寸分档 | Task 4 |
| P1-10 页边标注 | 未启动 | Task 5 |

---

### Task 1: P0-2 收尾——WebView 轨列宽量化接缝

**Files:**
- Create: `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/ReaderCjkColumnTest.kt`
- Modify: `android/core/designsystem/src/main/kotlin/com/koodoreader/core/designsystem/CjkColumn.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`（`contentInsets` 应用点，约 245 行起）

**Interfaces:**
- Consumes: `CjkColumn.fit(availablePx, fontSizePx, letterSpacingPx, minCells): CjkColumn.Fit`（已存在）
- Produces: `ReaderCjkColumn.sidePadFor(contentWidthPx, fontSizePx, letterSpacingPx): Float`——纯函数，后续 Task 均不依赖

- [x] **Step 1: 先写失败测试**

Create `android/core/designsystem/src/test/kotlin/com/koodoreader/core/designsystem/ReaderCjkColumnTest.kt`：

```kotlin
package com.koodoreader.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCjkColumnTest {

    @Test
    fun `side pad centres the quantised column`() {
        // available=328, font=17, tracking=0 → 19 cells = 323, leftover 5 split 2.5/2.5
        val pad = ReaderCjkColumn.sidePadFor(328f, 17f, 0f)
        assertEquals(2.5f, pad, 0.001f)
    }

    @Test
    fun `zero when the column already fits exactly`() {
        assertEquals(0f, ReaderCjkColumn.sidePadFor(323f, 17f, 0f), 0.001f)
    }

    @Test
    fun `degenerate input yields zero pad, never NaN`() {
        assertEquals(0f, ReaderCjkColumn.sidePadFor(0f, 17f, 0f), 0.001f)
        assertEquals(0f, ReaderCjkColumn.sidePadFor(Float.NaN, 17f, 0f), 0.001f)
        assertEquals(0f, ReaderCjkColumn.sidePadFor(328f, 0f, 0f), 0.001f)
    }

    @Test
    fun `tracking shrinks the cell count correctly`() {
        // cell = 17 + 0.5 = 17.5; floor((328 + 0.5) / 17.5) = 18 cells
        // width = 18*17 + 17*0.5 = 314.5; pad = (328 - 314.5)/2 = 6.75
        val pad = ReaderCjkColumn.sidePadFor(328f, 17f, 0.5f)
        assertTrue(pad in 6.7f..6.8f)
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew :core:designsystem:test --tests "com.koodoreader.core.designsystem.ReaderCjkColumnTest" --console=plain`
Expected: 编译失败 `Unresolved reference: ReaderCjkColumn`

- [x] **Step 3: 实现纯函数**

在 `CjkColumn.kt` 末尾（`object CjkColumn` 内）追加：

```kotlin
    /**
     * Extra padding for *each* side of a WebView-track page so the text column
     * is a whole number of character cells (see [fit]). Returns 0 for
     * degenerate input — never NaN — and is a no-op wrapper so both tracks
     * share one quantisation entry point.
     */
    object ReaderCjkColumn {
        fun sidePadFor(
            contentWidthPx: Float,
            fontSizePx: Float,
            letterSpacingPx: Float = 0f,
        ): Float = CjkColumn.fit(
            availablePx = contentWidthPx,
            fontSizePx = fontSizePx,
            letterSpacingPx = letterSpacingPx,
        ).sidePadPx
    }
```

（`object ReaderCjkColumn` 嵌套在 `object CjkColumn` 内，测试引用路径 `CjkColumn.ReaderCjkColumn`；为简洁允许顶层引用 `ReaderCjkColumn`——若选择嵌套，把测试中的引用改为 `CjkColumn.ReaderCjkColumn`。**推荐直接建成顶层 object**（与 `CjkStyleSheet` 平级），上面测试无需改。）

顶层版本（推荐，放在 `CjkColumn.kt` 文件末尾）：

```kotlin
/** WebView-track entry point — see [CjkColumn.fit]. Never NaN. */
object ReaderCjkColumn {
    fun sidePadFor(
        contentWidthPx: Float,
        fontSizePx: Float,
        letterSpacingPx: Float = 0f,
    ): Float = CjkColumn.fit(
        availablePx = contentWidthPx,
        fontSizePx = fontSizePx,
        letterSpacingPx = letterSpacingPx,
    ).sidePadPx
}
```

- [x] **Step 4: 跑测试确认通过**

Run: 同 Step 2
Expected: 4 tests PASSED

- [x] **Step 5: 接入 NativeEpubScreen 的 WebView 容器 padding**

在 `NativeEpubScreen.kt` 中，`val contentInsets = rememberReaderContentInsets()`（245 行）之后计算量化边距，并把 `sidePad` 加到 WebView 容器的左右 padding 上：

```kotlin
    val contentInsets = rememberReaderContentInsets()
    // P0-2 (WebView track): quantise the foliate column to a whole number of
    // character cells. sidePad is symmetric and depends only on viewport width
    // and the body font, so — like the chrome insets — it never changes with
    // toolbar visibility and cannot trigger a re-pagination flash.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val cjkSidePadPx = remember(configuration.screenWidthDp, features.fontScale) {
        val viewportPx = configuration.screenWidthDp * density.density
        val marginsPx = features.marginHorizontalPx.coerceIn(
            ReaderFeatureState.MARGIN_MIN, ReaderFeatureState.MARGIN_MAX,
        ) * density.density
        val fontPx = ReaderFeatureState.FONT_SIZE_DEFAULT * features.fontScale
        ReaderCjkColumn.sidePadFor(viewportPx - 2f * marginsPx, fontPx)
    }
```

（缺什么 import 补什么：`android.content.res.LocalConfiguration`、`androidx.compose.ui.platform.LocalDensity`、`com.koodoreader.core.designsystem.ReaderCjkColumn`。`features.marginHorizontalPx` 的实际字段名以 `ReaderFeatureState` 为准，接入时核对一次。）

找到 WebView 容器应用 `contentInsets` 的 Box（round-3 已改为忽略 Scaffold padding 的那个），把左右 padding 从 `contentInsets.left/right` 改为 `contentInsets.left + cjkSidePadPx`（right 同理）。**只改 foliate WebView 容器**；native 文本轨无需改（引擎内已量化）。

- [x] **Step 6: 编译验证**

Run: `cd android && ./gradlew :app:compileNativeHostDebugKotlin :core:designsystem:test -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL

- [x] **Step 7: Commit**

```bash
git add android/core/designsystem/ android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt
git commit -m "feat(android): quantise foliate column width to whole CJK cells"
```

---

### Task 2: P1-13 滑杆离散化 + 触感 + 字距设置

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt`（常量、字段、codec、snap 函数）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt`（userStylesheet / paginatorOptions 透传字距）
- Modify: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderControlCenterSections.kt`（滑杆接线 + 触感）
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt`（追加）

**Interfaces:**
- Produces: `ReaderFeatureState.letterSpacingSp: Float`（默认 0，范围 ±0.1）；`ReaderFeatureCodec.snappedLineHeight(v)`；`KEY_LETTER_SPACING = "letterSpacingSp"`

- [x] **Step 1: 失败测试（codec 往返 + snap）**

在 `ReaderFeatureStateTest.kt` 追加：

```kotlin
    @Test
    fun `letter spacing round trips through the codec`() {
        val state = ReaderFeatureState(letterSpacingSp = 0.05f)
        val restored = ReaderFeatureCodec.decode(ReaderFeatureCodec.encode(state))
        assertEquals(0.05f, restored.letterSpacingSp, 0.001f)
    }

    @Test
    fun `letter spacing clamps to the supported range`() {
        val state = ReaderFeatureState(letterSpacingSp = 0.4f)
        assertEquals(
            ReaderFeatureCodec.LETTER_SPACING_MAX,
            ReaderFeatureCodec.clampedLetterSpacing(state),
        )
    }

    @Test
    fun `line height snaps to the 0_05 grid`() {
        assertEquals(1.15f, ReaderFeatureCodec.snappedLineHeight(1.13f), 0.001f)
        assertEquals(1.20f, ReaderFeatureCodec.snappedLineHeight(1.22f), 0.001f)
        assertEquals(ReaderFeatureCodec.LINE_HEIGHT_MIN, ReaderFeatureCodec.snappedLineHeight(0.5f))
        assertEquals(ReaderFeatureCodec.LINE_HEIGHT_MAX, ReaderFeatureCodec.snappedLineHeight(9f))
    }
```

Run: `./gradlew :app:testNativeHostDebugUnitTest --tests "com.koodoreader.reader.shell.ReaderFeatureStateTest" -Ptarget=native`
Expected: 编译失败（`letterSpacingSp` 不存在）

- [x] **Step 2: 实现**

`ReaderFeatureState.kt`：
- 常量区（`LINE_HEIGHT_MAX = 2f` 之后）加：

```kotlin
    const val LINE_HEIGHT_STEP = 0.05f
    const val LETTER_SPACING_MIN = -0.1f
    const val LETTER_SPACING_MAX = 0.1f
    const val LETTER_SPACING_STEP = 0.01f
```

- `KEY_*` 区加 `const val KEY_LETTER_SPACING = "letterSpacingSp"`。
- `data class ReaderFeatureState` 加字段 `val letterSpacingSp: Float = 0f`（放在 `lineHeightRatio` 之后）。
- codec 的 encode/decode 各加一行（镜像 `KEY_PARAGRAPH_SPACING` 的写法）：`put(KEY_LETTER_SPACING, state.letterSpacingSp)` / `letterSpacingSp = obj.optDouble(KEY_LETTER_SPACING, 0.0).toFloat()`（以 codec 现有实际风格为准）。
- `object ReaderFeatureCodec` 加：

```kotlin
    fun clampedLetterSpacing(state: ReaderFeatureState): Float =
        state.letterSpacingSp.coerceIn(LETTER_SPACING_MIN, LETTER_SPACING_MAX)

    fun snappedLineHeight(value: Float): Float =
        (value / LINE_HEIGHT_STEP).roundToInt() * LINE_HEIGHT_STEP
```

并把既有的 lineHeightRatio clamp 点（约 235 行 `state.lineHeightRatio.coerceIn(...)`）改为 `snappedLineHeight(state.lineHeightRatio.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX))`。（`roundToInt` 需要 `import kotlin.math.roundToInt`。）

`ReaderTypography.kt`：
- `userStylesheet(...)` 签名加 `letterSpacingSp: Float = 0f`，`letterSpacingPx` 行改为：

```kotlin
            letterSpacingPx = (CjkTypography.LETTER_SPACING_SP + letterSpacingSp.coerceIn(
                ReaderFeatureState.LETTER_SPACING_MIN, ReaderFeatureState.LETTER_SPACING_MAX,
            )) * fontScale.coerceAtLeast(0.5f),
```

- `paginatorOptions(...)` 同样加 `letterSpacingSp: Float = 0f`，`letterSpacingPx = letterSpacingPx(density, fontScale)` 改为把 setting 并入（`CjkTypography.letterSpacingPx` 是常量式函数——在这里直接内联计算：`((CjkTypography.LETTER_SPACING_SP + letterSpacingSp) * fontScale.coerceAtLeast(0.5f)) * density`，与 native 轨 dp→px 归一保持一致）。
- 两个调用点（`ReadestViewSettings.kt:48` 的 `ReaderFeatureCodec.userStylesheet(features, firstLineIndent, density)` 与 `NativePdfScreen.kt:297` 的 reflowCss，及 `NativeEpubScreen.kt:531/1502` 的 paginatorOptions）透传 `features.letterSpacingSp`。

- [x] **Step 3: 测试通过**

Run: 同 Step 1 命令
Expected: 全部 PASSED

- [x] **Step 4: 控制中心滑杆接线（离散化 + 触感 + 字距滑杆）**

`ReaderControlCenterSections.kt` 的 Layout/Appearance 区：
- 文件头加 `import androidx.compose.ui.hapticfeedback.HapticFeedbackType` 与 `import androidx.compose.ui.platform.LocalHapticFeedback`。
- 行距滑杆（约 128-134 行）改为：

```kotlin
                val haptics = LocalHapticFeedback.current
                Slider(
                    value = lineHeight,
                    onValueChange = { raw ->
                        val snapped = ReaderFeatureCodec.snappedLineHeight(raw)
                        if (snapped != lineHeight) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onLineHeight(snapped)
                    },
                    valueRange = ReaderFeatureState.LINE_HEIGHT_MIN..ReaderFeatureState.LINE_HEIGHT_MAX,
                    steps = ((ReaderFeatureState.LINE_HEIGHT_MAX - ReaderFeatureState.LINE_HEIGHT_MIN) /
                        ReaderFeatureState.LINE_HEIGHT_STEP).toInt() - 1,
                )
```

- 字号滑杆同样把 `onFontScale` 包一层（snap 到 `FONT_SCALE` 既有刻度，每跨一档给一次触感；刻度值沿用 codec clamp 逻辑）。
- 行距滑杆之后新增字距滑杆（仅 `format != ReaderFormatKind.PDF || pdfReflow` 分支）：

```kotlin
                Text(
                    "${t("Letter spacing")} · ${"%.2f".format(letterSpacingSp)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = letterSpacingSp,
                    onValueChange = { raw ->
                        val snapped = (raw / ReaderFeatureState.LETTER_SPACING_STEP).roundToInt() *
                            ReaderFeatureState.LETTER_SPACING_STEP
                        if (snapped != letterSpacingSp) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onLetterSpacing(snapped)
                    },
                    valueRange = ReaderFeatureState.LETTER_SPACING_MIN..ReaderFeatureState.LETTER_SPACING_MAX,
                    steps = ((ReaderFeatureState.LETTER_SPACING_MAX - ReaderFeatureState.LETTER_SPACING_MIN) /
                        ReaderFeatureState.LETTER_SPACING_STEP).toInt() - 1,
                )
```

- 新文案 key 走 Global Constraints 的 locale 流程：`"Letter spacing": "Letter spacing"`（en）/ `"Letter spacing": "字距"`（zh-CN），sync 后提交。

- [x] **Step 5: 回归**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native --console=plain`
Expected: BUILD SUCCESSFUL

- [x] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt android/app/src/main/java/com/koodoreader/reader/reader/ReaderControlCenterSections.kt android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt src/assets/locales/ android/app/src/main/assets/locales/
git commit -m "feat(android): discrete sliders with haptics and letter-spacing setting"
```

---

### Task 3: P1-12 选词菜单动作可配置

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/SelectionMenuActions.kt`
- Create: `android/app/src/test/java/com/koodoreader/reader/shell/SelectionMenuActionsTest.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`（菜单块 2118-2191）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReadingSettingsRoute.kt`（开关入口，镜像既有区块写法）

**Interfaces:**
- Consumes: `ReaderGesturePrefs` 的 SharedPreferences 模式（同目录，照抄结构）
- Produces: `SelectionMenuAction`（enum）、`SelectionMenuActionPrefs.load/save(context)`；菜单渲染消费 `List<SelectionMenuAction>`

- [x] **Step 1: 失败测试**

`SelectionMenuActionsTest.kt`：

```kotlin
package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionMenuActionsTest {

    @Test
    fun `blank or missing preference yields the default order`() {
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse(null))
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse(""))
    }

    @Test
    fun `saved order round trips`() {
        val order = listOf(
            SelectionMenuAction.DICTIONARY,
            SelectionMenuAction.TRANSLATE,
            SelectionMenuAction.COPY,
        )
        assertEquals(order, SelectionMenuAction.parse(order.joinToString(",") { it.name }))
    }

    @Test
    fun `unknown entries are dropped and empty result falls back to default`() {
        assertEquals(
            listOf(SelectionMenuAction.COPY),
            SelectionMenuAction.parse("COPY,BOGUS"),
        )
        assertEquals(SelectionMenuAction.DEFAULT, SelectionMenuAction.parse("BOGUS1,BOGUS2"))
    }
}
```

Run 确认编译失败。

- [x] **Step 2: 实现 `SelectionMenuActions.kt`**

```kotlin
package com.koodoreader.reader.shell

import android.content.Context

/**
 * Configurable actions of the reader selection menu (P1-12). The colour
 * palette and Delete stay fixed — they are the primary actions; this list
 * covers the secondary row. Stored as a comma-separated name list so the
 * SharedPreferences layer stays string-only like ReaderGesturePrefs.
 */
enum class SelectionMenuAction(val titleKey: String) {
    COPY("Copy"),
    SEARCH("Search"),
    DICTIONARY("Dictionary"),
    TRANSLATE("Translate"),
    NOTE("Note"),
    ;

    companion object {
        val DEFAULT: List<SelectionMenuAction> = listOf(COPY, SEARCH, DICTIONARY, TRANSLATE, NOTE)

        fun parse(raw: String?): List<SelectionMenuAction> {
            if (raw.isNullOrBlank()) return DEFAULT
            val parsed = raw.split(',')
                .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
            return parsed.ifEmpty { DEFAULT }
        }
    }
}

object SelectionMenuActionPrefs {
    private const val PREFS_NAME = "reader_selection_menu"
    private const val KEY_ACTIONS = "actions"

    fun load(context: Context): List<SelectionMenuAction> =
        SelectionMenuAction.parse(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ACTIONS, null),
        )

    fun save(context: Context, actions: List<SelectionMenuAction>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIONS, actions.joinToString(",") { it.name })
            .apply()
    }
}
```

Run 测试确认通过。

- [x] **Step 3: 菜单接线（NativeEpubScreen）**

在 Screen 顶部（`contentInsets` 附近）加载：

```kotlin
    val context = LocalContext.current
    var menuActions by remember { mutableStateOf(SelectionMenuActionPrefs.load(context)) }
```

把 2118-2191 菜单块中"未高亮"第二行（约 2172-2187）与"已高亮"行（约 2143-2156）里的 COPY/SEARCH/DICTIONARY/TRANSLATE 四个硬编码 `TextButton` 替换为：

```kotlin
                    Row(modifier = Modifier.fillMaxWidth()) {
                        menuActions.forEach { action ->
                            TextButton(
                                onClick = { runSelectionAction(action) },
                                modifier = Modifier.koodoPressScale(),
                            ) { Text(t(action.titleKey)) }
                        }
                        TextButton(onClick = { showSelectionMenu = false }, modifier = Modifier.koodoPressScale()) { Text(t("Close")) }
                    }
```

并在 Screen 内新增（复用既有 handler，零行为变化）：

```kotlin
    fun runSelectionAction(action: SelectionMenuAction) {
        when (action) {
            SelectionMenuAction.COPY -> {
                clipboardManager.setText(AnnotatedString(currentSelectionText()))
                showSelectionMenu = false
            }
            SelectionMenuAction.SEARCH -> {
                searchQuery = currentSelectionText()
                showSelectionMenu = false
                showSearch = true
                doSearch()
            }
            SelectionMenuAction.DICTIONARY -> lookupDictionary()
            SelectionMenuAction.TRANSLATE -> startTranslation()
            SelectionMenuAction.NOTE -> { noteText = ""; showNoteDialog = true }
        }
    }
```

（`clipboardManager` 就是菜单块里现有的 `LocalClipboardManager.current`——把 `val clipboard = …` 提到 handler 可达处，或 handler 内现取；实现时按现状选择最简方式。）未高亮组的 4 个高亮色按钮与已高亮组的 Delete 保持硬编码不动。NOTE 动作在原菜单里与高亮色同行——迁移后 NOTE 进入可配置行，默认顺序含 NOTE，视觉变化可接受。

- [x] **Step 4: 设置入口（ReadingSettingsRoute）**

镜像该文件既有区块，加"选词菜单"分区：对 `SelectionMenuAction.entries` 渲染 FilterChip（选中 = 在 `menuActions` 中），点击即 `SelectionMenuActionPrefs.save(context, 新列表)` 并刷新本地 state。只提供启停，不提供排序（YAGNI，需要时再加）。新文案 key：`"Selection menu actions": "Selection menu actions"` / `"选词菜单动作"`，走 locale 流程。

- [x] **Step 5: 回归 + Commit**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/SelectionMenuActions.kt android/app/src/test/java/com/koodoreader/reader/shell/SelectionMenuActionsTest.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/ReadingSettingsRoute.kt src/assets/locales/ android/app/src/main/assets/locales/
git commit -m "feat(android): make selection menu actions configurable"
```

---

### Task 4: P1-14 书架卡片尺寸分档

**Files:**
- Create: `android/app/src/test/java/com/koodoreader/reader/shell/BookCardTierTest.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BookCard.kt`（网格 tile，约 70-155 行）

**Interfaces:**
- Produces: `BookCardTier.forWidthDp(widthDp: Float): BookCardTier`

- [x] **Step 1: 失败测试**

```kotlin
package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class BookCardTierTest {
    @Test
    fun `narrow tiles are compact`() {
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(104f))
        assertEquals(BookCardTier.COMPACT, BookCardTier.forWidthDp(139.9f))
    }

    @Test
    fun `wide tiles are regular`() {
        assertEquals(BookCardTier.REGULAR, BookCardTier.forWidthDp(140f))
        assertEquals(BookCardTier.REGULAR, BookCardTier.forWidthDp(400f))
    }
}
```

Run 确认失败（`BookCardTier` 不存在）。

- [x] **Step 2: 实现**

`BookCard.kt` 顶部加：

```kotlin
/** Shelf card density tier (P1-14): what fits on a tile depends on its width. */
enum class BookCardTier {
    COMPACT,
    REGULAR,
    ;

    companion object {
        /** Below this tile width (dp) the compact tier drops the progress badge. */
        const val REGULAR_MIN_WIDTH_DP = 140f

        fun forWidthDp(widthDp: Float): BookCardTier =
            if (widthDp < REGULAR_MIN_WIDTH_DP) COMPACT else REGULAR
    }
}
```

测试通过后，把网格 `BookCard`（约 70 行起的 tile composable）最外层包进 `BoxWithConstraints`，派生 `val tier = BookCardTier.forWidthDp(this.maxWidth.value)`；把标题 `Text` 的 style 改为 `if (tier == BookCardTier.REGULAR) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall`，封面进度徽章在 `tier == BookCardTier.COMPACT` 时不渲染。（徽章渲染点已在文件中搜索 `progressFraction` 定位；`toCardModel` 不动。）列表行 `BookCardListRow` 不分档。

- [x] **Step 3: 回归 + Commit**

Run: `cd android && ./gradlew :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native --console=plain`
Expected: BUILD SUCCESSFUL

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/BookCard.kt android/app/src/test/java/com/koodoreader/reader/shell/BookCardTierTest.kt
git commit -m "feat(android): tier shelf grid cards by tile width"
```

---

### Task 5: P1-10 页边标注（sidemark）

**Files:**
- Modify: `android/engine/annotate/src/main/kotlin/com/koodoreader/engine/annotate/HighlightPalette.kt`
- Test: `android/engine/annotate/src/test/kotlin/com/koodoreader/engine/annotate/HighlightPaletteTest.kt`（追加）
- 接线文件：取决于 Step 1 的契约确认（两个候选，见下）

**Interfaces:**
- Produces: `HighlightPalette.sideMarkCss(colorCss: String, dark: Boolean): String`——生成页边标记的 class 规则

- [x] **Step 1: 引擎契约确认（只读，约 15 分钟）**

读 `android/app/src/main/assets/foliate/engine.mjs` 的 `addAnnotation`/高亮应用路径，回答一个问题：高亮 span 是否带稳定的 class 名（或 `addHighlight(cfi, css)` 的 css 落到哪里——inline style 还是 class）？
- 若高亮 span 有稳定 class（如 `koodo-hl` 或按色区分）：走候选 A——userStylesheet 追加一条 `.koodo-hl::after { … }` 类规则，无需 engine 改动。
- 若只有 inline style：走候选 B——`engine.mjs` 的 addAnnotation 增加一个 `data-koodo-hl="<color>"` 属性（5 行内的小改动），CSS 用 `[data-koodo-hl]::after` 选中。
两个候选的 CSS 主体相同，Step 2 一次写好，Step 3 按 A/B 二选一接线。

- [x] **Step 2: 失败测试 + 实现 palette 函数**

`HighlightPaletteTest.kt` 追加：

```kotlin
    @Test
    fun `side mark sits in the page margin and follows the highlight colour`() {
        val css = HighlightPalette.sideMarkCss("#ffe066", dark = false)
        assertTrue(css.contains("content:"))
        assertTrue(css.contains("position:absolute"))
        assertTrue(css.contains("#ffe066"))
        assertTrue(css.contains("width:3px"))
    }

    @Test
    fun `dark theme softens the side mark`() {
        assertTrue(HighlightPalette.sideMarkCss("#ffe066", dark = true).contains("opacity"))
    }
```

`HighlightPalette.kt` 实现（与 `readerCss` 同风格）：

```kotlin
    /**
     * P1-10 sidemark: a 3px vertical tick in the page margin beside every
     * highlighted line, KOReader-style. Emitted as a class rule (pseudo
     * elements cannot be set via inline style), so the wiring step attaches
     * the class (candidate A) or data attribute (candidate B).
     */
    fun sideMarkCss(colorCss: String, dark: Boolean): String = buildString {
        append("content:'';position:absolute;")
        append("top:0;bottom:0;")
        append(if (dark) "opacity:0.55;" else "")
        append("width:3px;background:").append(colorCss).append(';')
    }
```

（选择器部分由接线步拼：`selector + "{" + sideMarkCss(...) + "}"`。）

- [x] **Step 3: 接线（A/B 二选一）**

- 候选 A：在 `ReaderTypography.userStylesheet` 产物末尾追加 4 条规则（黄/绿/粉/黑蓝四色的 `::after` 用 `left:-6px`；RTL 时改 `right:-6px`——方向从 `ReaderFeatureState`/布局方向透传一个 `rtl: Boolean = false` 参数）。
- 候选 B：`engine.mjs` addAnnotation 落 span 处加 `data-koodo-hl`；Kotlin 侧同样在 userStylesheet 追加 `[data-koodo-hl=…]::after` 规则。
高亮重锚/重刷路径（`addHighlight` 重放、`paintJavascript`）因都走同一 span 生成函数，自动继承，无需另改。

- [x] **Step 4: 回归 + Commit**

Run: `cd android && ./gradlew :engine:annotate:test :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native --console=plain && node scripts/test-foliate-host.js`
Expected: 全部绿

```bash
git add android/engine/annotate/ android/app/src/main/java/com/koodoreader/reader/shell/ReaderTypography.kt android/app/src/main/assets/foliate/engine.mjs
git commit -m "feat(android): sidemark annotation ticks in the page margin"
```

---

### Task 6: 收尾——文档回写 + 全量回归

**Files:**
- Modify: `docs/android-uiux-upgrade-2026-11-19.md`（§5.2 遗留段、§3.5 清单勾选）

- [x] **Step 1: 回写文档**

- §5.2 遗留段改写为："native 轨已由 paginatorOptions 量化（§5.2 同轮落地）；WebView 轨接缝 `ReaderCjkColumn.sidePadFor` 已接入（见 2026-11-22 提交）。**剩余：实机核对 WebView 可用宽度口径**（转入真机附录清单）。"
- §3.5 表格：P0-2 标 ✅、P1-10/12/13/14 标 ✅（完成后）。
- 新增一节"2026-11-22 第 9 轮：P0/P1 剩余项落地"，按 Task 1-5 记改动 + 提交号。

- [x] **Step 2: 全量回归**

```bash
node scripts/check-dict-catalog.js && node scripts/test-foliate-host.js 2>&1 | tail -1 && node scripts/test-pdfengine.js 2>&1 | tail -1 && \
cd android && ./gradlew :benchmarks:compileBenchmarkKotlin :core:designsystem:test :core:designsystem:selfCheck :engine:annotate:test :core:importer:test :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20414 -PversionName=2.4.4-native --console=plain
```
Expected: 全部绿（app 套件 ≥378 tests, 0 failed）

- [x] **Step 3: Commit**

```bash
git add docs/android-uiux-upgrade-2026-11-19.md
git commit -m "docs(android): record p0-p1 remainder round in uiux upgrade log"
```

---

## 附录（不在本计划，真机可用时单独执行）

1. **P0-2 实机核对**：WebView 可用 CSS 宽口径（是否存在滚动条/内边距差），必要时修正 `Task 1 Step 5` 的宽度计算。
2. **M4a Task 10 真机三点清单**（阅读选项/双页、PDF 裁边重排、全局默认字号）+ 备份脚本真机往返 + benchmark device run。
3. **高亮重锚真机验收 3 项**（§5.9）。
4. **性能基线回填**：`docs/android-baseline*.json` 的 `pending-device` 四项（冷启动/打开/翻页/内存）。

## 暂缓项（需产品决策或另立里程碑）

- `check-locales.mjs` 2 处注册表漂移（b59ad363 既有）。
- Roadmap 后续里程碑：M4b（抛光与 a11y + UX 截图回归清单）、M4c（备份字段 UX + 用户指南）、M5（平板布局 + 漫画壳统一）。
- P2 可选功能：页内脚注、竖排/RTL、ruby/割注、替代目录、双书对照。
- 选词菜单动作**排序**（本计划只做启停）。

## Self-Review 记录

- §3.5 覆盖：P0-2 → Task 1（native 半已存在，已在 Global Constraints 写明防止重做）；P1-10 → Task 5；P1-12 → Task 3；P1-13 → Task 2；P1-14 → Task 4。P1-9/11 此前轮次已完成，不重复。
- 真机相关全部移出正文进附录，符合用户要求。
- 签名一致性：`ReaderFeatureState.letterSpacingSp` 在 Task 2 定义、被 ReaderTypography 双轨与控制中心消费；`SelectionMenuAction`/`BookCardTier`/`ReaderCjkColumn` 均为各自 Task 内自包含，无跨 Task 依赖（Task 顺序可并行，编号仅为优先级）。
