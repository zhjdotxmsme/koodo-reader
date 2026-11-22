# Android 阅读器 UI/UX 升级文档（2026-11-19）

> 范围：`android/` Compose 原生阅读器 + 书架。
> 目的：① 记录本轮细节优化的审查结论；② 对标 KOReader / Readest / FolioReader 提炼可借鉴能力；③ 给出 P0–P2 落地路线。
> 阅读顺序：第一、二部分是判断依据，第三部分是执行清单。

## 0. 取证说明

| 项目 | 取证方式 | 可信度 |
|---|---|---|
| Readest | 仓库内已内联 `android/app/src/main/assets/foliate/readest-style.mjs`、`paginator.js`、`engine.mjs` | 一手代码 |
| KOReader | `frontend/apps/reader/modules/readerview.lua`（`getTapZones()`）、`frontend/apps/reader/modules/readerpaging.lua`、`frontend/ui/data/css_tweaks.lua` | 一手代码 |
| FolioReader-Android | 源码抓取失败（网络超时 / 代码搜索无结果） | 仅给设计结论，不含代码引用 |
| Readest 双书并读 | 仓库内无 `SplitView` 等实现；仅有单书 spread（`maxColumnCount`） | **该功能不存在**，不得作为需求依据 |

---

# 第一部分 本轮细节优化审查

## 1.1 结论

本轮改动方向正确（排版参数收敛、触控参数可配、选中态反馈、风格 token 化），但存在 **3 个会直接破坏「纸书质感」的单位/算法错误**，以及 5 处像素级不一致。均集中在两条轨道：

- **CSS 轨**（EPUB/FB2/DOCX/HTML，foliate + `ReaderTypography.userStylesheet`）
- **Native 绘制轨**（TXT/PDF reflow，`TextLayoutDraw` + sp/px）

两条轨道目前**对同一份排版意图给出不同的物理结果**，同一本书换格式观感就变，这是"细节粗糙"的结构性原因。

## 1.2 问题分级

| 级别 | 问题 | 位置 | 影响 |
|---|---|---|---|
| Blocker | CSS 轨直接写入物理 px（`letter-spacing: 1.5px`、段距 30px），在 density=3 上是 CSS px 而非 dp | `ReaderTypography.kt` `userStylesheet()` | 中文默认带 8.8% 字距；EPUB 段距是 TXT 的 3 倍 |
| Blocker | `ReaderSystemBarPolicy.contentSize()` 与调用方 `windowInsetsPadding` 双重减 inset | `ReaderSystemBarPolicy.kt` + 各 ReaderScreen | 全屏顶部间距多出一个状态栏高度；旋转/回前台跳一次 |
| Blocker | 中文未量化列宽（缺 `cjk-tailored` 等价逻辑） | `NativeEpubScreen` / `TextLayoutDraw` | 两端对齐每行摊字缝，字号越大越明显 |
| Major | 手势阈值全部是物理 px（`minDx = 48f`、下滑 24f） | `ReaderPageGestures.kt` | xxhdpi 上等于 16dp / 8dp，误翻页 + 工具栏乱弹 |
| Major | 触控区只有 x 轴三等分，无 y 轴/四布局/RTL 镜像 | `ReaderPageGestures.onTap` | 单手误触；右开本方向错误 |
| Major | 段距对每段都加（应为 `p + p`） | 排版参数应用层 | 段首「缩进 + 段距」双留白；页底空洞 |
| Minor | 列表视图卡片不显示进度徽章（网格有） | `BookCard.kt` | 一致性 |
| Minor | 旧版 packed-ARGB 高亮在深色下被统一映射为 dark amber | `HighlightPalette.paintArgb` | 自定义色丢失 |

## 1.3 修复方向（与第三部分 P0 对应）

1. 单位单一真相：`css px ≡ dp`，物理 px 只出现在 native 绘制轨。
2. 缩进/段距用 `em`，字距用绝对 px（Readest 与 KOReader 一致的分工）。
3. 列宽量化到 `n × fontSize`（KOReader `cjk-tailored`）。
4. 所有阈值 `px → dp`，并在 density 2.0/2.75/3.5 三档回归。

---

# 第二部分 可借鉴功能比对

图例：✅ 已有 ｜ ⚠️ 部分/需确认 ｜ ❌ 无 ｜ ★ 借鉴价值（★～★★★）

## 2.1 排版引擎

| # | 能力 | KOReader | Readest | 本项目 | 价值 |
|---|---|---|---|---|---|
| 1 | CJK 列宽量化（`-cr-hint: cjk-tailored`） | ✅ | —（浏览器负责） | ❌ | ★★★ |
| 2 | 悬挂标点 `hanging-punctuation: allow-end last` | ✅（CRE 内置） | ✅ | ❌ | ★★ |
| 3 | 语言感知孤行控制（CJK 降为 1/1） | ⚠️ 用户开关 | ✅ `:lang(zh/ja/ko)` | ❌ | ★★ |
| 4 | 段落结构判定（纯行内 div 才算段落） | — | ✅ `:has()` | ⚠️ | ★★★ |
| 5 | 段首图片/列表内段落取消缩进 | ⚠️ | ✅ `p.leading-image`、`li p` | ❌ | ★★ |
| 6 | 首行缩进 0/1/2 字 + 首段不缩进 | ✅ `p:first-child`/`p + p` | ✅ `text-indent: 2em` | ⚠️ 仅有布尔开关 `firstLineIndent`，无档位、无首段豁免 | ★★ |
| 7 | 行距离散档位（0.9–1.5，步进 0.05） | ✅ 13 档 | ✅ 倍率 | ⚠️ 连续滑杆 1.0–2.0 | ★★ |
| 8 | 字距设置（绝对 px，中文默认 0） | — | ✅ | ❌ | ★ |
| 9 | 上下标不改变行高（`strut-confined`） | ✅ | ✅ | ❌ | ★★ |
| 10 | 页内脚注（in-page footnote，页底显示注释原文） | ✅ 默认开启 | — | ❌（有弹窗 `FoliateFootnote`） | ★★★ |
| 11 | ruby 注音 / 割注（warichu） | ✅ | ✅ `getRubyStyles`/`getWarichuStyles` | ❌ | ★ |
| 12 | 竖排（`writing-mode: vertical-rl`） | ⚠️ | ✅ | ❌ | ★ |
| 13 | 替代目录（按 H1–H6 重建 TOC） | ✅ `alt_toc_*` | — | ❌ | ★★ |
| 14 | 每书级样式微调（per-book style tweak） | ✅ | ⚠️ 全局 | ⚠️ 有 custom CSS，但全局生效 | ★★ |
| 15 | 逐属性覆盖出版商样式（overrideLayout） | ✅ | ✅ | ❌ | ★★ |

