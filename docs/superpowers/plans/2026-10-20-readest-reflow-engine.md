# Readest Reflow Engine (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reflowable books on native Android apply Readest `getStyles` + foliate `flow`/`max-column-count`, so EPUB/MOBI/FB2 match Readest mobile layout (scroll default, paginated toggle, two-column, real theme, footnotes in Compose).

**Architecture:** Keep Compose chrome. Bundle Readest `style.ts` (`getStyles`) into `assets/foliate/readest-style.mjs` with Android stubs. `ReadestViewSettings` maps ThemeKind + ReaderFeatureState to JSON. `FoliateHostBridge.setViewSettings` pushes that JSON; `engine.mjs` calls `getStyles` and `renderer.setAttribute`. Do not embed FoliateViewer or Next/React.

**Tech Stack:** Kotlin/Compose, Node + npx esbuild, vendored readest/foliate-js (MIT), Readest `style.ts` (AGPL-3.0), existing LocalAssetServer WebView host.

## Global Constraints

- Native Android only (`-Ptarget=native`). No cloud, no desktop `src/`, no plugins.
- User-visible strings via `t("key")`; new keys go in `src/assets/locales/en.json`.
- WebView mutations only on the WebView thread (`webView.post`).
- PDF / CBZ / CBT / CB7 / CBR routing unchanged.
- Keep `-Preader=canvas` rollback.
- Do **not** git commit unless the user explicitly asks this session.
- Gradle on this machine: JDK `E:\open-source\koodo-reader\.tools\jdk-17.0.20.1+1`, `GRADLE_USER_HOME=.tools/gradle-home`, `LOCALAPPDATA=.tools/localappdata`, `-Dkotlin.compiler.execution.strategy=in-process`, init scripts `.tools/reloc-build.gradle` and `.tools/debug-signing.gradle`.

### Gradle unit-test command

```powershell
$tmp = "E:\open-source\koodo-reader\.tools\tmp"
$env:JAVA_HOME = "E:\open-source\koodo-reader\.tools\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$env:ANDROID_HOME = "C:\Users\54389\AppData\Local\Android\Sdk"
$env:GRADLE_USER_HOME = "E:\open-source\koodo-reader\.tools\gradle-home"
$env:LOCALAPPDATA = "E:\open-source\koodo-reader\.tools\localappdata"
$env:TEMP = $tmp; $env:TMP = $tmp
Set-Location "E:\open-source\koodo-reader\android"
.\gradlew.bat --no-daemon --init-script "E:\open-source\koodo-reader\.tools\reloc-build.gradle" --init-script "E:\open-source\koodo-reader\.tools\debug-signing.gradle" --project-cache-dir "E:\open-source\koodo-reader\.tools\gradle-project-cache" "-Djava.io.tmpdir=$tmp" "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests <TEST_CLASS>
```

---

## File map

| File | Responsibility |
|------|----------------|
| `android/app/src/main/java/com/koodoreader/reader/epubhost/ReadestViewSettings.kt` | Pure JVM: ThemeKind + ReaderFeatureState → JSON snapshot |
| `android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt` | Pins mapping (night, fontSize/1.25, columns, margin floor, scrolled default) |
| `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt` | Add `scrolled: Boolean = true` + codec key |
| `scripts/readest-style/stubs/*.js` | Android stubs for Readest imports (`getOSPlatform`, themes, fonts, …) |
| `scripts/bundle-readest-style.mjs` | esbuild `style.ts` → `assets/foliate/readest-style.mjs` |
| `android/app/src/main/assets/foliate/engine.mjs` | `setViewSettings` applies getStyles + flow/columns |
| `android/app/src/main/assets/foliate/index.html` | Import readest-style; footnote click → `onEvent` |
| `android/app/src/main/java/.../FoliateHostBridge.kt` | `setViewSettings(json, bgArgb)`; `setAppearance` delegates |
| `android/app/src/main/java/.../NativeEpubScreen.kt` | Call setViewSettings; footnote sheet |
| `android/app/src/main/java/.../ReaderAppearanceSheet.kt` | Scroll/paginated toggle |
| `scripts/test-foliate-host.js` | Node pins for night CSS, flow=paginated, TXT toc |

---

