# Android UI/UX P0（交互断层修复四项）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复书架四大交互断层——列表视图无长按菜单、空态无行动、导入完成反馈弱、搜索无清除——与收口批 1/2 并行交付。

**Architecture:** 全部改动落在 `:core:ui` 的 `KoodoBookRow`（加 long-press + menu 槽位，与 `KoodoBookCard` 同模式）与 `:app` 的 `LibraryScreen`/`BookCard`（接线与文案）。不改数据层、不加依赖。

**Tech Stack:** Kotlin + Jetpack Compose Material3、`:core:ui`/`:core:designsystem` token、`t()` i18n（键入 `src/assets/locales/{en,zh-CN}.json`，经 `scripts/sync-locales-android.js` 同步进安卓 assets）。

## Global Constraints

- 本机**无 Android SDK / JDK 17**——`:app`/`:core:ui` 编译与测试只能 CI 验证（`gh workflow run "Build/release-android"`）；本地可跑 `node scripts/sync-locales-android.js --check` 与 `node scripts/check-locales.mjs`。
- 新 UI 文案必须走 `t()`；新键同时加 `src/assets/locales/en.json` 与 `zh-CN.json`，然后跑同步脚本（CI 有 `--check` 守卫）。
- `:core:ui` 禁止依赖 `:core:data`/Context/i18n（既有 interface inversion 约束，见 KoodoBookCard.kt:38-46）；菜单文案由调用方注入。
- 图标：优先用 `R.drawable.ic_*`（Readest 风格矢量集）；该集没有的字形用 `Icons.Default.*`（material-icons-core 已是依赖，KoodoBookCard.kt:16-17 有先例）。
- 不新增任何第三方依赖；不重构无关布局（P1 的大标题/转场不在本计划）。

---

### Task 1: 列表视图长按菜单（KoodoBookRow 槽位化 + BookListRow 接线）

**Files:**
- Modify: `android/core/ui/src/main/kotlin/com/koodoreader/core/ui/component/KoodoBookCard.kt:129-182`（KoodoBookRow）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BookCard.kt:98-113`（BookListRow）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt:304-323`（列表分支传回调）

**Interfaces:**
- Consumes: `BookCardModel`（既有）；`DropdownMenu/DropdownMenuItem`（BookCard.kt 已 import）
- Produces: `KoodoBookRow(model, cover, onClick, onLongClick: () -> Unit = {}, favoriteContentDescription: String? = null, menu: @Composable () -> Unit = {}, modifier)`；`BookListRow(..., onToggleFavorite, onMoveToTrash, onAddToShelf: () -> Unit = {})`

- [ ] **Step 1: KoodoBookRow 加参数与 combinedClickable**

把 KoodoBookCard.kt:136-182 整个函数替换为：

```kotlin
/**
 * Compact list-view row (view mode = list).
 *
 * Long-press + menu slot mirror [KoodoBookCard] so grid and list expose the
 * same per-book actions — the menu wording comes from the caller's i18n.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KoodoBookRow(
    model: BookCardModel,
    cover: Painter?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    favoriteContentDescription: String? = null,
    menu: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KoodoBookCover(
                model = model,
                cover = cover,
                modifier = Modifier
                    .width(48.dp)
                    .aspectRatio(COVER_ASPECT)
                    .clip(KoodoCoverShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = favoriteContentDescription,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        menu()
    }
}
```

`ExperimentalFoundationApi` 已在文件 :3 import、:74 已用，无新 import。

- [ ] **Step 2: BookListRow 透传菜单**

把 BookCard.kt:98-113 整个函数替换为：

```kotlin
/** Compact list-view row (view mode = list) — same menu actions as the grid. */
@Composable
fun BookListRow(
    book: BookEntity,
    isFavorite: Boolean,
    coverVersion: Int = 0,
    onToggleFavorite: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
    onAddToShelf: () -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    KoodoBookRow(
        model = book.toCardModel(isFavorite),
        cover = rememberCoverPainter(book.cover, book.key, coverVersion),
        onClick = onClick,
        onLongClick = { menuOpen = true },
        favoriteContentDescription = t("Favorite"),
        menu = {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(if (isFavorite) t("Remove from favorite") else t("Add to favorite"))
                    },
                    onClick = { onToggleFavorite(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(t("Move to trash")) },
                    onClick = { onMoveToTrash(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(t("Add to shelf")) },
                    onClick = { onAddToShelf(); menuOpen = false },
                )
            }
        },
        modifier = modifier,
    )
}
```

（`remember/mutableStateOf/getValue/setValue` 已在 BookCard.kt import。）

- [ ] **Step 3: LibraryScreen 列表分支传回调**

把 LibraryScreen.kt:309-321 的 `items(books, key = { it.key }) { book -> ... }` 内容改为：