> **#10 页内脚注是投入产出比最高的一项**：中文古籍/学术书大量使用章末注，弹窗会打断阅读流，KOReader 的做法是把注文按页底排版并保持正文行数不变。

## 2.2 交互与手势

| # | 能力 | KOReader | 本项目 | 价值 |
|---|---|---|---|---|
| 16 | 触控区为**两个矩形**+四种布局（default/left_right/top_bottom/bottom_top）+比例可调 | ✅ `getTapZones()` | ❌ 三等分 | ★★★ |
| 17 | RTL / 右开本仅做一次 x 轴镜像（`x' = 1 - x - w`） | ✅ | ❌ | ★★ |
| 18 | 手势→动作映射表（区域可选动作） | ✅ | ❌ 固定 4 开关 | ★★ |
| 19 | 长按/滑动快进翻页（`flip_steps = {0,1,2,5,10,20,50,100}`） | ✅ | ❌ | ★★ |
| 20 | 双速率手势：`pan.rate = 0.5`（跟手）/ `swipe.rate = 1.0`（翻页） | ✅ | ❌ | ★★ |
| 21 | 相邻页预渲染（`overlap` 像素） | ✅ | ⚠️ | ★ |
| 22 | 翻页动画可选（滑动/淡入/无） | ✅ | ❌ | ★★ |
| 23 | 左右边缘上滑调亮度、下滑调字号 | ✅ | ❌ | ★ |

## 2.3 标注与辅助阅读

| # | 能力 | 来源 | 本项目 | 价值 |
|---|---|---|---|---|
| 24 | **页边标注（sidemark 图标 / sideline 竖线）**，RTL 自动换边 | KOReader `setupNoteMarkPosition()` | ❌ | ★★★ |
| 25 | 高亮 CFI 主键 + 文本哈希重锚 | FolioReader / Kindle 通用做法 | ⚠️ 需确认 | ★★★ |
| 26 | 划词菜单动作可配置（词典/翻译/摘录/朗读） | KOReader / Readest | ⚠️ | ★★ |
| 27 | 词典（mdict 导入） | KOReader | ✅ `DictionaryImport` | — |
| 28 | 划词翻译气泡 | KOReader | ✅ `reader/translate` | — |
| 29 | TTS 朗读 + 句级高亮跟随 | KOReader | ✅ `TtsSettingsRoute` | — |
| 30 | 阅读统计（日历/热力图/时长分布） | KOReader | ✅ `StatsRoute` | ★（补可视化） |
| 31 | 书内搜索结果高亮列表 + 上下文 | KOReader | ✅ `SearchIndex` + `IncrementalBookSearch` + foliate 搜索命中 | ★（补上下文高亮） |

> **#24 页边标注**：解决「笔记记了但读的时候看不见」。KOReader 真实实现是 `note_mark == "sidemark"`（铅笔图标）或 `"sideline"`（竖线），位置 `screen_w - sign_gap - sign_w`，RTL 时翻到左侧——只需在正文列外侧留 5dp 档位即可实现，改动小、感知强。

## 2.4 书架与库

| # | 能力 | 来源 | 本项目 | 价值 |
|---|---|---|---|---|
| 32 | 系列 / 标签 / 收藏夹 | KOReader collection | ❌ 无 series/tag/collection 数据模型 | ★★ |
| 33 | 封面批量获取/替换 | KOReader | ✅ `CoverStore` | — |
| 34 | 排序/筛选持久化 | KOReader | ✅ `LibraryPrefs` | — |
| 35 | 长按多选批量操作 | KOReader | ✅ `LibrarySelection` | — |
| 36 | 书架骨架屏 | — | ✅ `LibraryLoadingSkeleton` | — |

## 2.5 Top 10 借鉴优先级

1. **CJK 列宽量化**（★★★，1 天）— 中文两端的根因
2. **页内脚注**（★★★，3–5 天）— 学术/古籍体验分水岭
3. **页边标注 sidemark/sideline**（★★★，1–2 天）— 让笔记"被看见"
4. **高亮重锚（CFI + 文本哈希）**（★★★，2 天）— 长期可靠性
5. **触控区矩形模型 + 四布局 + 镜像**（★★★，2 天）— 误触与 RTL
6. **段落结构判定**（★★★，1 天）— 垃圾 EPUB 排版噪音
7. **语言感知孤行控制**（★★，0.5 天）— 页底空洞
8. **行距/字距离散档位 + 触感**（★★，1 天）— 可调出"刚好的值"
9. **长按快进 + 双速率手势**（★★，1 天）— 长篇滑动体感
10. **替代目录（H1–H6 重建）**（★★，1.5 天）— 无 TOC 的书

---

# 第三部分 重构方案

## 3.1 单位单一真相

```kotlin
// 新增 ReaderMetrics.kt —— 唯一的单位换算入口
@Immutable
data class ReaderMetrics(
    val fontSp: Float,
    val lineHeight: Float,          // 倍率
    val indentEm: Float = 2f,       // 中文 2 字
    val paraGapEm: Float = 0f,
    val letterSpacingDp: Float = 0f,
    val cjk: Boolean,
) {
    /** WebView 以 CSS px 布局，与 dp 同量纲：CSS 里只允许出现 dp 数值 */
    fun paragraphCss() = buildString {
        appendLine("p, blockquote, dd, div:not(:has(*:not(a,abbr,b,bdi,bdo,br,cite,code,data,dfn,em,i,kbd,mark,q,rp,rt,ruby,s,samp,small,span,strong,sub,sup,time,u,var))) {")
        appendLine("  text-indent: ${indentEm}em;")
        appendLine("  line-height: $lineHeight;")
        appendLine("  letter-spacing: ${letterSpacingDp}px;")
        appendLine("  hanging-punctuation: allow-end last;")
        appendLine(if (cjk) "  widows: 1; orphans: 1;" else "  widows: 2; orphans: 2;")
        appendLine("}")
    }
}
```