### Task 1: ReadestViewSettings mapper

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/epubhost/ReadestViewSettings.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/epubhost/ReadestViewSettingsTest.kt`
- Consumes: `ThemeKind`, `ThemeSpec.builtInPreset` via `com.koodoreader.reader.shell.builtInPreset`, `ReaderFeatureState`, `ReaderFeatureCodec`
- Produces: `ReadestViewSettings.Snapshot` and `ReadestViewSettings.from(kind, features): Snapshot`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.reader.shell.ReaderFeatureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadestViewSettingsTest {

    @Test
    fun `night kind sets dark mode and overrideColor`() {
        val s = ReadestViewSettings.from(ThemeKind.NIGHT, ReaderFeatureState())
        assertTrue(s.isDarkMode)
        assertTrue(s.overrideColor)
        assertEquals("dark", s.colorScheme)
        assertEquals("rgba(44, 47, 49,1)", s.bg)
        assertEquals("rgba(255,255,255,1)", s.fg)
    }

    @Test
    fun `fontScale 1 maps defaultFontSize to 17 divided by 1_25`() {
        val s = ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(fontScale = 1.0f))
        assertEquals(17.0 / 1.25, s.defaultFontSize, 0.001)
        assertFalse(s.isDarkMode)
        assertEquals("light", s.colorScheme)
    }

    @Test
    fun `columns two and negative margin`() {
        val s = ReadestViewSettings.from(
            ThemeKind.PROTECT_EYE,
            ReaderFeatureState(columnsPerPage = 2, marginHorizontalPx = -8f),
        )
        assertEquals(2, s.maxColumnCount)
        assertEquals(0, s.marginPx)
        assertTrue(s.scrolled)
    }

    @Test
    fun `generic font keys pass through, others become serif`() {
        assertEquals("sans-serif", ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(fontKey = "sans-serif")).defaultFont)
        assertEquals("serif", ReadestViewSettings.from(ThemeKind.DEFAULT, ReaderFeatureState(fontKey = "bundled:inter")).defaultFont)
    }
}
```

- [ ] **Step 2: Run test, expect compile failure** (`ReadestViewSettings` unresolved)

Run: Gradle command with `--tests com.koodoreader.reader.epubhost.ReadestViewSettingsTest`

Expected: FAIL compiling, `Unresolved reference: ReadestViewSettings`

- [ ] **Step 3: Minimal implementation**

```kotlin
package com.koodoreader.reader.epubhost

import com.koodoreader.core.designsystem.ThemeKind
import com.koodoreader.reader.shell.ReaderFeatureCodec
import com.koodoreader.reader.shell.ReaderFeatureState
import com.koodoreader.reader.shell.builtInPreset

object ReadestViewSettings {

    data class Snapshot(
        val bg: String,
        val fg: String,
        val defaultFontSize: Double,
        val lineHeight: Double,
        val marginPx: Int,
        val maxColumnCount: Int,
        val scrolled: Boolean,
        val overrideColor: Boolean,
        val isDarkMode: Boolean,
        val colorScheme: String,
        val defaultFont: String,
    )

    fun from(kind: ThemeKind, features: ReaderFeatureState): Snapshot {
        val preset = kind.builtInPreset()
        val dark = kind == ThemeKind.NIGHT
        val fontSizePx = ReaderFeatureCodec.fontSizePx(features).toDouble()
        val font = features.fontKey.lowercase()
        val defaultFont = when (font) {
            "sans-serif", "serif", "monospace" -> font
            else -> "serif"
        }
        return Snapshot(
            bg = preset.backgroundColor,
            fg = preset.foregroundColor,
            defaultFontSize = fontSizePx / 1.25,
            lineHeight = ReaderFeatureCodec.clampedLineHeight(features).toDouble(),
            marginPx = ReaderFeatureCodec.clampedMargin(features).toInt().coerceAtLeast(0),
            maxColumnCount = ReaderFeatureCodec.clampedColumns(features).coerceIn(1, 2),
            scrolled = features.scrolled,
            overrideColor = true,
            isDarkMode = dark,
            colorScheme = if (dark) "dark" else "light",
            defaultFont = defaultFont,
        )
    }
}
```

Note: `features.scrolled` lands in Task 2. For this task to compile in isolation, add `val scrolled: Boolean = true` on `ReaderFeatureState` in the same step if Task 2 is not done yet (default true, no codec yet is OK — tests use the default).

- [ ] **Step 4: Re-run tests**

