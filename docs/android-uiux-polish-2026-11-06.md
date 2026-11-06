# Android UI/UX 优化（2026-11-06）

范围：`android/` 原生壳，只做界面与交互层，不改业务/引擎逻辑。

## Design Token 与主题
- `core/designsystem/MotionTokens.kt`（新）：FAST=120 / NORMAL=200 / SLOW=300 / SHIMMER=900 / HINT=2000，收敛全应用动效时长。
- `ColorTokens` 新增 `scrim` 槽位（`KoodoColors` 两处 `Color(0xFF000000)` 字面量移除）；`ColorTokensTest` 槽位数 25→26，scrim 两侧同值属设计意图。
- `core/ui/theme/KoodoAnimatedScheme.kt`（新）：`rememberAnimatedScheme(target)` 300ms 逐角色插值，主题切换不再硬切；中断从当前插值续播。
- `KoodoTheme`：SideEffect 同步 windowBackground 与状态栏/导航栏图标明暗（修复浅色模式深色系统栏）；`LocalKoodoSpacing` 零消费已删除。
- res：`window_background` 对齐 token（day `#F8F6F2` / night `#16181D`），新增 `values-night/` 同名资源；`windowLightStatusBar` day=true/night=false；`brand_primary` 对齐 `#3A6EA5`。
- `NativeShellActivity.attachBaseContext` + `ShellAppearancePrefs.readAppThemeModeSync`：首帧前按持久化主题选 -night 资源，消除冷启动闪色。
- `AppearanceRoute`：预览色块改用 `ColorTokens` 真实值（SYSTEM 行显示当前主题解析结果）；三组选中态统一 12dp 圆点；`CornerRadius(8f)` 像素改 dp。
- `ShellChrome`：searchCorner/groupCorner 走 `ShapeTokens`。

## 共享组件（:core:ui）
- `Shimmer.kt`（新）：`Modifier.shimmer()` 渐变扫描骨架。
- `ErrorState.kt`（新）：图标+文案+可选重试按钮（≥48dp），字符串由调用方传入。
- `KoodoBookCard/KoodoBookRow`：`coverLoading` 占位 shimmer、封面 Crossfade 三态（Loading/Ready/Failed）、多选 `Role.Checkbox`+`selected` 语义。

## 书架页
- `LibraryViewModel`：`loadError` + `retryLoad()`（books 流 `catch`+`retryTick` 重启查询）；`pendingTrash` + `undoDelete()/commitDelete()` 软删撤销窗口。
- `LibraryScreen`：Error 分支接 `ErrorState` 重试；空态按 搜索无结果/收藏为空/书架为空 区分文案，仅真空书架显示导入按钮；多选顶栏 AnimatedVisibility（200ms 进/120ms 出）；`BackHandler` 先退多选；删除后 Snackbar「Undo」；排序改 `ModalBottomSheet`（RadioButton 行 + 方向图标，≥48dp 行高）；FilterChip 20dp 字面量→CircleShape。
- `BookCard`：封面三态 + Crossfade；长按直接进入多选；菜单触发改显式 MoreVert 按钮（48dp，BottomEnd 避开收藏星）。
- `LibraryLoadingSkeleton`：alpha 脉冲改 shimmer。

## 阅读页
- `NativeEpubScreen`：chrome 200ms 淡入淡出+位移，TopAppBar 透明+scrim 渐变遮罩（内容仍不吃 Scaffold padding，重分页约束保留）；返回链 选区>目录>chrome>双击退出（2s 窗口 + Snackbar 提示）；章节切换顶部 pill 2s 自动消失（仅接有现成章节索引的轨道）；高亮/笔记保存 Snackbar；打开失败 ErrorState+retryKey 重试；选区菜单 Surface→ModalBottomSheet（动作集不变）；硬编码 "Back" 走 t()。
- `NativePdfScreen`：同款 chrome 动画+渐变遮罩（顶栏/PagerBar）；返回链；冷启动失败 ErrorState+retry（中途错误仍 Toast）；引擎不可用页 i18n 化（非瞬时错误，不设重试）。
- `AdaptiveReaderLayout`：大屏目录可收起（`sidebarOpen` + 200ms 横滑，TocSidebar 关闭按钮）。
- `TextScrollView`：目录跳转 `scrollToItem`→`animateScrollToItem`。
- `ReaderSettingsSheet`：四个背景色块 48dp 触达盒 + contentDescription。

## 验证
- `:app:compileWebviewDebugKotlin` 通过（含 :core:ui / :core:designsystem / :core:data）。
- `:core:designsystem:test` 全绿。
- `:app:testWebviewDebugUnitTest`：315 项中 314 通过；唯一失败 `PdfReflowHighlightsTest` 为 `org.json.JSONObject not mocked` 环境型既有失败，与本次改动无交集。
- 构建环境：`JAVA_HOME=D:\jdk-17\jdk-17.0.13+11`（AGP 8.2.2 需 JDK 17）。

## 新增 i18n key（en/zh-CN 已补译并同步，其余语言回退英文）
`Can't load library`、`Retry`、`Moved to trash`、`Undo`、`More options`、`No matching books`、`This shelf is empty`、`Ascending`、`Descending`、`Highlight saved`、`Note saved`、`Press back again to exit`、`White background`、`Paper background`、`Dark gray background`、`Black background`、`Tap left or right to turn pages`、`Tap the center to toggle the toolbar`、`Got it`，以及打开失败/引擎不可用提示若干。

## 追加完成（同日第二轮）
- 翻页过渡：`AnimatedContent` 200ms 轻微位移+淡入淡出，方向感知。原生文本轨（`NativeTextReaderScreen`，`ReaderView` 作 targetState，出场帧渲染旧页）+ EPUB 旧 Canvas 轨（页码比较定方向，行/高亮按页码分别计算）。**PDF 轨不加**：缩放/连续滚动模型下双帧位图成本高、收益低。
- `TextPageView` 横滑翻页：复用 `ReaderPageGestures.onHorizontalSwipe` 纯函数，与 EPUB/PDF 同判定（累计 48f 阈值）。
- 首次阅读引导：`ReaderFirstRunGuide`（点按分区两条提示 + 点任意处/「Got it」跳过），SharedPreferences 一次性标志，EPUB/PDF 入口共用。
- 字号滑杆步进统一：设置页 27→17（0.1 步长，与阅读内 sheet 一致）。
- 新 i18n key 已补 en/zh-CN 并经 `scripts/sync-locales-android.js` 同步（`--check` 通过）。

## 未做（后续）
- 系统栏显隐动画（`WindowInsetsAnimationControllerCompat` 需真机验证，列 P2 暂缓）。
- Canvas 正文 TalkBack 语义（引擎级改造）。