## 3.2 CJK 列宽量化

```kotlin
object ReaderCjkColumn {
    /** 列宽量化到字号整数倍，余量转为两侧对称留白 */
    fun quantize(availablePx: Float, fontPx: Float): Pair<Float, Float> {
        if (fontPx <= 0f) return availablePx to 0f
        val cells = floor(availablePx / fontPx).toInt().coerceAtLeast(1)
        val width = cells * fontPx
        return width to ((availablePx - width) / 2f)
    }
}
```

接入点：foliate 的 `--page-width` / paginator `gap`，以及 native 轨 `TextLayoutDraw` 的行宽上限。验收：`available=328, font=17 → 323 + 2.5/2.5`，任意一行两端严格对齐且无字缝。

## 3.3 触控区矩形模型

```kotlin
data class TapZone(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun contains(x: Float, y: Float) = x >= l && x <= r && y >= t && y <= b
    fun mirrored() = copy(l = 1f - r, r = 1f - l)
}

enum class PageTurnLayout { DEFAULT, LEFT_RIGHT, TOP_BOTTOM, BOTTOM_TOP }

object ReaderTapZones {
    fun zones(layout: PageTurnLayout, forwardRatio: Float, backwardRatio: Float,
              rtlMirrored: Boolean): Pair<TapZone, TapZone> {
        val fw = forwardRatio.coerceIn(0.10f, 0.40f)
        val bw = backwardRatio.coerceIn(0.10f, 0.40f)
        val (f, b) = when (layout) {
            PageTurnLayout.LEFT_RIGHT  -> TapZone(1 - fw, 0f, 1f, 1f) to TapZone(0f, 0f, bw, 1f)
            PageTurnLayout.TOP_BOTTOM  -> TapZone(0f, 1 - fw, 1f, 1f) to TapZone(0f, 0f, 1f, bw)
            PageTurnLayout.BOTTOM_TOP  -> TapZone(0f, 0f, 1f, fw)     to TapZone(0f, 1 - bw, 1f, 1f)
            PageTurnLayout.DEFAULT     -> TapZone(0.62f, 0f, 0.88f, 1f) to TapZone(0.12f, 0f, 0.38f, 1f)
        }
        return if (rtlMirrored) f.mirrored() to b.mirrored() else f to b
    }
}
```

配套（缺一不可）：

1. `Modifier.systemGestureExclusion(Rect(...))` 只对翻页区、只在翻页模式启用，否则会吞掉系统返回手势。
2. 所有阈值改 dp：`onHorizontalSwipe(minDx = 24.dp.toPx())`、`revealChromeOnSwipeDown(threshold = 24.dp.toPx())`。
3. 先判方向再判距离：`abs(dx) > abs(dy) && abs(dx) > slop`。

## 3.4 工具栏与沉浸式

```kotlin
AnimatedVisibility(
    visible = chromeVisible,
    enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(160, easing = FastOutSlowInEasing)) { -it / 3 },
    exit  = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 },
) { ReaderTopBar(...) }
```

原则：**正文不参与动画**（禁用 `animateContentSize`），避免字号跳变与页面错位；内容高度只做一次性 inset 计算：

```kotlin
fun contentHeight(windowPx: Int, insets: Insets): Int =
    (windowPx - insets.top - insets.bottom).coerceAtLeast(0)
```

## 3.5 执行清单

### P0（必须）

| # | 事项 | 文件 | 验收 |
|---|---|---|---|
| 1 | 中文 `letterSpacing = 0`；`userStylesheet` 全面 dp + em | `ReaderTypography.kt`、`ReaderFeatureCodec.kt` | 无额外字缝；TXT/EPUB 同字号段距一致 |
| 2 | `ReaderCjkColumn.quantize()` 接入两条轨 ✅ | 新增 + `NativeEpubScreen`/`TextLayoutDraw` | 两端严格对齐 |
| 3 | 缩进 2em、段距仅 `p + p`、行距 13 档 | `ReaderTypography` / 控制中心 | 无双重留白；页底无半行空洞 |
| 4 | CJK `widows/orphans = 1` | CSS + native 分页器 | 中文页底空白 ≤ 1 行 |
| 5 | 触控区矩形模型 + 四布局 + 镜像 + gestureExclusion | `ReaderPageGestures.kt` | 单手不误翻；右开本方向正确 |
| 6 | 手势阈值 px → dp | `ReaderPageGestures.kt` | 三档 density 行为一致 |
| 7 | 修 double-inset | `ReaderSystemBarPolicy.kt` | 顶部间距 = 设定值，旋转不跳 |
| 8 | chrome `AnimatedVisibility` | `ReaderChrome`/各 Screen | 呼出无位移 |

### P1（质感）

| # | 事项 | 要点 |
|---|---|---|
| 9 | 主题单次 `setStyles` ✅ | 样式分层合并为一段一次注入；深色图片 `mix-blend-mode: multiply` |
| 10 | 页边标注 sidemark/sideline ✅ | 正文列外侧 6px 竖条，RTL 换边，两轨同源 |
| 11 | 高亮 CFI + 文本哈希重锚 ✅ | 5 色 + 深色 tint 表（实做为摘要文本兜底，无哈希） |
| 12 | 划词菜单动作可配置 ✅ | 复制/搜索/词典/翻译/笔记，启停可配 |
| 13 | 控制中心滑杆离散化 + 触感 ✅ | 行距 0.05 格（19 档）、字号整像素（14–22px）、字距 ±0.1 |
| 14 | 书架卡片按尺寸分档 ✅ | `BookCardTier`：<140dp 隐藏进度徽章、标题降档 |

### P2（可选）

| # | 事项 | 说明 |
|---|---|---|
| 15 | 页内脚注 | CRE 思路：注文按页底排版，正文行数不变 |
| 16 | 竖排 / RTL 完整支持 | CSS 轨复用 `writing-mode` 分支，native 轨另做 |
| 17 | ruby / 割注 | 搬 Readest CSS，解决字体回退 |
| 18 | 替代目录（H1–H6 重建） | 无 TOC 的 EPUB 救命 |
| 19 | 双书对照阅读 | 两个 paginator + CFI 结构对齐 + 手动钉锚（**无开源参照**） |

---