Expected: PASS (`night kind…`, `fontScale 1…`, `columns two…`, `generic font keys…`)

---

### Task 2: Persist `scrolled` on ReaderFeatureState

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReaderFeatureState.kt`
- Modify: `android/app/src/test/java/com/koodoreader/reader/shell/ReaderFeatureStateTest.kt`

**Interfaces:**
- Produces: `ReaderFeatureState.scrolled: Boolean` default `true`; codec key `"scrolled"`

- [ ] **Step 1: Extend the existing test**

In `empty store is desktop defaults…` add:

```kotlin
assertTrue(s.scrolled)
```

In `enhancement flags and rules survive a round trip` set `scrolled = false` on `original` and `assertFalse(restored.scrolled)`.

- [ ] **Step 2: Run `ReaderFeatureStateTest`**

Expected: FAIL — `Unresolved reference: scrolled` or assertion `scrolled` not restored.

- [ ] **Step 3: Implementation**

Add to data class:

```kotlin
val scrolled: Boolean = true,
```

Add:

```kotlin
const val KEY_SCROLLED = "scrolled"
```

In `fromMap`:

```kotlin
scrolled = boolOf(map[KEY_SCROLLED]).let { raw ->
    if (map[KEY_SCROLLED] == null) true else raw
},
```

Wait — `boolOf` treats missing as false. Missing must default **true**. Use:

```kotlin
scrolled = if (map[KEY_SCROLLED] == null) true else boolOf(map[KEY_SCROLLED]),
```

In `toMap`:

```kotlin
KEY_SCROLLED to state.scrolled.toString(),
```

- [ ] **Step 4: Re-run `ReaderFeatureStateTest` and `ReadestViewSettingsTest`**

Expected: all PASS.

---

### Task 3: Bundle Readest `getStyles`

**Files:**
- Create: `scripts/readest-style/stubs/misc.js`
- Create: `scripts/readest-style/stubs/themes.js`
- Create: `scripts/readest-style/stubs/fonts.js`
- Create: `scripts/readest-style/stubs/constants.js`
- Create: `scripts/readest-style/stubs/dialogueHighlight.js`
- Create: `scripts/readest-style/stubs/ambientLight.js`
- Create: `scripts/readest-style/stubs/inlineTags.js`
- Create: `scripts/readest-style/stubs/scrollable.js`
- Create: `scripts/bundle-readest-style.mjs`
- Create: `android/app/src/main/assets/foliate/readest-style.mjs` (build output)
- Modify: `android/app/src/main/assets/foliate/VENDOR.txt`

**Interfaces:**
- Produces: ESM `export function getStyles(viewSettings, themeCode)` from the bundled file

- [ ] **Step 1: Fetch Readest style.ts into `.tools/readest-src/utils/style.ts`**

```powershell
New-Item -ItemType Directory -Force -Path "E:\open-source\koodo-reader\.tools\readest-src\utils" | Out-Null
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/readest/readest/main/apps/readest-app/src/utils/style.ts" -OutFile "E:\open-source\koodo-reader\.tools\readest-src\utils\style.ts"
```

If the file is missing `export const getStyles`, stop — do not invent a replacement.

- [ ] **Step 2: Write stubs** (each file is the whole module)

`scripts/readest-style/stubs/misc.js`:

```javascript
export function getOSPlatform() { return 'android'; }
```

`scripts/readest-style/stubs/themes.js`:

```javascript
export const themes = [];
export function generateLightPalette() { return {}; }
export function generateDarkPalette() { return {}; }
```

`scripts/readest-style/stubs/fonts.js`:

```javascript
export function createFontCSS() { return ''; }
```

`scripts/readest-style/stubs/constants.js`:

```javascript
export const MONOSPACE_FONTS = ['Consolas'];
export const SANS_SERIF_FONTS = ['Arial'];
export const SERIF_FONTS = ['Georgia'];
export const FALLBACK_FONTS = ['serif'];
export const CJK_SANS_SERIF_FONTS = ['Noto Sans CJK'];
export const CJK_SERIF_FONTS = ['Noto Serif CJK'];
```

`scripts/readest-style/stubs/dialogueHighlight.js`:

```javascript
export function isDialogueHighlightActive() { return false; }
```

`scripts/readest-style/stubs/ambientLight.js`:

```javascript
export function readStoredAmbientIsDarkMode() { return false; }
```

`scripts/readest-style/stubs/inlineTags.js`:

```javascript
export const INLINE_FORMATTING_SELECTOR = 'em, i, strong, b, code';
```

`scripts/readest-style/stubs/scrollable.js`:

```javascript
export const LINK_HIT_AREA_CLASS = 'koodo-link-hit';
export const LINK_HIT_AREA_PX = 44;
export const SCROLL_WRAPPER_CLASS = 'koodo-scroll';
export const SCROLL_WRAPPER_FIT_CLASS = 'koodo-scroll-fit';
```

- [ ] **Step 3: Write `scripts/bundle-readest-style.mjs`**

```javascript
import { build } from 'esbuild';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const stub = (name) => path.join(root, 'scripts', 'readest-style', 'stubs', name);