```kotlin
                    items(books, key = { it.key }) { book ->
                        ShellSettingsGroup {
                            BookListRow(
                                book = book,
                                isFavorite = book.key in shelf.favorites,
                                coverVersion = coverVersion,
                                onToggleFavorite = { viewModel.toggleFavorite(book.key) },
                                onMoveToTrash = { viewModel.moveToTrash(book.key) },
                                onAddToShelf = { shelfPickKey = book.key },
                                onClick = { onOpenBook(book.key) },
                                modifier = Modifier.padding(
                                    horizontal = SpaceTokens.md.dp,
                                    vertical = SpaceTokens.sm.dp,
                                ),
                            )
                        }
                    }
```

- [ ] **Step 4: i18n 键确认**

Run: `grep -o '"Remove from favorite"\|"Add to favorite"\|"Move to trash"\|"Add to shelf"\|"Favorite"' src/assets/locales/en.json | sort -u`
Expected: 5 个键全部已存在（网格菜单在用）。若有缺失，在 `en.json` 与 `zh-CN.json` 补齐后跑 `node scripts/sync-locales-android.js`。

- [ ] **Step 5: 本地可跑守卫 + Commit**

Run: `node scripts/sync-locales-android.js --check && node scripts/check-locales.mjs`
Expected: 全绿（无键漂移）。`:core:ui`/`:app` 编译由 CI 验证（workflow_dispatch）。

```bash
git add android/core/ui/src/main/kotlin/com/koodoreader/core/ui/component/KoodoBookCard.kt \
        android/app/src/main/java/com/koodoreader/reader/shell/BookCard.kt \
        android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt
git commit -m "feat(android): 书架列表视图长按菜单与网格对齐"
```

---