# 第四部分 风险

1. 列宽量化的余量补偿在窄屏可能不对称：优先偶数格，余量并入单侧或 gutter。
2. 拉丁文不能跟随 CJK 关闭孤行控制，必须按 `:lang()` 分流。
3. `systemGestureExclusion` 有系统配额（单边约 ≤200dp），不可整屏排除。
4. `hanging-punctuation` 依赖 WebView 内核版本，落地前在 `minSdk` 的 WebView 上实测，不可用则降级为标点挤压。
5. 页内脚注改动横跨排版与分页，建议单独迭代，不与 P0 混提。

---

# 第五部分 实施记录

## 5.1 2026-11-19 第 1 轮：同步冲突（数据安全）

> 先于排版落地：这是唯一会**丢用户数据**的问题。

| 改动 | 位置 |
|---|---|
| 进度协议增加可选 `updatedAt`（旧快照仍可解码） | `core/sync/.../SyncSnapshot.kt` |
| 高亮协议增加 `cfi` / `percentage`（原协议**没有位置锚点**，同步过去的高亮是孤儿） | 同上 + `SyncSnapshotCodec.kt` |
| 合并策略 `SyncMerge`：前进直接应用；后退须「双方均有时间戳且远端更新」；否则进冲突列表 | 新增 `core/sync/.../SyncMerge.kt` |
| 进度写入时间戳（`ts:` 前缀），清除时一并清理 | `ReaderProgressPrefs.kt` |
| 拉取改为「计划 → 应用」；笔记/书签改为增量合并（缺则插入，已有只补空缺字段，不覆盖） | `SyncSnapshotBuilder.kt`（重写） |
| 冲突对话框：逐书「本机 xx% / 远端 yy%」，默认选中更靠后一侧 | `BackupViewModel.kt` / `BackupScreen.kt` |
| 单测 12 项（前进/后退/同时戳/无时戳/显式选择/JSON 往返/旧格式） | `core/sync/src/test/.../SyncMergeTest.kt` |

刻意取舍：**不同步 `date`**。手写 JSON 解码器不支持字符串内转义引号，而 desktop 的 `date` 是 JSON-in-string，带上会解析出截断值。

## 5.2 2026-11-19 第 2 轮：排版内核（P0-1/3/4 + 列宽量化）

| 改动 | 位置 | 效果 |
|---|---|---|
| 中文默认字距 `0.5sp → 0` | `CjkTypography.LETTER_SPACING_SP` | 两端对齐不再靠拉字缝，靠字格本身 |
| 新增列宽量化 `CjkColumn.fit()` | 新增 `core/designsystem/.../CjkColumn.kt` | 列宽 = n × 字距推进量，余量均分两侧 |
| 新增 CSS 生成器 `CjkStyleSheet` | 新增 `core/designsystem/.../CjkStyleSheet.kt` | 段落结构判定、`!important` 归属、`:lang()` 孤行分流 |
| `p` 判定扩展为「后代纯行内的 div 也算段落」 | `CjkStyleSheet` | Calibre/多看转出的 `<div>正文</div>` 得到正确缩进，布局 wrapper 不受影响 |
| `:has()` 单列成规则 | `CjkStyleSheet` | 旧 WebView 不支持时只丢该规则，`<p>` 段落仍正常 |
| CJK 关闭孤行控制（`widows/orphans: 1`） | `CjkStyleSheet` | 中文页底不再出现 1–2 行空洞；拉丁文保持 2/2 |
| 段距只作用于段落下侧，且清零 `margin-top` | `CjkStyleSheet` | 作者样式不再叠加，页首不继承上一段间距 |
| 单位归一：设置存 **dp**，native 轨 ×density，WebView 轨 = CSS px | `ReaderFeatureState` / `ReaderTypography` | 同一段距设置在 EPUB 与 TXT 上不再差 3 倍 |
| `PaginatorOptions` 统一由 `ReaderTypography.paginatorOptions()` 构建 | `NativeEpubScreen` | 消除两处重复构造导致的参数漂移 |
| 单测：量化 5 项 + CSS 9 项 + 更新字距断言 | `core/designsystem/src/test` | 锁定契约 |

**遗留**：native 轨已由 `paginatorOptions()` 的 `quantizeColumnToCells` 量化；WebView 轨的接缝 `ReaderCjkColumn.foliateSidePadFor()` 已接入（`NativeEpubScreen` 只对 foliate 容器加对称左右 padding）。**剩余：实机核对 WebView 可用宽度口径**（是否存在滚动条/内边距差），必要时修正反解式——列入真机附录清单。

**未验证**：本轮改动**未运行编译**（按要求暂停打包）。风险点在 `ReaderControlCenterSections` 的滑杆类型与 `NativeEpubScreen` 的调用参数，恢复构建后需先跑 `:core:designsystem:test` 与 `:app:compileWebviewDebugKotlin`。

## 5.3 2026-11-19 第 3 轮：笔记跨书搜索

| 改动 | 位置 |
|---|---|
| `NotesAggregation.search()`：多词 AND、忽略大小写、命中高亮正文/笔记/书签标签/章节 | `NotesViewModel.kt` |
| 笔记页新增搜索框（含清空），无命中时区分「无笔记」与「无匹配结果」 | `NotesScreen.kt` |
| 单测 5 项（大小写、笔记正文、标签、AND 语义、空查询） | `NotesAggregationTest.kt` |

## 5.4 2026-11-20 第 4 轮：生词本（词汇闭环）

| 改动 | 位置 |
|---|---|
| 新增生词本页面：按日期倒序、搜索（词/例句/书名）、删除、点击回到出处书籍 | `WordbookScreen.kt`、`WordbookViewModel.kt` |
| 写入路径 `WordBank.add()`：UUID key + `yyyy-MM-dd` date + 例句 + 章节 | 同上 |
| 阅读器词典弹窗新增「收藏到生词本」，收藏后按钮变「已收藏」 | `NativeEpubScreen.kt` |
| 设置 → 内容源 新增「生词本」入口 + 路由 | `SettingsScreen.kt`、`ShellNav.kt`、`ShellNavHost.kt` |
| 单测 7 项（空词丢弃、排序、desktop 日期载荷归一、搜索语义） | `WordbookAggregationTest.kt` |