await build({
  absWorkingDir: root,
  entryPoints: [path.join(root, '.tools', 'readest-src', 'utils', 'style.ts')],
  outfile: path.join(root, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'readest-style.mjs'),
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: 'es2020',
  alias: {
    '@/utils/misc': stub('misc.js'),
    '@/styles/themes': stub('themes.js'),
    '@/styles/fonts': stub('fonts.js'),
    '@/services/constants': stub('constants.js'),
    '@/utils/dialogueHighlight': stub('dialogueHighlight.js'),
    '@/utils/ambientLight': stub('ambientLight.js'),
    '@/utils/inlineTags': stub('inlineTags.js'),
    '@/utils/scrollable': stub('scrollable.js'),
    '@/types/book': path.join(root, 'scripts', 'readest-style', 'stubs', 'book-types.js'),
  },
});
```

Also create `scripts/readest-style/stubs/book-types.js`:

```javascript
export const FIXED_LAYOUT_FORMATS = ['PDF', 'CBZ'];
```

- [ ] **Step 4: Write a failing Node test in `scripts/test-foliate-host.js`**

After existing imports, add `getStyles` from `readest-style.mjs`. If the file does not exist the test fails (RED).

```javascript
const STYLE_MJS = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'readest-style.mjs');
const { getStyles } = await import(pathToFileURL(STYLE_MJS).href);
const css = getStyles(
    {
        marginTopPx: 16, marginRightPx: 16, marginBottomPx: 16, marginLeftPx: 16,
        writingMode: 'auto', vertical: false, useBookLayout: false, overrideLayout: true,
        paragraphMargin: 1, lineHeight: 1.5, wordSpacing: 0, letterSpacing: 0,
        textIndent: 0, fullJustification: false, hyphenation: false,
        serifFont: 'Georgia', sansSerifFont: 'Arial', monospaceFont: 'Consolas',
        defaultFont: 'serif', defaultCJKFont: 'Noto Serif CJK', defaultFontSize: 13.6,
        minimumFontSize: 12, fontWeight: 400, overrideFont: false,
        overrideColor: true, invertImgColorInDark: false, backgroundTextureId: '',
        isEink: false, userStylesheet: '', zoomLevel: 100,
    },
    { bg: 'rgba(44, 47, 49,1)', fg: 'rgba(255,255,255,1)', isDarkMode: true, primary: '#fff', base100: '#2c2f31' },
);
check('readest getStyles night overrides nested colours', css.indexOf('!important') >= 0, css.slice(0, 200));
```

Run: `node scripts/test-foliate-host.js`

Expected: FAIL (cannot find `readest-style.mjs` or `getStyles`)

- [ ] **Step 5: Bundle**

```powershell
Set-Location "E:\open-source\koodo-reader"
npx --yes esbuild@0.24.2 --version
node scripts/bundle-readest-style.mjs
```

If esbuild alias API rejects, switch to `plugins` with `onResolve` for `@/` prefixes — do not rewrite `style.ts`.

Prepend a banner comment to the outfile (or write VENDOR.txt):

```
readest style.ts (AGPL-3.0) bundled for Android reflow
source: https://github.com/readest/readest
foliate-js MIT: see LICENSE in this folder
```

- [ ] **Step 6: Re-run `node scripts/test-foliate-host.js`**

Expected: PASS including `readest getStyles night overrides nested colours`

If `getStyles` needs more ViewSettings fields, add the missing keys to the test object with Readest defaults (`gapPercent: 5`, `maxColumnCount: 1`, `scrolled: true`, `showHeader: false`, `showFooter: false`) until the import runs. Do not stub `getStyles` itself.

---

### Task 4: `engine.mjs` `setViewSettings`

**Files:**
- Modify: `android/app/src/main/assets/foliate/engine.mjs`
- Modify: `android/app/src/main/assets/foliate/index.html`
- Modify: `scripts/test-foliate-host.js`

**Interfaces:**
- Consumes: `getStyles` from `./readest-style.mjs`
- Produces: `setViewSettings(opts)` → `{ ok: true }`; records `engine.flow` and `engine.maxColumnCount`

- [ ] **Step 1: Failing Node tests**

```javascript
await engine.setViewSettings({
    bg: 'rgba(44, 47, 49,1)', fg: 'rgba(255,255,255,1)',
    defaultFontSize: 13.6, lineHeight: 1.75, marginPx: 16,
    maxColumnCount: 2, scrolled: false, overrideColor: true,
    isDarkMode: true, colorScheme: 'dark', defaultFont: 'serif',
});
eq('setViewSettings records paginated flow', engine.flow, 'paginated');
eq('setViewSettings records two columns', engine.maxColumnCount, 2);
```

Run `node scripts/test-foliate-host.js`

Expected: FAIL `setViewSettings is not a function` or `unknown method`

- [ ] **Step 2: Implementation in `createFoliateEngine`**

Add to appearance state:

```javascript
let flow = 'scrolled';
let maxColumnCount = 1;
```

Import at top of engine.mjs (or lazy inside setViewSettings to keep Node tests that mock renderer working):

```javascript
async function setViewSettings(opts) {
    appearance = Object.assign({}, appearance, opts || {});
    flow = opts && opts.scrolled === false ? 'paginated' : 'scrolled';
    maxColumnCount = Math.max(1, Number((opts && opts.maxColumnCount) || 1));
    const themeCode = {
        bg: appearance.bg,
        fg: appearance.fg,
        isDarkMode: !!appearance.isDarkMode,
        primary: appearance.fg,
        base100: appearance.bg,
    };
    const viewSettings = {
        marginTopPx: appearance.marginPx || 0,
        marginRightPx: appearance.marginPx || 0,
        marginBottomPx: appearance.marginPx || 0,
        marginLeftPx: appearance.marginPx || 0,
        writingMode: 'auto',
        vertical: false,
        useBookLayout: false,
        overrideLayout: true,
        paragraphMargin: 1,
        lineHeight: Number(appearance.lineHeight || 1.5),
        wordSpacing: 0,
        letterSpacing: 0,
        textIndent: 0,
        fullJustification: false,
        hyphenation: false,
        serifFont: 'Georgia',
        sansSerifFont: 'Arial',
        monospaceFont: 'Consolas',
        defaultFont: appearance.defaultFont || 'serif',
        defaultCJKFont: 'Noto Serif CJK SC',
        defaultFontSize: Number(appearance.defaultFontSize || 13.6),
        minimumFontSize: 12,
        fontWeight: 400,
        overrideFont: false,
        overrideColor: appearance.overrideColor !== false,
        invertImgColorInDark: false,
        backgroundTextureId: '',
        isEink: false,
        userStylesheet: '',
        zoomLevel: 100,
        scrolled: flow === 'scrolled',
        maxColumnCount,
        gapPercent: 5,
        showHeader: false,
        showFooter: false,
    };
    let css = '';
    try {
        const mod = await import('./readest-style.mjs');
        css = mod.getStyles(viewSettings, themeCode);
    } catch (e) {
        css = appearanceCss(appearance);
    }
    if (view && view.renderer) {
        if (typeof view.renderer.setStyles === 'function') view.renderer.setStyles(css);
        if (typeof view.renderer.setAttribute === 'function') {
            view.renderer.setAttribute('flow', flow);
            view.renderer.setAttribute('max-column-count', String(maxColumnCount));
            view.renderer.setAttribute('max-inline-size', '720px');
            view.renderer.setAttribute('gap', '5%');
        }
    }
    return { ok: true };
}
```

Keep `setAppearance` as:

```javascript
async function setAppearance(opts) {
    return setViewSettings(Object.assign({}, opts, {
        defaultFontSize: (opts && opts.fontSize ? opts.fontSize : 17) / 1.25,
        scrolled: flow !== 'paginated',
        maxColumnCount,
        overrideColor: true,
        isDarkMode: (opts && opts.colorScheme) === 'dark',
    }));
}
```

Export `setViewSettings` on the engine object and add `'setViewSettings'` to `METHODS`.

Add getters:

```javascript
get flow() { return flow; },
get maxColumnCount() { return maxColumnCount; },
```

- [ ] **Step 3: Re-run `node scripts/test-foliate-host.js`**

Expected: PASS, including new flow/column checks. Existing `setAppearance records fontSize` still PASS.

---

### Task 5: Bridge + NativeEpubScreen

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateHostBridge.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`

