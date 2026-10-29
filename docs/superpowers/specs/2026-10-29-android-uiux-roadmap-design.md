# Android UI/UX 优化路线图（现有风格打磨）

> 日期：2026-10-29 · 状态：P0 已确认（本轮与收口批 1/2 并行实施）；P1/P2 为滚动 backlog
> 定位：**不重设计**——以现有 Material 3 + Readest 风格为基底，系统性修交互断层、打磨视觉细节
> 关联：`docs/superpowers/specs/2026-10-29-android-closeout-design.md`（功能收口线）

---

## 1 · 设计原则

1. **组件归位**：屏幕壳留 `:app`，可复用组件沉 `:core:ui`，token 归 `:core:designsystem`（沿用 BookCard.kt:26-43 已确立的 interface inversion 模式）
2. **token 优先**：新增间距/圆角一律走 `SpaceTokens`/`ShapeTokens`/`ColorTokens`，禁止新散落字面量；顺手消除存量字面量
3. **i18n 全覆盖**：所有新 UI 文案走 `t()`，键补进 `src/assets/locales/en.json` + `zh-CN.json`（W6a 惯例）
4. **无障碍随做**：新交互必须带 contentDescription / 语义角色，不攒成后期 a11y 大扫除

---

## 2 · P0 — 交互断层修复（本轮实施）

| # | 断层 | 现状证据 | 改法 | 验收 |
|---|---|---|---|---|
| 1 | 列表视图无长按菜单 | `BookCard.kt:99-113` `BookListRow` 仅 onClick；网格卡（:66-93）有收藏/废纸篓/加书架三菜单 | `:core:ui` 的 `KoodoBookRow` 加 long-press + 菜单槽位（与 `KoodoBookCard` 同模式），`BookListRow` 传入与网格一致的三项操作 | 列表长按弹同款菜单，操作可用且持久化 |
| 2 | 空态无行动 | `LibraryScreen.kt:389-402` `EmptyLibraryHint` 两行灰字 | 空态改居中构图：图标/插画 + 文案 +「导入图书」filled button（拉起 `filesLauncher`）；favoritesOnly 空态保持纯文案 | 空库一键到达文件选择器 |
| 3 | 导入完成反馈弱 | `LibraryScreen.kt:111-124` 完成态仅顶栏 `labelSmall` 文字 | 完成时 Snackbar：「已导入 N · 重复 M · 失败 K」，error 非空时附「查看」action（弹详情 dialog 列失败文件名）；Running 时保留现有顶栏小字+进度条 | 导入结束有可见、可消失、可追查的反馈 |
| 4 | 搜索框无清除 | `LibraryScreen.kt:203-222` 纯 TextField | 查询非空时显示 trailing 清除 IconButton（一键清空并收起键盘）；**不做**展开收起式搜索（布局改动大、收益低） | 输入后出现 ×，点击清空 |

实施注意：#1 动 `:core:ui` 共享组件，需过 `:core:ui` 现有测试（designsystem 128 绿基线不回退）。

---

## 3 · P1 — 视觉与动效打磨（下批，只规划）

| 项 | 内容 | 涉及 |
|---|---|---|
| 转场动画 | NavHost 全局转场：设置叶走水平 SharedAxis，书架→阅读器 fade-through；尊重系统「移除动画」设置 | `ShellNavHost.kt` |
| 列表项动画 | 导入完成/删除/恢复时 `Modifier.animateItem()`（LazyGrid/LazyColumn 通用） | `LibraryScreen.kt`、`TrashScreen.kt` |
| 封面规格统一 | 书卡片封面固定 2:3 比例 + 统一圆角 token；占位色板维持 `ColorTokens.COVER_PLACEHOLDERS` | `:core:ui` KoodoBookCard/KoodoBookRow |
| 圆角 token 审计 | 消除散落的 `RoundedCornerShape(20.dp)` 字面量（LibraryScreen.kt:236,246 等）→ `ShapeTokens` 补 `chip`/`field` 档位 | `:core:designsystem` + 调用点 |
| 大标题滚动 | 书架 `LargeTopAppBar` + `exitUntilCollapsed`（滚动时收拢为普通栏） | `LibraryScreen.kt` |
| 笔记屏筛选 | 3 个 SegmentedButton 改 FilterChip 流 + 各类型计数徽标 | `NotesScreen.kt` |
| 统计屏图表 | 配色统一走 `ChartTokens`；深色模式对比度逐图过一遍 | `StatsRoute.kt` + `:core:designsystem` |

P1 验收口径（实施时定）：截图对比深浅两模式；`-Preader=canvas` 回归不受影响。

---

## 4 · P2 — Backlog（仅登记，不承诺时间）

- 书籍详情/元数据编辑页：长按菜单加「详情」→ 路径、格式、大小、进度、改书名/作者（写回 BookEntity）
- 多选批量操作：长按进入选择模式 → 收藏/移废纸篓/加书架批量执行
- 阅读器 TOC 抽屉美化 + 进度跳转滑条（`NativeEpubScreen` / `NativePdfScreen` 通用组件）
- TalkBack 全屏走查：从书架到阅读器全流程无障碍审计（P0 的 #1-#4 完成后做才有意义）
- 平板/横屏双栏书架（`WindowSizeClass` ≥ Medium 时 list-detail）
- P8-F3：`-Ptarget` 参数 → product flavor（从功能收口规格降级并入本 backlog）

---

## 5 · 交付与验证

- P0 四项随本轮代码交付，验证：`:app:testDebugUnitTest` + `:core:ui`/`:core:designsystem` 测试绿；i18n 键 CI 校验（`scripts/check-locales.mjs`）通过
- P1/P2：本路线图为活文档，实施反馈直接改写对应条目；不做总验收

*Spec self-review（2026-10-29）：无 TBD；P0 每项有文件级证据与验收；P1/P2 明确标注「只规划/仅登记」防止范围蔓延；与收口规格的并行边界（P0 ↔ 批1/2）一致；原则一节约束了 token 与 i18n 惯例避免实施漂移。*