设计取舍：**不新建表**。`words` 是桌面 `schema.lock` 里已有的词汇表，直接复用意味着手机收藏的词在备份/恢复与桌面导出里天然可见。桌面 `date` 列可能是 JSON 载荷，读取时用正则归一成 `yyyy-MM-dd` 再排序。

## 5.5 审计更正（重要）

第 2 部分的能力盘点有几处**错误地判为缺失**，实际代码里已实现。逐个核实后的更正：

| 我此前的判断 | 实际情况 | 证据 |
|---|---|---|
| ❌ TTS 没有系统媒体会话 | ✅ **已实现**：`MediaSessionCompat` + 锁屏卡片 + 耳机/车机媒体键 + 前台服务 | `feature/tts/MediaSessionController.kt`、`LockscreenControlsReceiver.kt`、`ForegroundTtsService.kt` |
| ❌ 笔记不能导出 | ✅ **已实现**：CSV / JSON / MD / TXT / HTML 五种格式 | `BackupViewModel.exportData()` + `DataExport` |
| ❌ 缺少连续阅读天数 streak | ✅ **已实现**：`longestStreak`（近一年最长连续天数） | `feature/stats/StatsAggregator.kt:71` |
| （未提及） | ✅ OCR 引擎与按需模型下载已存在 | `feature/ocr/OcrEngine.kt`、`OnDemandModelDownloader.kt` |

**确认为真缺口**（逐一 grep 过）：

| 缺口 | 证据 |
|---|---|
| 生词本 UI | 本轮已补；此前 `words` 表只有备份导入会写 |
| 系列 / 标签 | 无数据模型（仅 Kotlin 标准库 `Collection` 命中） |
| ~~收藏夹~~ | **已存在**：`LibraryPrefs.shelves()`（桌面 `shelfList` 键）+ `ShelfLogic` + 书库书架 chips + 收藏开关 + 加入书架；`favorites` 还带回收站联动 |
| OPDS 目录 | 全仓库无 `opds` 引用 |
| 跨书笔记搜索 | 本轮已补（此前只能按类型过滤） |

教训：能力盘点必须先列**路由表与屏幕文件**再下结论。第 2 部分基于关键词 grep 得出的「缺失」结论不可靠，后续应以 `ShellNavHost` 的路由清单为基线。

## 5.6 2026-11-20 第 5 轮：触控区几何（P0-5/6）+ 系统手势冲突

前四轮都在「内容呈现」。这一轮改「输入」：三个阅读器（EPUB、PDF、漫画）此前各自实现了点击区，语义、阈值和单位都不一致。

| 事项 | 文件 |
|---|---|
| 新增比例式触控区模型：`TapZoneRect` / `TapZoneLayout` / `TapZoneGeometry`（纯 JVM，可测） | `engine/gesture/.../TapZoneRect.kt` |
| 四种布局：`EDGES`（默认，等同旧版）/ `DESKTOP`（九宫格桌面 parity）/ `TOP_BOTTOM` / `BOTTOM_TOP` | 同上 |
| 前后翻页带**宽度独立**（`tapBackFraction`，0 = 跟随 `tapEdgeFraction`），比例夹在 8%–45%，两条带永不重叠 | 同上 |
| RTL 只做一次 `x' = 1 - x` 镜像；满宽带天然是恒等变换，不需要分支 | `TapZoneRect.mirroredHorizontally()` |
| 手势阈值 dp 化：点击容差 8dp、横滑 24dp、长按 500ms | 新增 `ReaderGestureMetrics` |
| 触控区配置新增 `tapZoneLayout` / `tapBackFraction`，旧配置（只有 `tapEdgeFraction`）仍可解码 | `ReaderGestureConfig.kt` |
| 设置页新增布局选择与「上一页区域」独立滑杆 | `ReadingSettingsRoute.kt` |
| EPUB WebView、EPUB Canvas、PDF 翻页、PDF 重排 WebView、漫画宿主全部改走同一套判定 | `NativeEpubScreen.kt`、`TextPageView.kt`、`NativePdfScreen.kt`、`ComicViewerHost.kt` |
| 手势导航冲突：仅排除左右两条翻页带（上限 32dp），chrome 区保留返回手势 | 新增 `ReaderGestureExclusion.kt` |
| 漫画宿主此前**完全忽略**用户设置，固定 1/3 | `ComicViewerHost.kt`（私有 `tapZone()` 已删除） |

### 为什么 8dp / 24dp 这两个数

旧代码是 `minDx = 48f`（px）、`24f`（px）、`24f`（px）。在 xxhdpi 上分别等于 16dp / 8dp：

- 8dp 的「点击容差」意味着手指落下时轻微侧移就把点击判成拖拽，点击失灵；
- 8dp 的「下滑揭示工具栏」意味着几乎任何向下微动都会弹出 chrome，这就是「工具栏乱弹」的另一半原因。

统一为 8dp 容差 + 24dp 横滑 + 24dp 下滑后，三档 density 行为一致。

### 为什么不整体排除屏幕

系统对 edge-back 的排除额度约为**每边 200dp**。整屏排除会让用户无法用返回手势退出书库，属于「修一个 bug 造一个更严重的」。因此只排除两条翻页带，并把宽度上限压到 32dp（读数：实际带略窄于视觉带，不完美但可接受），chrome 区永远保留返回手势。

### 兼容性

- `EDGES` + `tapBackFraction = 0` 的行为与改动前逐像素一致（1/3 边带、中间 chrome），老用户不会感到变化；
- 新增设置项都是可选字段，`ReaderGesturePrefs` 按 `toMap(defaultConfig)` 的键集合读写，缺键即默认值；
- `ReaderPageGestures.onTap(x, width, edge)` / `tapIfNotDrag(...)` 保留为薄封装，既有单测不需要改。

### 待验证（未编译）

- `ReaderGestureExclusion` 使用 `View.systemGestureExclusionRects`（API 29+，已按 `Build.VERSION.SDK_INT` 守卫）；
- Compose 侧通过 `LocalView` 注册排除矩形，理论上等价于 `Modifier.systemGestureExclusion`，但依赖 `AndroidComposeView` 作为触摸目标，需真机确认；
- 新增单测：`TapZoneRectTest`（12 例，engine 模块 JUnit5）、`ReaderPageGesturesTest`（+5 例）、`ReaderGestureConfigTest`（+4 例）。