**Interfaces:**
- Produces: `FoliateHostBridge.setViewSettings(snapshot: ReadestViewSettings.Snapshot, bgArgb: Int?)`

- [ ] **Step 1: Add Kotlin method** (no new test class required; JSON shape is pinned by Task 1)

```kotlin
fun setViewSettings(snapshot: ReadestViewSettings.Snapshot, bgArgb: Int? = null) {
    if (bgArgb != null) {
        webView.post { webView.setBackgroundColor(bgArgb) }
    }
    val payload = org.json.JSONObject()
        .put("bg", snapshot.bg)
        .put("fg", snapshot.fg)
        .put("defaultFontSize", snapshot.defaultFontSize)
        .put("lineHeight", snapshot.lineHeight)
        .put("marginPx", snapshot.marginPx)
        .put("maxColumnCount", snapshot.maxColumnCount)
        .put("scrolled", snapshot.scrolled)
        .put("overrideColor", snapshot.overrideColor)
        .put("isDarkMode", snapshot.isDarkMode)
        .put("colorScheme", snapshot.colorScheme)
        .put("defaultFont", snapshot.defaultFont)
    runJs(method = "setViewSettings", args = listOf(payload.toString()))
}
```

Change the `Opts` overload of `setAppearance` to:

```kotlin
fun setAppearance(opts: FoliateAppearance.Opts, bgArgb: Int? = null) {
    val kind = if (opts.colorScheme == "dark") ThemeKind.NIGHT else ThemeKind.DEFAULT
    val features = ReaderFeatureState(
        fontScale = 1.0f,
        lineHeightRatio = opts.lineHeight.toFloat(),
        marginHorizontalPx = opts.marginPx.toFloat(),
        fontKey = opts.fontFamily,
        scrolled = true,
    )
    // Keep this overload for any leftover callers; NativeEpubScreen must use setViewSettings.
    setViewSettings(
        ReadestViewSettings.from(kind, features).copy(
            bg = opts.bg,
            fg = opts.fg,
            defaultFontSize = opts.fontSizePx / 1.25,
            isDarkMode = opts.colorScheme == "dark",
            colorScheme = opts.colorScheme,
        ),
        bgArgb,
    )
}
```

