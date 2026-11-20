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
| 2 | `ReaderCjkColumn.quantize()` 接入两条轨 | 新增 + `NativeEpubScreen`/`TextLayoutDraw` | 两端严格对齐 |
| 3 | 缩进 2em、段距仅 `p + p`、行距 13 档 | `ReaderTypography` / 控制中心 | 无双重留白；页底无半行空洞 |
| 4 | CJK `widows/orphans = 1` | CSS + native 分页器 | 中文页底空白 ≤ 1 行 |
| 5 | 触控区矩形模型 + 四布局 + 镜像 + gestureExclusion | `ReaderPageGestures.kt` | 单手不误翻；右开本方向正确 |
| 6 | 手势阈值 px → dp | `ReaderPageGestures.kt` | 三档 density 行为一致 |
| 7 | 修 double-inset | `ReaderSystemBarPolicy.kt` | 顶部间距 = 设定值，旋转不跳 |
| 8 | chrome `AnimatedVisibility` | `ReaderChrome`/各 Screen | 呼出无位移 |

### P1（质感）

| # | 事项 | 要点 |
|---|---|---|
| 9 | 主题单次 `setStyles` | 样式分层合并为一段一次注入；深色图片 `mix-blend-mode: multiply` |
| 10 | 页边标注 sidemark/sideline | 正文列外侧 5dp，RTL 换边 |
| 11 | 高亮 CFI + 文本哈希重锚 | 5 色 + 深色 tint 表 |
| 12 | 划词菜单动作可配置 | 词典/翻译/摘录/朗读 |
| 13 | 控制中心滑杆离散化 + 触感 | 行距 13 档、字距 ±0.1 |
| 14 | 书架卡片按尺寸分档（进度徽章/字号） | `BookCard.kt` 网格与列表一致性 |

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

**遗留**：`CjkColumn.fit()` 已实现并有单测，但尚未接入两个渲染轨的边距计算（需要实机验证 WebView 的可用宽度口径，避免与 Foliate 自身边距叠加）。这是 P0-2 的最后一步。

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
| 收藏夹 / 系列 / 标签 | `app/`、`feature/`、`core/` 内无任何数据模型（仅 Kotlin 标准库 `Collection` 命中） |
| OPDS 目录 | 全仓库无 `opds` 引用 |
| 跨书笔记搜索 | 本轮已补（此前只能按类型过滤） |

教训：能力盘点必须先列**路由表与屏幕文件**再下结论。第 2 部分基于关键词 grep 得出的「缺失」结论不可靠，后续应以 `ShellNavHost` 的路由清单为基线。

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