## 5.7 2026-11-20 第 6 轮：沉浸式边距的两个真 bug

这一轮先核实再动手。原计划里写的「工具栏动画生硬」经查**并不成立**：`ReaderChrome.ReaderTopBar` 早已是 `fadeIn + slideInVertically` 200ms（`MotionTokens.NORMAL` + `KodoMotion.Decelerate`），退出用 `Accelerate`，且 chrome 是**浮层**、不改变内容尺寸（所以不会触发重新分页）。真正的问题在别处，而且是两个会让排版错位的硬 bug。

### bug 1：`contentSize()` 把纵向 inset 减到了宽度上

```kotlin
// 修复前
fun contentSize(widthPx: Int, heightPx: Int, topPx: Int, bottomPx: Int): Pair<Int, Int> =
    (widthPx - topPx) to (heightPx - bottomPx)
```

调用方传的是 `(size.width, size.height, contentInsets.topPx, contentInsets.bottomPx)`。后果：

- 每一页都**窄了约一个状态栏高度**（~30px），而且是随状态栏高度变化的宽度；
- 高度只减了底部 inset，所以**第一行文字压在状态栏下面**；
- 越是大状态栏/刘海屏，错位越明显。

正确形式：每个轴只减自己的 inset。

```kotlin
fun contentSize(widthPx, heightPx, topPx, bottomPx, leftPx = 0, rightPx = 0) =
    (widthPx - leftPx - rightPx).coerceAtLeast(1) to
    (heightPx - topPx - bottomPx).coerceAtLeast(1)
```

### bug 2：Canvas 阅读器把同一份 inset 减了两次

```kotlin
// 修复前
BoxWithConstraints(
    modifier.fillMaxSize().padding(top = contentInsets.top, bottom = contentInsets.bottom),
) {
    val heightPx = (constraints.maxHeight - contentInsets.topPx - contentInsets.bottomPx)...
```

`Modifier.padding` 在内层节点测量之前就已生效，`BoxWithConstraintsScope.constraints` 是**已被 padding 收缩过**的约束。再减一次的结果是：引擎按 `H - 2×(top+bottom)` 分页，却画在 `H - (top+bottom)` 的盒子里 —— 页底出现一条**约 40dp 的空白带**，且每页的字数比实际能放下的少两三行。

修复后：`padding` 负责「画在哪里」，`constraints` 负责「能画多少」，两者各管一件事，不再重复扣减。

### 顺带补上的横向 inset

`ReaderContentInsets` 原来只有 top/bottom。横屏时导航栏跑到侧边、刘海在另一侧，正文会压到导航栏下面。新增 `left` / `right`（`startPx` / `endPx`），取自 `WindowInsets.navigationBarsIgnoringVisibility` 的左右分量：

- 竖屏为 0，行为完全不变；
- 横屏时 foliate WebView、Canvas 轨、PDF 重排与翻页视图统一加 `start`/`end` padding；
- `contentSize()` 同时减去左右，保证分页结果与绘制区域一致。

### 教训

「双重扣减」这类 bug 不会崩、不会报错，只会让页面**看起来排版差一点**——这正是最难被自动化发现的一类。它同时也是上一轮报告的更正：**P0 的「工具栏动画」不需要做，P0 的「沉浸式边距」比我原先描述的更严重**（不是差一点，是宽度和首行都错）。

### 验证

新增 3 个单测（`ReaderSystemBarPolicyTest`）：

- 纵向 inset 只影响高度（回归用例：`1080×2280` − top 96 − bottom 30 → `1080×2154`）；
- 横向 inset 只影响宽度（横屏 `2280×1080` − left 40 − right 30 → `2210×984`）；
- 极端 inset 不会把视口压成 0（最小 1px）。

需在真机确认（未编译）：

1. 竖屏打开 EPUB：首行不再压状态栏，页宽恢复正常；
2. 竖屏打开 TXT：页底空白带消失，每页行数增加；
3. 横屏打开任意格式：正文不再压到侧边导航栏；
4. 旋转屏幕：不出现一次「页面跳动」。

## 5.8 2026-11-20 第 7 轮：外观设置的「实时预览」与抖动的根因

### 先说核实结果

原计划 P1 写的「主题切换应改为单次 `setStyles`」——**桥接层早已是单次快照**：`FoliateHostBridge.setViewSettings(snapshot)` 把 bg/fg/字号/行高/边距/分栏/自定义 CSS 打包成一个 JSON，一次 `runJs("setViewSettings", ...)`。这一点与 Readest 的 `getStyles() → setStyles()` 架构一致，不需要改。

所以问题不在「怎么注入」，而在**注入的频率**和**注入后丢位置**。

### 问题 1：拖滑杆时每帧都在重排整本书

```kotlin
LaunchedEffect(useFoliate, foliateReady, viewSettings) { host.setViewSettings(...) }
```

`Slider` 在手指按住期间每帧都会发出新值，`viewSettings` 每帧都变，`LaunchedEffect` 每帧都重启 —— 也就是**每秒几十次整书重排**。表现就是：字号拖动时文字抖、滚动位置跳、长章节明显掉帧。

KOReader 的做法是面板关闭时才应用；Readest 是每次变更推一次样式表，但它没有 Android 滑杆这种高频输入。两者共同的原则是 **先合并，再应用一次**。

修法利用 `LaunchedEffect` 的取消语义（新值进来会取消上一个协程）：

```kotlin
var appearanceApplied by remember(bookKey, foliateReady) { mutableStateOf(false) }
LaunchedEffect(useFoliate, foliateReady, viewSettings) {
    if (!useFoliate || !foliateReady) return@LaunchedEffect
    val host = foliateBridge ?: return@LaunchedEffect
    if (appearanceApplied) delay(ReaderAppearance.SETTLE_MS)   // 120ms 尾沿
    …
    appearanceApplied = true
}
```

关键细节：**首次应用不加延迟**。否则每次打开书都要多等 120ms 才看到正文。

### 问题 2：PDF 重排模式的「实时预览」根本不实时

```kotlin
// 修复前：样式只在 onPageFinished 注入
webViewClient = object : WebViewClient() {
    override fun onPageFinished(...) { inject(typographyStyle) }
}
```

后果：在 PDF 重排模式下拖字号/行高滑杆，**画面毫无变化**，必须退出重进才生效。用户会认为滑杆坏了。