Prefer **not** to keep that adapter if the only caller is NativeEpubScreen — replace the LaunchedEffect instead.

- [ ] **Step 2: NativeEpubScreen LaunchedEffect**

Replace `foliateAppearance` / `host.setAppearance(...)` with:

```kotlin
val viewSettings = remember(
    readerThemeKind, features.fontScale, features.fontKey,
    features.lineHeightRatio, features.marginHorizontalPx,
    features.columnsPerPage, features.scrolled,
) {
    ReadestViewSettings.from(readerThemeKind, features)
}
LaunchedEffect(useFoliate, foliateReady, viewSettings) {
    if (!useFoliate || !foliateReady) return@LaunchedEffect
    val host = foliateBridge ?: return@LaunchedEffect
    val bgArgb = CssColor.parse(viewSettings.bg).toInt()
    withContext(Dispatchers.IO) {
        runCatching { host.setViewSettings(viewSettings, bgArgb) }
    }
}
```

Need `import com.koodoreader.core.designsystem.ThemeKind` only if still referenced.

- [ ] **Step 3: Compile via unit tests**

Run: Gradle `--tests com.koodoreader.reader.epubhost.ReadestViewSettingsTest --tests com.koodoreader.reader.epubhost.FoliateReaderLayoutTest`

Expected: PASS (compile of `FoliateHostBridge` + `NativeEpubScreen` is a dependency of those tests).

---

