# Fullscreen Reader Contents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the EPUB/PDF modal bottom-sheet contents lists with full-screen pages whose entries jump directly back to the corresponding reading position.

**Architecture:** Keep the existing reader routes, book sessions, and visibility state. Add one shared `ReaderTocPage` Compose container for the full-screen scaffold, top back action, and system back handling; each reader keeps its own entry rendering and jump callback.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Activity Compose `BackHandler`, existing Foliate/PDF reader controllers.

## Global Constraints

- Do not add a NavHost route or recreate the book/Activity.
- Do not add page sliders, continuous previews, TOC search, or new dependencies.
- Preserve EPUB depth indentation and PDF page numbers/loading/empty states.
- Build nativeHost with `-Ptarget=native -PversionCode=20413 -PversionName=2.4.4-native`.
- Work in place on `dev`; do not use a worktree.

---

### Task 1: Shared full-screen contents page and EPUB/PDF integration

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/reader/ReaderTocPage.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt`
- Design: `docs/superpowers/specs/2026-11-06-fullscreen-reader-toc-design.md`

**Interfaces:**
- Produces: `@Composable fun ReaderTocPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit)`.
- Consumes: existing EPUB `foliateBridge.goTo(target)`, native `session.pageOfChapter(i)`, and PDF `controller.openOutlineRow(row)` callbacks.

- [ ] **Step 1: Run the failing UI-structure regression check**

Run:

```bash
node -e "const fs=require('fs'); const files=['app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt','app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt']; let bad=false; for(const p of files){const s=fs.readFileSync(p,'utf8'); const marker=p.includes('NativeEpub')?'if (showToc) {':'if (showOutline) {'; const block=s.slice(s.indexOf(marker)); if(!block.includes('ReaderTocPage(')){console.error('RED: full-screen ReaderTocPage missing in '+p); bad=true;}} process.exit(bad?1:0);"
```

Working directory: `android/`. Expected: exit 1 because both readers still use `ModalBottomSheet` and neither calls `ReaderTocPage`.

- [ ] **Step 2: Create the shared full-screen container**

Create `ReaderTocPage.kt` with this complete implementation:

```kotlin
package com.koodoreader.reader.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.koodoreader.reader.R
import com.koodoreader.reader.shell.t

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = title)
                    }
                },
            )
        },
        content = content,
    )
}
```

- [ ] **Step 3: Replace the EPUB modal with the full-screen page**

Import `com.koodoreader.reader.reader.ReaderTocPage`, change `ReaderImmersiveBars(showChrome)` to `ReaderImmersiveBars(showChrome || showToc)`, and wrap the existing `LazyColumn` as follows. Keep both existing `items` branches and their click bodies unchanged.

```kotlin
if (showToc) {
    ReaderTocPage(
        title = t("Content"),
        onBack = { showToc = false },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Existing Foliate and native chapter items remain here unchanged.
        }
    }
}
```

Delete only the old `showToc` block's `rememberModalBottomSheetState`, `ModalBottomSheet`, title `Text`, and `fillMaxHeight(0.8f)` wrapper. Other modal sheets in the file stay unchanged.

- [ ] **Step 4: Replace the PDF modal and make its list fill the page**

Import `ReaderTocPage`, change `ReaderImmersiveBars(showChrome)` to `ReaderImmersiveBars(showChrome || showOutline)`, and use:

```kotlin
if (showOutline) {
    ReaderTocPage(
        title = t("PDF outline"),
        onBack = { showOutline = false },
    ) { padding ->
        OutlineList(
            rows = state.outline,
            loading = state.outlineLoading,
            onPick = { row ->
                controller.openOutlineRow(row)
                showOutline = false
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
```

Add `modifier: Modifier` to `OutlineList`, apply it to its root `Column`, remove its duplicate internal title, and change its list to fill the remaining column height:

```kotlin
private fun OutlineList(
    rows: List<OutlineResolver.Row>,
    loading: Boolean,
    onPick: (OutlineResolver.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        // Keep the existing empty branch unchanged.
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Keep the existing indexed outline rows unchanged.
        }
    }
}
```

The actual edit must retain the existing empty-state `return@Column`; the snippets above show the surrounding structural changes, not a license to delete those branches.

- [ ] **Step 5: Run regression checks, unit tests, and build**

Run the Step 1 check again; expected exit 0. Then run:

```bash
./gradlew :app:testNativeHostDebugUnitTest :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20413 -PversionName=2.4.4-native
```

Working directory: `android/`. Expected: `BUILD SUCCESSFUL` and no test failures.

- [ ] **Step 6: Install and verify the original scenarios on the connected phone**

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/nativeHost/debug/app-nativeHost-debug.apk
```

Working directory: `android/`. Expected: `Success`. On EPUB and PDF:

1. Open the contents from the reading toolbar; capture and inspect a screenshot showing a full-screen list and one normal top app bar.
2. Use the top back button and system back separately; each closes only the contents page and preserves the reading position.
3. Pick an EPUB chapter and a resolved PDF outline row; the contents close and the corresponding reading location appears.
4. Scroll a long contents list to its final row; it remains above the navigation bar.

- [ ] **Step 7: Commit the verified implementation**

```bash
git add android/app/src/main/java/com/koodoreader/reader/reader/ReaderTocPage.kt android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt android/app/src/main/java/com/koodoreader/reader/shell/NativePdfScreen.kt docs/superpowers/plans/2026-11-06-fullscreen-reader-toc.md
git commit -m "feat(android): use fullscreen reader contents"
```