修法：把「注入」从生命周期回调里拆出来，改成随 CSS 变化就地替换那一个 `<style>` 元素：

```kotlin
LaunchedEffect(reflowCss, features.pdfReflow) {
    val web = reflowWeb ?: return@LaunchedEffect
    if (!features.pdfReflow || state.reflowHtml.isNullOrBlank()) return@LaunchedEffect
    delay(ReaderAppearance.SETTLE_MS)
    web.evaluateJavascript(ReaderAppearance.swapStyleJs(reflowCss), null)
}
```

### 问题 3：换样式时丢失阅读位置

只替换样式、不处理滚动，WebView 会保持原来的**像素**偏移。但字号一变，同一个像素偏移落在完全不同的句子上——读者会觉得「刚才那行跑了」。

`swapStyleJs()` 因此按**比例**保位：

```js
var ratio = scrollTop / (scrollHeight - clientHeight);
/* …换 <style>… */
scrollTop = ratio * (scrollHeight - clientHeight);
```

比例保位不是完美解（段落重排后同一比例可能差半行），但比像素保位好一个量级：像素保位在字号从 16→24 时会直接跳到后面几十行。

### 为什么手写 JS 字符串转义

`ReaderAppearance.jsString()` 自己转义而不是用 `org.json.JSONObject.quote`：这个文件要被 **纯 JVM 单测**覆盖，而 `org.json` 在本地单测里是未实现的 Android stub，一调用就抛异常。转义规则覆盖 `\ " \n \r \t` 以及 `\u2028/\u2029`（ES2019 前会截断 JS 字符串字面量，而中文排版 CSS 里经常出现这两个字符附近的内容）。

### 验证

新增 `ReaderAppearanceTest`（5 例，纯 JVM）：

- 转义 `\`、`"`、换行、制表符、行分隔符；
- 含中文引号的 CSS 经过引用后不残留裸换行；
- 脚本确实只替换一个元素、按比例恢复滚动；
- 自定义元素 id 生效；
- 防抖窗口落在 80–180ms（既能把一次拖动合并成一次，又不至于被感知为卡顿）。

### 仍需真机确认（未编译）

1. 拖动字号滑杆：只在松手后重排一次，不再抖动；
2. 松手后阅读位置仍停在原来那段；
3. PDF 重排模式下拖字号：画面立即变化（此前完全不动）；
4. 深色主题切换：无白闪（WebView 背景色在 JS 之前就已设置，保持现状）。

## 5.9 2026-11-20 第 8 轮：高亮重锚（P0/P1 收尾项）

### 缺陷本身

高亮的主键是 EPUB CFI（ADR-002）。当书文件被重新导入或替换（换封面、换版本、修目录），CFI 指向的 DOM 节点消失，`view.addAnnotation` 拒绝——而旧的注入路径是 **fire-and-forget**：

```kotlin
runCatching { host.addHighlight(cfi, css) }   // 结果被丢弃
```

后果：死锚点**无声消失**，用户永远不知道自己的高亮去哪了。这和同步覆盖进度是同一类问题——「用户资产静默丢失」。

### 修复：主键 CFI + 摘要文本兜底

**JS 侧（`engine.mjs` 新增 `reAnchorHighlights`）**，每个高亮三步：

1. `resolveCFI(cfi)` —— 抛异常才算死（比 `addAnnotation` 更准：能区分「锚点死了」和「章节还没加载」，后者由 foliate 自己在章节渲染时重放标注）；
2. 死锚 → 在**同一章节**内用保存的摘要在原文中定位（`findTextRange`，空白弹性匹配），用 `view.getCFI` 生成新 CFI 并重新画；
3. 修不回的进 `failed`——**绝不删除**，用户数据只增不减。

关键设计决定：

- **只在原章节内找**（从 CFI 的 `!/N` 步长解析 spine index，点 CFI 和 range CFI 都适用）。跨章节的文本匹配几乎必然意味着「这不是同一本书」；
- **空白弹性匹配用 `\s*` 而不是 `\s+`**——Node 测试抓出来的：`<p>a</p><p>b</p>` 的 textContent 是 `"ab"`，块级边界之间根本没有空白；
- needle 截到 80 字符、最少 4 字符，短摘要有歧义时宁可失败也不误锚。

**Kotlin 侧三段，职责分开：**

| 层 | 文件 | 职责 |
|---|---|---|
| 纯逻辑 | `shell/HighlightReAnchor.kt` | 从笔记构造请求；把 remap 应用回笔记（写回新 CFI、清掉死的 DOM `range`） |
| 桥接 | `epubhost/FoliateHostBridge.reAnchorHighlights()` | 阻塞调用 + 解析报告（超时按条数 2s/条，夹在 5–60s） |
| 集成 | `NativeEpubScreen` | `foliateReady` 后一次拉取 → 重锚 → 写回 `noteDao().upsert` |

写回是幂等的：修过的笔记 CFI 已经是新的，下一轮 remap 匹配不到它，不会无限循环。集成点在 `foliateReady = true` 之后——那时 `open()` 已完成，resolve 失败才真的意味着锚点死了（章节未加载的场景 foliate 会在渲染时自行重放）。

### 一个更正

上一轮把这项标为「CFI 主键 + **文本哈希**兜底」。实做后发现**哈希是多余的**：WebView 轨里直接拿摘要文本做定位更准（软连字符、全半角都由 DOM 层处理），哈希只该出现在 native 文本轨。所以最终形态是「CFI 主键 + 摘要文本兜底」，没有哈希。

### 验证（这一轮是全绿的）

**JS：`node scripts/test-foliate-host.js` → 65/65 通过**（原有 51 + 新增 14）：

- `spineIndexOf`：点 CFI / range CFI / 奇数步长 / 无 indir / 垃圾输入；
- `findTextRange`：跨文本节点 + 空白弹性、不存在的句子、过短 needle、null doc；
- `reAnchorHighlights`：健康的锚 → `anchored`；死锚 + 文本可找 → `reanchored[old→new]` 且留在原章节；死锚 + 文本找不到 → `failed`；空 CFI 忽略；颜色透传；空输入干净报告（用注入的 fake view + fake DOM，不需要 WebView）。

**Kotlin（13 例，`:app` JVM 测试，待恢复构建后跑）：**