### Task 6: Appearance sheet — 分页 / 滚动

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/ReaderAppearanceSheet.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt` (call site)
- Modify: `src/assets/locales/en.json` — add `"Scrolled": "Scrolled"` and `"Paginated": "Paginated"` if missing

- [ ] **Step 1: Add parameters**

```kotlin
fun ReaderAppearanceSheet(
    selected: ThemeKind,
    fontScale: Float,
    lineHeight: Float,
    marginPx: Float,
    scrolled: Boolean,
    onTheme: (ThemeKind) -> Unit,
    onFontScale: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onMargin: (Float) -> Unit,
    onScrolled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
)
```

After the theme chips, before font size:

```kotlin
Text(t("Layout"), style = MaterialTheme.typography.labelLarge)
Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    TextButton(onClick = { onScrolled(true) }) {
        Text(t("Scrolled") + if (scrolled) " ✓" else "")
    }
    TextButton(onClick = { onScrolled(false) }) {
        Text(t("Paginated") + if (!scrolled) " ✓" else "")
    }
}
```

Use `TextButton` from material3 (add import). If `t("Layout")` missing, use `t("Two-column layout")` only for the columns slider already in settings — for this toggle add `"Layout"` to `en.json`.

- [ ] **Step 2: Wire NativeEpubScreen**

```kotlin
ReaderAppearanceSheet(
    selected = readerThemeKind,
    fontScale = fontScale,
    lineHeight = ReaderFeatureCodec.clampedLineHeight(features),
    marginPx = ReaderFeatureCodec.clampedMargin(features),
    scrolled = features.scrolled,
    onTheme = { appearancePrefs.readerThemeKind = it },
    onFontScale = { setFontScale(it) },
    onLineHeight = { commitFeatures(features.copy(lineHeightRatio = it)) },
    onMargin = { commitFeatures(features.copy(marginHorizontalPx = it)) },
    onScrolled = { commitFeatures(features.copy(scrolled = it)) },
    onDismiss = { showAppearance = false },
)
```

- [ ] **Step 3: Compile**

Same Gradle test command as Task 5. Expected: PASS.

---

### Task 7: Footnotes → Compose sheet

**Files:**
- Modify: `android/app/src/main/assets/foliate/index.html`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Test: extend `scripts/test-foliate-host.js` only if you extract a pure helper; otherwise device smoke in Task 8 is the test.

- [ ] **Step 1: JS — listen on `foliate-view` `load` and intercept noteref clicks**

In `index.html` after `view` is created:

```javascript
view.addEventListener('load', (ev) => {
    const doc = ev.detail && ev.detail.doc;
    if (!doc || doc.__koodoFootnote) return;
    doc.__koodoFootnote = true;
    doc.addEventListener('click', (e) => {
        const a = e.target && e.target.closest && e.target.closest('a');
        if (!a) return;
        const epubType = (a.getAttribute('epub:type') || a.getAttribute('type') || '').toLowerCase();
        const href = a.getAttribute('href') || '';
        if (epubType.indexOf('noteref') < 0 && epubType.indexOf('footnote') < 0) return;
        e.preventDefault();
        e.stopPropagation();
        const id = (href.split('#')[1] || '').trim();
        let excerpt = '';
        if (id) {
            const el = doc.getElementById(id) || doc.querySelector('[name="' + id + '"]');
            excerpt = el ? String(el.textContent || '').trim().slice(0, 800) : '';
        }
        try { host.onEvent('footnote', JSON.stringify({ href, excerpt })); } catch (err) {}
    }, true);
});
```

- [ ] **Step 2: NativeEpubScreen EventSink**

Add state:

```kotlin
var footnoteText by remember { mutableStateOf<String?>(null) }
```

In `onEvent`:

```kotlin
"footnote" -> {
    val excerpt = payload.optString("excerpt", "")
    val href = payload.optString("href", "")
    if (excerpt.isNotBlank()) footnoteText = excerpt
    else if (href.isNotBlank()) {
        readerScope.launch(Dispatchers.IO) { runCatching { foliateBridge?.goTo(href) } }
    }
}
```

`eventSink` currently cannot capture `readerScope` easily if defined before it — use `mainHandler.post { footnoteText = excerpt }` like relocate already hops to main.

If the sink is created in `remember` before `footnoteText` exists, store excerpt in an `AtomicReference` or move bridge creation after state. Pattern: `var footnoteText` first, then `eventSink` closes over the setter via `mainHandler.post`.

Sheet:

```kotlin
if (footnoteText != null) {
    AlertDialog(
        onDismissRequest = { footnoteText = null },
        title = { Text(t("Notes")) },
        text = { Text(footnoteText!!) },
        confirmButton = { TextButton(onClick = { footnoteText = null }) { Text(t("Close")) } },
    )
}
```

Prefer `ModalBottomSheet` to match TOC. Key `t("Notes")` already exists in en.json from the notes tab; if not, use `t("Note")`.

- [ ] **Step 3: Compile**

Gradle Foliate tests. Expected: PASS.

---

### Task 8: Device smoke

**Files:** none (APK + adb)

- [ ] **Step 1: Assemble and install**

```powershell
# same Gradle env as unit tests
.\gradlew.bat --no-daemon --init-script "E:\open-source\koodo-reader\.tools\reloc-build.gradle" --init-script "E:\open-source\koodo-reader\.tools\debug-signing.gradle" --project-cache-dir "E:\open-source\koodo-reader\.tools\gradle-project-cache" "-Djava.io.tmpdir=$tmp" "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug -Ptarget=native -PabiFilters=arm64-v8a
$adb = "C:\Users\54389\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "E:\open-source\koodo-reader\.tools\out\_app\outputs\apk\debug\app-debug.apk"
& $adb shell am start -n com.koodoreader.reader/.LauncherAlias
```

Do **not** start `.shell.NativeShellActivity` (not exported).

- [ ] **Step 2: Manual / uiautomator checklist on PKT110, book 何以中国 EPUB**

1. Opens in **scrolled** mode (finger swipe moves text continuously).
2. `⋯` → 外观 → **Paginated** → page-tap turns pages.
3. 外观 → 夜间 → full page dark, not a white inner box.
4. 外观 → 护眼 → green page.
5. Settings two-column or columnsPerPage=2 + rotate: two columns on paginated.
6. Tap a TOC entry with a footnote/noteref if present → sheet, not a jump into empty chapter. If this book has no noterefs, skip and note in the PR/summary.

Pull screenshots to `.tools/device-readest-*.png`.

---

### Task 9: TXT → in-memory EPUB (spec D, only if Task 3 bundling is green)

**Files:**
- Create: `scripts/readest-style/stubs/txt.js` only if `@/utils/txt` is a single file with few imports
- Modify: `android/app/src/main/assets/foliate/engine.mjs` `open()` TXT branch

- [ ] **Step 1:** Fetch `https://raw.githubusercontent.com/readest/readest/main/apps/readest-app/src/utils/txt.ts` into `.tools/readest-src/utils/txt.ts`. Count its `@/` imports.