### Task 2: 空态行动化（导入按钮）

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt:256-257,388-402`

**Interfaces:**
- Consumes: `filesLauncher`（LibraryScreen :74-76 既有）
- Produces: `EmptyLibraryHint(favoritesOnly: Boolean, onImportClick: () -> Unit)`

- [ ] **Step 1: 改签名与构图**

把 LibraryScreen.kt:388-402 整个函数替换为：

```kotlin
@Composable
private fun EmptyLibraryHint(favoritesOnly: Boolean, onImportClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_import),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = SpaceTokens.md.dp),
            )
            Text(
                text = if (favoritesOnly) {
                    t("No favorite books")
                } else {
                    t("Library is empty")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!favoritesOnly) {
                Button(
                    onClick = onImportClick,
                    modifier = Modifier.padding(top = SpaceTokens.md.dp),
                ) {
                    Text(t("Import"))
                }
            }
        }
    }
}
```

新增 import：`androidx.compose.material3.Button`（import 区 :23-39 处插入）。

- [ ] **Step 2: 调用点传回调**

把 LibraryScreen.kt:256-257 的：

```kotlin
            if (books.isEmpty()) {
                EmptyLibraryHint(shelf.favoritesOnly)
```

改为：

```kotlin
            if (books.isEmpty()) {
                EmptyLibraryHint(
                    favoritesOnly = shelf.favoritesOnly,
                    onImportClick = { filesLauncher.launch(arrayOf("*/*")) },
                )
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt
git commit -m "feat(android): 书架空态加导入按钮"
```

---

### Task 3: 导入完成 Snackbar + 失败详情弹窗

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt`（Scaffold 区 :89-89、importState 区 :104-126、EmptyLibraryHint 调用前）
- Modify: `src/assets/locales/en.json`、`src/assets/locales/zh-CN.json`（新键 "View details"）

**Interfaces:**
- Consumes: `ImportState.Finished(imported, duplicates, failed, error)`（LibraryViewModel.kt:39-45，无逐文件失败清单——详情弹窗展示 `error` 文本，逐文件清单列 P2 backlog）
- Produces: Snackbar 反馈通道（后续导入相关反馈复用 `snackbarHostState`）

- [ ] **Step 1: 加 i18n 键**

`src/assets/locales/en.json` 的 Library 相关键附近加：

```json
  "View details": "View details",
```

`src/assets/locales/zh-CN.json` 对应位置加：

```json
  "View details": "查看详情",
```

（插入位置与该两文件现有键的字母序/分组保持一致；逗号按实际前后行调整。）

- [ ] **Step 2: Scaffold 挂 SnackbarHost**

LibraryScreen.kt 中 `Scaffold(` 前（:89 前）加：

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    var showFailureDialog by remember { mutableStateOf(false) }
```

`Scaffold(` 参数区加一行：

```kotlin
        snackbarHost = { SnackbarHost(snackbarHostState) },
```

- [ ] **Step 3: 完成态触发反馈**

在 `if (books.isEmpty())` 之前（:256 前）加：

```kotlin
            val finished = importState as? ImportState.Finished
            LaunchedEffect(finished) {
                val state = finished ?: return@LaunchedEffect
                val message = buildString {
                    if (state.error != null) {
                        append(t("Import failed")).append(": ").append(state.error)
                    } else {
                        append(t("Import")).append(' ').append(state.imported)
                        if (state.duplicates > 0) append(" · ").append(t("Duplicate")).append(' ').append(state.duplicates)
                        if (state.failed > 0) append(" · ").append(t("Import failed")).append(' ').append(state.failed)
                    }
                }
                val needsDetail = state.error != null || state.failed > 0
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = if (needsDetail) t("View details") else null,
                    duration = SnackbarDuration.Long,
                )
                if (needsDetail && result == SnackbarResult.ActionPerformed) {
                    showFailureDialog = true
                }
            }
```

- [ ] **Step 4: 失败详情弹窗**

在文件尾部 `if (pickKey != null) { ... }` 块之后加：

```kotlin
    if (showFailureDialog) {
        val state = importState as? ImportState.Finished
        AlertDialog(
            onDismissRequest = { showFailureDialog = false },
            title = { Text(t("Import failed")) },
            text = {
                Text(
                    state?.error ?: buildString {
                        append(t("Import failed"))
                        append(' ').append(state?.failed ?: 0)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showFailureDialog = false }) { Text(t("Confirm")) }
            },
        )
    }
```

新增 import：`androidx.compose.material3.SnackbarHost`、`androidx.compose.material3.SnackbarHostState`、`androidx.compose.material3.SnackbarDuration`、`androidx.compose.material3.SnackbarResult`、`androidx.compose.runtime.LaunchedEffect`。

- [ ] **Step 5: 本地守卫 + Commit**

Run: `node scripts/sync-locales-android.js --check && node scripts/check-locales.mjs`
Expected: 全绿。

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt \
        src/assets/locales/en.json src/assets/locales/zh-CN.json
git commit -m "feat(android): 导入完成 Snackbar 反馈与失败详情弹窗"
```

---

### Task 4: 搜索框清除按钮

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt:203-222`
- Modify: `src/assets/locales/en.json`、`src/assets/locales/zh-CN.json`（若无 "Clear" 键）

**Interfaces:**
- Consumes: `viewModel.setSearchQuery("")`（既有）；`Icons.Default.Close`（material-icons-core）
- Produces: 无

- [ ] **Step 1: 确认 "Clear" 键**

Run: `grep -c '"Clear":' src/assets/locales/en.json`
Expected: 1（已存在则跳过 Step 2 的 en 侧；zh-CN 同理查 `"查看"` 语义键——直接 `grep -c '"Clear":' src/assets/locales/zh-CN.json`）。任一缺失则按下述补。

- [ ] **Step 2: 补缺失键**

en.json：`"Clear": "Clear",`；zh-CN.json：`"Clear": "清除",`。

- [ ] **Step 3: 加 trailing 清除按钮**

把 LibraryScreen.kt:203-222 的 `TextField(...)` 改为（在 `singleLine = true,` 之后插入 trailingIcon，其余参数不动）：

```kotlin
            val keyboard = LocalSoftwareKeyboardController.current
            TextField(
                value = shelf.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SpaceTokens.SCREEN_HORIZONTAL.dp,
                        vertical = SpaceTokens.sm.dp,
                    ),
                singleLine = true,
                trailingIcon = {
                    if (shelf.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.setSearchQuery("")
                                keyboard?.hide()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = t("Clear"),
                            )
                        }
                    }
                },
                placeholder = { Text(t("Search")) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = t("Search"),
                    )
                },
                shape = ShellChrome.searchCorner,
                colors = ShellChrome.searchFieldColors(),
            )
```

新增 import：`androidx.compose.material.icons.Icons`、`androidx.compose.material.icons.filled.Close`、`androidx.compose.ui.platform.LocalSoftwareKeyboardController`。

- [ ] **Step 4: 本地守卫 + Commit**

Run: `node scripts/sync-locales-android.js --check && node scripts/check-locales.mjs`
Expected: 全绿。

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt \
        src/assets/locales/en.json src/assets/locales/zh-CN.json
git commit -m "feat(android): 书架搜索框加清除按钮"
```

---

## Self-Review 记录

- 规格覆盖：P0 四项各一 Task；#3 的「失败文件名清单」按 ImportState 现状降级为 error 文本展示，逐文件清单已在收口规格外——需同步修 UI/UX 规格 §2 #3 一行（已完成：见该规格同日修订）。
- 占位符：无 TBD；每个代码步骤均为完整替换块。
- 类型一致性：`KoodoBookRow` 新签名（Task 1 Step 1）与 `BookListRow` 调用（Step 2）参数名一一对应；`EmptyLibraryHint(favoritesOnly, onImportClick)` 定义与调用一致；`snackbarHostState`/`showFailureDialog` 在 Task 3 内定义并自洽。
- 验证边界：`:core:ui` 无既有测试目录（Glob 确认 `src/test` 不存在），本计划不引入 Robolectric 新基建；行为验证归入真机冒烟清单追加项。