- `HighlightReAnchorTest`（6）：跳过无 CFI 笔记、颜色回调、remap 写回 + 清 `range`、幂等、退化 remap 丢弃、first-wins；
- `ReAnchorReportParsingTest`（4）：完整报告、空/坏载荷降级、线上丢 identity remap、缺数组解析为空（`org.json` 已有 `testImplementation`，JVM 可跑）。

### 真机验收

1. 打开一本有高亮的 EPUB：所有高亮正常显示；
2. （构造性测试）修改书文件后重新导入：高亮仍在原位或附近，且**笔记列表里 CFI 已更新**；
3. 彻底对不上的书：高亮缺失但笔记完整（可在笔记页看到原文），不崩溃、无静默删除。

---

## 5.10 2026-11-22 第 9 轮：P0/P1 剩余项落地

| Task | 清单项 | 改动 | 关键位置 |
|---|---|---|---|
| 1 | **P0-2** WebView 轨列宽量化收尾 | `ReaderCjkColumn.foliateSidePadFor()` 反解「页面 padding 会缩小 gap 的百分比基数」这一循环依赖，把 foliate 列宽量化到整字格；`NativeEpubScreen` 只对 foliate 容器加对称左右 padding | `core/designsystem/.../CjkColumn.kt`、`NativeEpubScreen.kt` |
| 2 | **P1-13** 滑杆离散化 + 触感 + 字距 | 行距 snap 到 0.05 格（19 档）、字号 snap 到整像素（14–22px，7 档）、新增字距 ±0.1（0.01 步长）；跨档触感 `TextHandleMove`；字距贯通 native 与 WebView 两轨 | `ReaderFeatureState.kt`、`ReaderTypography.kt`、`ReaderControlCenterSections.kt`、`ReadingSettingsRoute.kt` |
| 3 | **P1-12** 划词菜单动作可配置 | `SelectionMenuAction` enum + SharedPreferences（逗号串，未知项丢弃、去重、空集回落默认）；菜单次行动作由设置驱动 | `shell/SelectionMenuActions.kt`、`NativeEpubScreen.kt`、`ReadingSettingsRoute.kt` |
| 4 | **P1-14** 书架卡片分档 | `BookCardTier` 按 tile 实测宽度分档（<140dp 为 COMPACT）；COMPACT 隐藏进度徽章、标题降为 `bodyMedium` | `core/ui/.../BookCardTier.kt`、`KoodoBookCard.kt` |
| 5 | **P1-10** 页边标注 | `SidemarkGeometry`：3px 竖条贴正文列外侧 6px，RTL 镜像到右，越界向内夹取；native 轨画在 Canvas、foliate 轨画在 Overlayer 的 SVG 组内（与高亮同生命周期） | `engine/annotate/.../SidemarkGeometry.kt`、`TextLayoutDraw.kt`、`overlayer.js`、`index.html` |

### 与原计划的偏差（实做更正）

1. **P0-2 的算式**：计划里的 `viewportPx - 2*margins` 口径不成立——foliate 的 `gap` 是页面宽度的百分比（`engine.mjs` 固定 5%），分页器内部还要再减 `margin` 与 `gap/2`。实际可用宽度是 `page*(1-g/(1-g)) - margin`，而加 padding 又同时缩小 `page`，所以 padding 必须反解：`page = (column + margin) / (1 - g/(1-g))`。另外跨度双页模式下每页指标不同（gutter 取 1/4 边距），padding 本身还可能把双页压回单页，因此该情形直接返回 0。
2. **P1-10 的接线候选**：计划假设高亮是 DOM `<span>`（候选 A：稳定 class；候选 B：`data-koodo-hl` 属性）。实际 foliate 轨的高亮是 `Overlayer.highlight()` 画在 over-layer SVG 上的矩形，与 DOM 无关，A/B 都不成立。改为在 `Overlayer.highlight` 增加 `sidemarkWidth/Gap/Rtl` 选项，把竖条作为同一 `<g>` 的最后一个子节点——重绘、删除随高亮一起走，不需要额外记账。竖条透明度独立于 `.3` 的色带（否则 30% 的竖条等于看不见）。
3. **页边标注的取数**：不做「仅带文字的笔记才画」的特判。竖条颜色由该页的色带 map 直接派生（`SidemarkGeometry.tickColors`），避免重复解析 CFI，也从结构上保证「竖条不可能指到色带不在的行」。

### 验证

- `node scripts/test-foliate-host.js` → **74/74**（新增 7 条：竖条位置/宽度/高度/不透明度、RTL 镜像、窄边距夹取、无宽度时不生成空组）；
- `:core:designsystem:test` → `ReaderCjkColumnTest` 8 例（含「padding 反解后列宽确实是整格」「双页/退化输入返回 0」）；
- `:engine:annotate:test` → `SidemarkGeometryTest` 6 例（含 NaN 与越界钳制、深浅两档 alpha）；
- `:core:ui:testDebugUnitTest` → `BookCardTierTest` 3 例；
- `:app:testNativeHostDebugUnitTest` → **388 例，0 失败**（含 `SelectionMenuActionsTest` 5 例、`ReaderFeatureStateTest` 新增 5 例）。

**未验证（真机）**：foliate 轨 padding 的实机观感与 CSS 宽度口径（是否有滚动条吃掉 1–2px）、触感反馈在真机上的强度、页边标注在窄边距大字号下的观感——三项都需设备可用时单独核对。

---

# 附录：源码参照

| 主题 | 参照位置 |
|---|---|
| 段落/行高/缩进/悬挂标点 | `android/app/src/main/assets/foliate/readest-style.mjs:607` |
| 样式分层与组合 | `readest-style.mjs:975`（`getStyles`） |
| CJK 孤行降级 | `readest-style.mjs:738`（`getCJKLineFlowStyles`） |
| 触控区几何 | KOReader `frontend/apps/reader/modules/readerview.lua:1365` |
| 翻页手势/预渲染 | KOReader `frontend/apps/reader/modules/readerpaging.lua` |
| 中文排版微调 | KOReader `frontend/ui/data/css_tweaks.lua`（`cjk_tailored`、`paragraph_indent`、`widows_orphans_*`） |
| 页边标注 | KOReader `readerview.lua`（`setupNoteMarkPosition`） |