- [ ] **Step 2:** If it imports more than 4 extra Readest modules (calibre metadata, zip writer, etc.), **stop** and leave `splitTxtChapters`. Record that decision in `VENDOR.txt` as `TXT: keep engine splitTxtChapters; TxtToEpubConverter not bundled (N extra modules)`. EPUB parity is not blocked.

- [ ] **Step 3:** If ≤4 extra modules, stub them, esbuild `txt.ts` into `assets/foliate/readest-txt.mjs`, and in `open()` for `fmt === 'txt'` call `TxtToEpubConverter.convert` then `view.open(epubFile)`. Add a Node test: open fixture txt still yields toc label `第一章`.

---

## Self-review vs spec

| Spec item | Task |
|-----------|------|
| Upgrade/use Readest getStyles | 3, 4 |
| setViewSettings replaces thin appearance | 4, 5 |
| scrolled default, toggle | 2, 6 |
| two-column via max-column-count | 1, 4 |
| ThemeKind → overrideColor | 1, 4 |
| fontSize / 1.25 | 1 |
| footnotes Compose | 7 |
| PDF/comics/TTS/canvas-delete/desktop out | not scheduled |
| AGPL banner | 3 VENDOR.txt |
| Device smoke | 8 |
| TXT TxtToEpubConverter | **deferred**: engine keeps `splitTxtChapters`; opening effect is unchanged. Call out in the summary — not first-slice blocking because EPUB is the parity target. |

No TBD in tasks. `setViewSettings` / `Snapshot` names are consistent across tasks. Skip git commit steps unless the user asks.
