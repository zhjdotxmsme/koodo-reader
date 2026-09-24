# P6：阅读统计（对齐 `/stats`）+ OCR（ML Kit 按需下载）设计

> 卡片：`t-muexn6c0-3dab3i`　workspace：`E:\open-source\koodo-reader`
> worktree：`.worktrees/p6-stats-ocr`　分支：`task/p6-stats-ocr-muexn6c0`
> 基准：`docs/android-native-migration.md` 附录 A.4「阅读统计 → `feature/stats`」「OCR → ML Kit（按需下载）」

本卡交付两个**纯新增**的原生模块（不修改任何既有文件的功能逻辑）：

| 模块 | 目录 | 纯 JVM 核 | Android 层 | JVM 单测 |
|---|---|---|---|---|
| 阅读统计 | `android/feature/stats/` | `StatsModels.kt` / `StatsAggregator.kt` / `ReadingSessionRepository.kt` | `ui/StatsScreen.kt`、`ui/StatsViewModel.kt`、`platform/StatsDatabase.kt` | **34**（3 个测试类） |
| OCR | `android/feature/ocr/` | `OcrEngine.kt` / `OnDemandModelDownloader.kt` / `OcrSearchRepository.kt` | `platform/MlKitOcrProvider.kt`、`platform/MlKitModelDownloader.kt`、`platform/OcrIndexDatabase.kt` | **29**（3 个测试类） |

合计 **63 个 JVM 单测全绿**（运行方式见 §6）。两个模块各一个 `build.gradle`，均为 Android Library。

## 1. 结论摘要

1. **统计口径逐项对齐桌面 `/stats`**，包括三处「桌面 bug-for-bug」行为（§3.2），并在 `StatsAggregator` 内以常量 + 注释固化，防止后续被"顺手修好"导致两端数字不一致。
2. **阅读时长落库为 `reading_sessions`**（Room entity + DAO）：一条记录 = 一个 `(书, 本地日, 会话)` 的连续阅读段，跨零点自动按天切分，空闲（>120 s 无 tick）不计时，聚合因此退化为一次纯函数折叠（可离线单测）。
3. **OCR 用 ML Kit 文本识别 v2 的「按需（Play services）」变体**：模型走 Google Play services 动态下载（每脚本每 ABI ≈260 KB，APK 内 **0 个 ML Kit `.so`**），而不是内置变体（每脚本每 ABI ≈4 MB，含 native 库 → 16 KB 页对齐风险）。16 KB / `.so` 体积评估见 §5。
4. **扫描页 OCR 形成检索闭环**：`扫描页 → 模型可用性检查/按需下载 → 识别 → 归一化 → 落 `ocr_pages` 索引 → 检索命中页码`。桌面 Web 端只做「选区→文字」（`esearch-ocr` + `onnxruntime-web`，无索引），原生端因扫描 PDF 无文本层，额外做了按页索引。
5. 与 **P6 词典** 共用 `OnDemandDownloader` 接口名与 6 条行为契约；两个模块各自保留独立声明，**归属由 main thread 在 settings.gradle 同时注册时决定**（§5.4）。
6. `android/settings.gradle`、`android/app/build.gradle`、`android/build.gradle`、`package.json`、`main.js` **未做任何修改**（本卡硬约束）；接线指令落在 §7，由 main thread 执行。

## 2. 目录与文件

```
android/feature/stats/
├── build.gradle                                  (com.android.library + kotlin.android + ksp + compose)
└── src/
    ├── main/kotlin/com/koodoreader/feature/stats/
    │   ├── StatsModels.kt        IsoDay（自研日历）、ReadingSession、DayPoint、HeatmapCell、StatsSnapshot
    │   ├── StatsAggregator.kt    时长/字数/进度/日历聚合 + 桌面 formatTime/热力图色阶
    │   ├── ReadingSessionRepository.kt  Room @Entity/@Dao + 存储接口 + 内存实现 + 计时状态机
    │   ├── ui/StatsScreen.kt     Compose 复刻 /stats（卡片 / 30 天柱线切换 / 52 周热力图 / 图例）
    │   ├── ui/StatsViewModel.kt  StatsUiState + chartTab
    │   └── platform/StatsDatabase.kt  Room @Database + 时区/仓库接线
    └── test/kotlin/com/koodoreader/feature/stats/
        ├── IsoDayTest.kt（6）  ReadingSessionRepositoryTest.kt（13）  StatsAggregatorTest.kt（15）

android/feature/ocr/
├── build.gradle                                  (com.android.library + kotlin.android + ksp)
└── src/
    ├── main/kotlin/com/koodoreader/feature/ocr/
    │   ├── OcrEngine.kt              OcrScript（脚本→模型/构件/体积）、OcrEngine 接口、OcrTextNormalizer
    │   ├── OnDemandModelDownloader.kt  OnDemandDownloader 契约 + ModelPack + DownloadState/Result + 内存实现
    │   ├── OcrSearchRepository.kt    Room @Entity/@Dao(ocr_pages) + 索引存储 + 识别→归一化→索引→检索闭环
    │   └── platform/MlKitOcrProvider.kt       ML Kit TextRecognition.getClient(...) 适配（OcrEngine 实现）
    │       platform/MlKitModelDownloader.kt   ModuleInstallClient 按需下载（OnDemandDownloader 实现）
    │       platform/OcrIndexDatabase.kt       Room @Database + 接线
    └── test/kotlin/com/koodoreader/feature/ocr/
        ├── OcrEngineTest.kt（9）  OnDemandModelDownloaderTest.kt（7）  OcrSearchRepositoryTest.kt（13）
```

分层规则（两个模块一致）：**`ui/`、`platform/` 之外的代码零 Android 依赖**，可被纯 JVM 测试直接编译执行；Android 相关代码只做 IO/Compose/ML Kit 适配。

其它改动：
- `src/assets/locales/en.json` + `zh-CN.json`：新增 1 个 key `"Word count"`（中文「字数」），并 `node scripts/sync-locales-android.js` 同步到 `android/app/src/main/assets/locales/*`（`--check` 通过）。其余 i18n 全部复用桌面既有 key（见 §3.4），不新增命名空间。
- **未改动**：`android/app/build.gradle`、`android/settings.gradle`、`android/build.gradle`、`package.json`、`main.js`、`android/core/**`、`scripts/**`。

## 3. 阅读统计：口径对齐 `/stats`

基准文件：`src/pages/stats/component.tsx`、`src/pages/stats/interface.tsx`、`src/pages/stats/stats.css`。

### 3.1 指标对齐表

| # | 桌面实现（行号） | 桌面口径 | Android 对应 | 单测 |
|---|---|---|---|---|
| 1 | `component.tsx:47-60` | `已读图书数` = `books` 表中 `recordLocation` 非空的书籍数 | `StatsSnapshot.totalBooks`，由调用方注入（`StatsAggregator.aggregate(booksRead=…)`） | `StatsAggregatorTest.total seconds and active days are summed per day` |
| 2 | `component.tsx:62-71` | `总时长` = `readingStats` 所有日期 `dayStats[].seconds` 之和 | `reading_sessions.seconds` 按 `day` 求和 | 同上 + `ReadingSessionRepositoryTest.ticks are credited…` |
| 3 | `component.tsx:73-90` | `连续阅读天数` = 从今天往前 365 天窗口内**最长**连续有阅读的天数（`currentStreak` 遇空日清零，取 `max`） | `StatsAggregator.longestStreak(secondsByDay, today)`，`STREAK_WINDOW_DAYS = 365` | `longest streak counts consecutive days…`、`an old run wins…`、`days outside the 365-day window…` |
| 4 | `component.tsx:92-97` | `日均时长` = `round(总时长 / 有阅读的天数)`（**分母是活跃天**，不是自然天） | `avgDailySeconds`，`activeDays = secondsByDay.count { it.value > 0 }` | `total seconds and active days…`、`daily average is zero…` |
| 5 | `component.tsx:99-109` | `近30天` = 固定 30 项，`i=29..0`，标签 `M/D`，图表值 `minutes = round(seconds/60)` | `StatsAggregator.last30Days` + `DayPoint.minutes` | `last 30 days is a fixed oldest-first window with M D labels` |
| 6 | `component.tsx:111-123` | 热力图 = `today - 51*7` 天再回退到**周日**，逐日到「今天」为止 | `StatsAggregator.heatmap`，`HEATMAP_WEEKS = 52`，`start = today - 51w - dayOfWeek` | `heatmap starts on the Sunday 51 weeks back and ends today` |
| 7 | `component.tsx:166-203` | 热力图列 = 补齐尾部空单元后每 7 天一组；月标签：首列恒标 + 含当月 1 号的列 | `heatmapWeeks()` / `heatmapMonthAnchors()`（返回 `IsoDay`，UI 用 `DateFormatSymbols` 本地化） | `heatmap columns are padded to full weeks…`、`month anchors label the first column…` |
| 8 | `component.tsx:151-158` | 单元格色阶：`0 / <300 / <900 / <1800 / else` → 5 级 | `StatsAggregator.heatmapLevel` + `HEATMAP_COLOR_THRESHOLDS` | `heatmap levels follow the desktop thresholds` |
| 9 | `component.tsx:202` | 图例档位：`[0, 200, 600, 1200, 2400]` | `HEATMAP_LEGEND_SECONDS`（**与色阶不同**，见 §3.2） | `legend keeps the desktop levels…` |
| 10 | `component.tsx:143-149` | `formatTime`：`<60 → "Ns"`；否则 `h>0 ? "Hh Mm" : "Mm"`（≥60 s 丢弃秒） | `StatsAggregator.formatTime` | `formatTime matches the desktop rendering` |
| 11 | `component.tsx:284-301, 151-158` | 暗色/亮色配色、图表描边 `#ffb066/#ff6b1a`、网格 `rgba(…,0.08)` | `StatsPalette.of(dark)`（逐色值对齐），`heatmapColor()` | 视觉对齐（无单测，UI 层） |
| 12 | `component.tsx:151-158`, `stats.css` | 热力图色板（亮 `#9be9a8/#40c463/#30a14e/#216e39`；暗 `#0e4429/#006d32/#26a641/#39d353`） | `StatsPalette.heatmap`（逐色值一致） | 同上 |
| 13 | 卡片文案（i18n） | `Reading Stats` / `Books read` / `Total reading time` / `Reading streak (days)` / `Daily average` / `Last 30 Days` / `Bar Chart` / `Line Chart` / `Reading Activity` | `StatsScreen(t = …)` 使用**同名 key**（Android 目录是桌面 JSON 的逐字拷贝，ADR-004） | — |

### 3.2 桌面 bug-for-bug（Android 照搬，勿"修复"）

1. **"连续阅读天数" 实为"近一年最长连续天数"**：桌面从今天倒扫 365 天并保留 `max`，因此"100 天前结束的 5 连读"会压过"今天仍在延续的 2 连读"；且**不要求今天有阅读**。`StatsAggregatorTest.an old run wins when it is longer than the live run` 锁定该语义。
2. **热力图色阶 ≠ 图例档位**：单元格用 `300/900/1800` 分档，图例却用 `200/600/1200/2400` 渲染 5 个色块 —— 桌面上"图例第 2 格"与"实际 300 s 的格子"颜色并不对应。Android 保留两者（`HEATMAP_COLOR_THRESHOLDS` / `HEATMAP_LEGEND_SECONDS`），并在 `legend keeps the desktop levels…` 中断言。若后续要修正，需桌面 + 原生同步改。
3. **`日均时长` 分母是"活跃天"**：一段只在一天读了 10 分钟的历史，日均显示 10 分钟而非"总时长/历史天数"。

### 3.3 Android 新增指标（桌面 `/stats` 暂无卡片）

卡片要求「时长/字数/进度/日历」，其中后两者的口径为：

- **字数**：`StatsAggregator.wordCount(text)` —— CJK（汉字/假名/谚文）**按字计数**，其余按空白分词（含 `'`/`’` 撇号），与桌面阅读进度面板的 CJK 计数习惯一致；会话记录 `words` 字段，聚合为 `totalWords`。UI 卡片文案使用新增 key `"Word count"`。
- **进度**：桌面 `recordLocation.percentage` 是 **0..1 小数**（`progressPanel/component.tsx:53` 里 `*100` 展示），`StatsAggregator.averageProgress` 取**已开始书籍**（>0）的均值并 `round(百分比, 2)`；0 进度书籍不计入，避免"未打开的书把均值拉低"。UI 卡片文案复用桌面既有 key `"Reading progress"`。

### 3.4 UI 对齐要点（`StatsScreen.kt`）

- 顶部标题 + 关闭按钮（桌面右上 `stats-close-btn`）；4 张卡片 2×2 栅格（桌面 4 列，移动端 2 列，与 `stats.css` 的 `@media (max-width:900px)` 一致），另加「字数 / 阅读进度」两张卡片。
- 近 30 天：Canvas 手绘，柱状/折线（含渐变面积）切换按钮，X 轴每 5 个点标一次（桌面 `interval={4}`），Y 轴 `Nm`，网格仅横向（桌面 `vertical={false}`）。
- 热力图：52 列 × 7 行、格 13 dp / 间距 3 dp（对齐 `--heatmap-cell-size/--heatmap-gap`），列区 `horizontalScroll` 复刻桌面 `overflow-x:auto`；左侧 Mon/Wed/Fri 标签；底部 5 格图例。
- 所有用户可见文案走 `t(key)` 参数注入（模块不依赖 `:app`，由 shell 传 `LocalI18n.t`），key 与桌面 JSON 完全同名。

### 3.5 计时状态机（`ReadingSessionRepository`）

| 规则 | 实现 | 单测 |
|---|---|---|
| 只在两 tick 间隔 ≤120 s 时计时 | `idleTimeoutMillis`（桌面 `ReadingTimeUtil.start/stop` 同样丢弃长时间挂起） | `an idle gap is not credited and restarts the session` |
| 跨零点按本地日切分 | `splitAcrossDays(start, end, zoneOffset)`（逐段用该时刻的时区偏移，DST 正确） | `a tick crossing midnight is split…`、`splitAcrossDays honours the local offset` |
| 亚秒余数不丢 | 毫秒余量向后进位（`pendingMillis`），1.5 s 的两 tick 记 3 s 而非 2 s | `sub-second ticks are carried instead of lost` |
| 键稳定、可重复 flush | 行键 `bookKey\|day\|sessionStart`，`RoomReadingSessionStore` 用 `REPLACE`；内存中保留**累计行**、只回写 dirty 行（否则 REPLACE 会截断已落库秒数） | `flushing twice keeps a single row…`、`long sessions flush every interval and survive a restart` |
| 换书/退出会话 | `begin(other)` 关闭旧会话；`end()` 关闭并 flush | `switching books closes the previous session` |
| 时钟回拨 | 重锚 `lastTick`，不记负数时长 | `a backwards clock jump credits nothing` |
| 单本累计（对齐桌面 `readingTime` blob 排序） | `totalSecondsFor(bookKey)` | `total seconds per book mirrors the desktop readingTime blob` |

## 4. 持久化

### 4.1 现状（本卡落地形态）

两个模块**各自持有私有 Room 库**，避免本卡触碰 `:core:data` 的 schema 版本（同时避免与 P6 其它 sub 抢 `KoodoDatabase.kt`）：

- `feature/stats` → `koodo-stats.db`：`reading_sessions(key, bookKey, day, startMillis, endMillis, seconds, words, cfi)`
- `feature/ocr` → `koodo-ocr-index.db`：`ocr_pages(key, bookKey, pageIndex, script, text, normalizedText, tokenCount, recognizedAt, durationMillis, engine)`

列名沿用 `core/data` 约定（camelCase 列、`key` 主键、`day` 用桌面 `dateToKey` 的 `YYYY-MM-DD`），因此并入 `koodo.db` 无需改名。

### 4.2 并入 `koodo.db` 的补丁（main thread 决策，本卡**未执行**）

`core/data` 不能依赖 feature 模块（方向相反），所以并入需要把 entity/DAO 上移或让 `:core:data` 反向依赖。推荐**上移**（改动最小、方向正确）：

```diff
--- a/android/core/data/src/main/kotlin/com/koodoreader/core/data/KoodoDatabase.kt
+++ b/android/core/data/src/main/kotlin/com/koodoreader/core/data/KoodoDatabase.kt
@@
 import com.koodoreader.core.data.entity.PluginEntity
 import com.koodoreader.core.data.entity.WordEntity
+import com.koodoreader.core.data.entity.ReadingSessionEntity
+import com.koodoreader.core.data.entity.OcrPageEntity
@@
         PluginEntity::class,
         WordEntity::class,
+        ReadingSessionEntity::class,
+        OcrPageEntity::class,
     ],
-    version = 1,
+    version = 2,
     exportSchema = true,
 )
 abstract class KoodoDatabase : RoomDatabase() {
@@
     abstract fun wordDao(): WordDao
+    abstract fun readingSessionDao(): ReadingSessionDao
+    abstract fun ocrPageDao(): OcrPageDao
```

配套动作：① 两个 entity/DAO 文件整体搬到 `core/data`（包名改为 `com.koodoreader.core.data.entity/dao`，feature 模块改为依赖 `:core:data`）；② `version = 2` 需要 `Migration(1,2)`（两张新建表，DDL 可从 Room 导出的 schema JSON 复制）；③ `scripts/check-room-schema.js` 目前以 `schema.lock`（桌面 DDL）为基准 —— 新表**不在桌面 schema 内**，需在守卫里登记白名单，否则 CI 会红。
在此之前，两个模块的自有库已可独立工作。

## 5. OCR

### 5.1 闭环

```
扫描页(位图) ─▶ ① 模型可用性检查（OnDemandDownloader.state）
                    │ 未安装
                    ▼
              ② ensureInstalled(按需下载/进度回调) ──失败──▶ ModelUnavailable（不索引，读下一页）
                    │ 已安装
                    ▼
              ③ OcrEngine.recognize(MlKitOcrProvider) ──异常──▶ Failed（不抛穿调用方）
                    ▼
              ④ OcrTextNormalizer.normalize（去连字符换行 / CJK 行合并 / 空白折叠）
                    ▼
              ⑤ ocr_pages upsert（键 = bookKey#pageIndex#script，重扫即覆盖）
                    ▼
              ⑥ search(query, bookKey?) → OcrHit(页码, 评分, 上下文片段) → 阅读器跳转
```

- 归一化规则对齐桌面 `cleanWindowsOcrText`（`src/utils/main/ocr-util.js`）：Windows/ML Kit 都会把 CJK 拆行、加空格，索引前必须还原（`OcrEngineTest` 覆盖）。
- 检索：查询与页面文本都归一化；索引 token = 拉丁词 + CJK **一元/二元**（`阅读` → `阅/阅读/读`），因此 1–2 字中文查询也能命中，无需分词器。
- 打分：`命中 token 比例 × 0.75 + 整串命中 0.25`，`minScore=0.34` 过滤"只命中一个常见词"的噪声；排序 `score desc → bookKey → pageIndex`（可复现）。片段：命中点 ±40 字符 + 省略号。
- 存储侧 `OcrPageDao.prefilter(LIKE)` 只做粗筛，排序/片段在 Kotlin 完成（因此排序逻辑可单测）；**全库检索的后续优化是 FTS4/FTS5 `ocr_pages_fts` 虚拟表**（§8）。

### 5.2 ML Kit 选型：按需（Play services）vs 内置

官方文本识别 v2 文档给出的两种安装方式（[ML Kit text recognition v2 — Android](https://developers.google.cn/ml-kit/vision/text-recognition/v2/android?hl=en)）：

| | 按需（本卡采用） | 内置 |
|---|---|---|
| 构件 | `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1`（+ `-chinese/-japanese/-korean/-devanagari:16.0.1`） | `com.google.mlkit:text-recognition:16.0.1`（+ 同族） |
| 实现 | 模型由 Google Play services 动态下载 | 模型 + native 库静态打进 APK |
| 体积 | 每脚本每架构 **≈260 KB** | 每脚本每架构 **≈4 MB** |
| 首次可用 | 需等待下载（未完成时请求返回空结果） | 立即可用 |
| 16 KB 页对齐 | **APK 内无 ML Kit `.so`**，风险转移给 Play services APK | 自带 native 库，需保住对齐（见 §5.3） |
| 代码 API | `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` / `ChineseTextRecognizerOptions.Builder().build()` … | **完全相同** |

工程侧配套（均已在模块内注释/settings 文档化）：

```xml
<application …>
  <meta-data android:name="com.google.mlkit.vision.DEPENDENCIES"
             android:value="ocr,ocr_chinese" />   <!-- OcrScript.manifestValue(...) 生成 -->
</application>
```

- 安装期预下载：上面的 meta-data（避免用户首次扫码时才等下载）。**这是本项目实际生效的机制**（见下面 §5.2.1）。
- 运行期显式下载/进度：原设计走 `ModuleInstallClient`（`areModulesAvailable` / `installModules` + `InstallStatusListener` 的 `bytesDownloaded/totalBytesToDownload`，见 [Module install APIs](https://developers.google.cn/android/guides/module-install-apis?hl=en)），但该路径对本模块**不可用**，已改为探针实现（§5.2.1）。`release()` 恒返回 `false`（ML Kit 模型归 Play services，App 无权删除，契约第 6 条）。
- ML Kit 文本识别 v2 要求 **API 23+**（本模块 `minSdk 24` ✓）。

#### 5.2.1 ModuleInstall 不可用的证据与替代实现（2026-09-24 定案）

`ModuleInstallRequest.addApi(...)` 只接受 `OptionalModuleApi`，而 ML Kit 的文本识别 options 对象并不是它：

```
$ javap -classpath play-services-mlkit-text-recognition-19.0.1/classes.jar \
    com.google.mlkit.vision.text.latin.TextRecognizerOptions
public class ...TextRecognizerOptions
    implements com.google.mlkit.vision.text.TextRecognizerOptionsInterface
$ javap ... com.google.mlkit.vision.text.TextRecognizerOptionsInterface
public interface ...TextRecognizerOptionsInterface {   // 无 OptionalModuleApi
  getModuleId() 有；getOptionalFeatures() 未声明
}
```

19.0.1 是当前最新版本，内置变体 `com.google.mlkit:text-recognition*` 的 classpath 上根本没有这个类，因此**改 import / 升版本 / 写适配器都无法让它类型正确**。结论：本模块保留「按需（Play services）构件」路线（产品已确认），但下载驱动改为：

1. **安装期**：app manifest 的 `com.google.mlkit.vision.DEPENDENCIES` meta-data（`android:value="ocr,ocr_chinese"`，即 latin + chinese）由 Play services 在装包时预取；其余脚本（日/韩/天城文）首次使用时再取。
2. **运行期**：`OcrModelInstaller`（纯 JVM，可单测）用**探针**判定可用性——创建该脚本的 `TextRecognizer` 并对一张 8×8 白图跑一次；失败即重试（1s/3s/8s 退避），重试后成功说明期间完成了取模。`platform/MlKitModelDownloader.kt` 只是把探针接到 ML Kit 上，两个文件**已解除 quarantine 并重新参与编译**。

由此带来的两个 UI 事实（契约里已写明）：

- **没有字节进度**：ML Kit 不为这些模型暴露下载进度，故 `DownloadState.Downloading(progress = null)`、`onProgress` 不会被调用，宿主必须显示不确定进度条；不要伪造 0..1。
- **「已装」与「刚下载」只能靠探测顺序区分**：首次探针即成功 → `AlreadyInstalled`；重试后才成功 → `Downloaded(bytes)`。


### 5.3 16 KB 页对齐与 `.so` 体积评估（本卡重点结论）

**风险背景**：Android 15+ 起 16 KB 页大小设备要求 `LOAD` 段 `p_align` 是 `0x4000` 的整数倍；仓库已有守卫 `scripts/check-elf-16kb.js`（**纯 Node**：自解析 APK 的 ZIP 中央目录取出 `lib/**/*.so`，再自解析 ELF program header 校验每个 `PT_LOAD`，不依赖 `unzip`/`llvm-readelf`；已接入 CI 出包后的守卫步骤）与 `scripts/check-elf-16kb.sh`（POSIX 版，仍走 `unzip`+`readelf`），并且 `docs/android-native-migration.md:175` 已因 16 KB 对齐问题**排除 Pdfium**。P3 选择 pdf.js 后，当时 APK 内 `.so` 数量为 0；P6-TTS 引入 `androidx.datastore` 后出现首个 `.so`（7 KB，实测 `p_align=0x4000` 合规）。

**事实依据**：

1. ML Kit 官方文本识别 v2 的体积数据（上表）：内置变体 ≈4 MB/脚本/架构；5 个脚本 × 2 个常用 ABI（`arm64-v8a`、`armeabi-v7a`）最坏可达 **≈40 MB** 的模型 + native 库，全部进入 APK。
2. 社区实测：ML Kit native 库曾在 AGP 8.5/8.6 + `stripDebugSymbols` 阶段报 `ELF file alignment does not match 16 KB requirement`（[googlesamples/mlkit#987](https://github.com/googlesamples/mlkit/issues/987)）。维护者在推荐基线（AGP 8.5.1 + targetSdk 35）下复测 `barcode-scanning:17.3.0` 单依赖**未发现 16 KB 问题**，真凶是**传递依赖** —— 当时 `androidx.camera:camera-core < 1.4.2` 携带 4 KB 对齐的 `libimage_processing_util_jni.so`；修复方式是把 CameraX 强制到 ≥1.4.2。
3. 本仓库 AGP 为 **8.2.2**（`android/build.gradle`），低于 16 KB 打包的推荐最低线（AGP 8.5.1）；`app/build.gradle` 已有 `-PabiFilters=<abi>` 单 ABI 切分开关（由 `scripts/build-android.js` 驱动）。

**结论与建议（按优先级）**：

1. **保持按需变体**（本卡实现）：APK 内 0 个 ML Kit `.so`，16 KB 风险从"我们的 APK"消失；体积代价 ≈260 KB/脚本/架构（客户端桩）。
2. 若产品要求"离线装机即用"而必须内置：
   - 升级构建链到 **AGP ≥ 8.5.1**（同时 `targetSdk 35`），让 AGP 的 16 KB 校验在构建期就拦下未对齐 `.so`；
   - CI/发版强制跑 `node scripts/check-elf-16kb.js <apk>`（或对 `--dir` 解包目录），失败即阻断；
   - **按 ABI 拆分**：继续用 `-PabiFilters`（已有）或改 App Bundle（每设备只装 1 个 ABI 的 `.so`），把 ≈40 MB 压到 ≈20 MB 量级；
   - 只装**用得到的脚本**：中文/日文/韩文/天城文按用户语言按需（`OcrScript.manifestValue(listOf(...))` 已是这个模型）；
   - **动态特性模块（on-demand dynamic feature）**：把 OCR UI + ML Kit 依赖整体挪进 dynamic feature，base APK 加 `com.google.mlkit:playstore-dynamic-feature-support:16.0.0-beta2`（[Reduce APK size](https://developers.google.cn/ml-kit/tips/reduce-app-size?hl=en)），首装体积再降；
   - 传递依赖排查：确保没有引入 `< 1.4.2` 的 CameraX（我们不用相机，风险仅来自未来依赖）。
3. **桌面 Web OCR 资产不进 APK**：`public/lib/onnxruntime-web/ort.min.js`（352 KB）+ `public/lib/esearch-ocr/esearch-ocr.umd.js`（27 KB）只是 JS 壳，`esearch-ocr` 的 `init({ort, detPath, recPath, dic, docClsPath})` 需要外部 `.onnx`/字典文件，`onnxruntime-web` 的 `.wasm` 也从 CDN/本地路径加载 —— 仓库内**没有** `.wasm`/`.onnx` 实体文件（已核实 `public/lib/onnxruntime-web`、`public/lib/esearch-ocr` 仅两个 JS）。因此 native 版没有任何"WASM 16 KB 打包"负担；打包脚本只需保证不把这类资产塞进 APK（当前 webapp 资产是 webview 轨的资源，不受 native OCR 影响）。
4. 结论：**本卡选择=零 `.so` 增量**；即便未来改内置，也有 §5.3-2 的 5 条可控路径，且守卫脚本可量化验证。

### 5.4 与 P6 词典共用 `OnDemandDownloader`（归属待 main thread 决定）

- 接口名固定 `OnDemandDownloader`，两个模块**各自声明**（本卡声明在 `feature/ocr/OnDemandModelDownloader.kt`），行为契约 6 条：① `state` 非阻塞无 IO；② `ensureInstalled` 幂等（已装 → `AlreadyInstalled` 不触网）；③ 进度单调、落在 `0f..1f`，可跳过；④ 不存在"半装"状态；⑤ 同 pack 并发调用由实现合并；⑥ `release` 可返回 `false`（平台不允许删除）。
- 归属规则：当 `feature:dictionary` 与 `feature:ocr` 同时注册进 `android/settings.gradle` 时，**由 main thread 决定**单一归属（建议：`feature:dictionary` 拥有接口、`feature:ocr` 依赖它；或两者都上移到 `:core:common`）。在归属决定前，两个模块互不依赖，避免"引用对方目录里的文件"。
- 契约测试在两侧都可复制：本卡的 `OnDemandModelDownloaderTest`（7 例）即契约的可执行规格。

## 6. 测试与自验

**运行方式（本机无网络、`~/.gradle` 不可写、`settings.gradle` 禁改，故不用 Gradle）**：
`.p6-stats-ocr-check/verify.ps1`（**worktree 之外的临时目录，不进 patch**）用 Gradle 模块缓存里已有的 `kotlin-compiler-embeddable:1.9.24` 直接编译两个模块的纯 Kotlin 源（排除 `ui/`、`platform/`）+ `src/test/kotlin`，再用 JUnit 4 `JUnitCore` 运行：

```
[verify] compiling 6 main + 6 test file(s)
[verify] running 6 JUnit class(es)
OK (63 tests)
```

| 模块 | 测试类 | 用例数 | 覆盖 |
|---|---|---|---|
| stats | `IsoDayTest` | 6 | epoch-day 换算（含闰年/负值）、Sunday-first 星期、`dateToKey` 往返、时区偏移取日、非法输入 |
| stats | `StatsAggregatorTest` | 15 | §3.1 表 1–10 全部口径 + 字数/进度 + 热力图分列/月锚点 |
| stats | `ReadingSessionRepositoryTest` | 13 | §3.5 计时规则全部（含跨零点、空闲裁剪、亚秒进位、重复 flush、时钟回拨、按书累计） |
| ocr | `OcrEngineTest` | 9 | 语言→脚本映射、manifest 值、构件坐标、归一化（CJK 合并/去连字符/空白折叠）、token 方案、CJK 判定 |
| ocr | `OnDemandModelDownloaderTest` | 7 | 目录与体积、状态迁移、进度单调、幂等、失败无半装、不可重试、release |
| ocr | `OcrSearchRepositoryTest` | 13 | 索引落库、重扫覆盖、空页不入库、语言隔离、模型缺失/引擎异常不抛穿、分词命中、排序与阈值、按书过滤、limit、片段省略号、按书删除 |

**未覆盖（见 §8 风险）**：`ui/`、`platform/` 下的 Android-only 代码（Compose/ML Kit/Room 运行时）在本环境无法编译（无 Android SDK 构建链、AGP 8.2.2 需 JDK17+ 且模块未注册），仅做静态审查。

## 7. 接线指令（main thread 执行；本卡未做）

```diff
--- a/android/settings.gradle
+++ b/android/settings.gradle
+// Native reading statistics + scanned-page OCR (P6).
+include ':feature:stats'
+include ':feature:ocr'
```

```diff
--- a/android/app/build.gradle   (native 轨依赖)
+++ b/android/app/build.gradle
+    implementation project(':feature:stats')
+    implementation project(':feature:ocr')
```

```diff
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
+  <meta-data android:name="com.google.mlkit.vision.DEPENDENCIES"
+             android:value="ocr,ocr_chinese" />
```

阅读器接线（建议）：
- 进入阅读页 `ReadingSessionWiring.repository(context)` → `begin(book.key, cfi)`；翻页/滚动 tick 调 `tick(wordsDelta = StatsAggregator.wordCount(renderedChunk))`；退出 `end()`。
- `/stats` 入口：`StatsViewModel(repository, booksReadProvider = { bookDao.countWithLocation() }, progressProvider = { locationDao.percentages() }, todayProvider = { ReadingSessionWiring.localToday(now) })` + `StatsScreen(state, t = { LocalI18n.current.localization.t(it) }, onClose = …)`。
- 扫描 PDF：`OcrWiring.repository(context)` → 每页渲染位图后 `indexPage(OcrRequest(key, pageIndex, script), MlKitPageImage(bitmap))`，检索页用 `search(query, bookKey)`。

## 8. 风险与后续

| 级别 | 风险 | 说明 / 缓解 |
|---|---|---|
| 中 | `platform/`、`ui/` 未编译验证 | 本环境无 Android 构建链（模块未注册 + AGP/JDK 限制）。缓解：这些文件均为薄适配（Compose 布局 / ML Kit 调用 / Room 建库），纯逻辑全在已测核心；接线时先跑 `gradle :feature:stats:testDebugUnitTest` 与 `:feature:ocr:testDebugUnitTest` |
| 中 | `MlKitOptionalModuleApis.of()` 的 5 行类型依赖 | ML Kit 的 options 对象作为 `OptionalModuleApi` 传入 `ModuleInstallRequest`；若与所选版本不符，会在该 5 行**编译失败**（刻意返回可空类型），届时按官方文档改用对应 API 句柄即可 |
| 中 | 首次 OCR 需下载模型 | 未装完时 ML Kit 返回空结果；缓解：manifest 安装期预下载 + `ensureInstalled` 进度 UI + `ModelUnavailable` 回执（不阻塞翻页） |
| 中 | Room schema 归属未定 | 两个私有库已可用；并入 `koodo.db` 需 `version=2` + 迁移 + 改 `scripts/check-room-schema.js` 白名单（§4.2） |
| 低 | 全库检索性能 | 目前 `LIKE` 粗筛 + Kotlin 精排，单本规模足够；跨书全库检索应上 FTS4/FTS5 `ocr_pages_fts` |
| 低 | 热力图图例/色阶不一致 | 桌面 bug-for-bug 保留；如需统一需两端同步改（§3.2） |
| 低 | 新增 i18n key | 仅 `"Word count"`（en/zh-CN + Android 资产已同步，`--check` 通过）；其余 39 语言按 ADR-004 的按需语言包策略，缺失时回退英文 |

## 9. 变更文件

新增（worktree `task/p6-stats-ocr-muexn6c0`）：
`android/feature/stats/{build.gradle, src/main/kotlin/.../stats/{StatsModels,StatsAggregator,ReadingSessionRepository}.kt, ui/{StatsScreen,StatsViewModel}.kt, platform/StatsDatabase.kt, src/test/kotlin/.../stats/{IsoDayTest,StatsAggregatorTest,ReadingSessionRepositoryTest}.kt}`
`android/feature/ocr/{build.gradle, src/main/kotlin/.../ocr/{OcrEngine,OnDemandModelDownloader,OcrSearchRepository}.kt, platform/{MlKitOcrProvider,MlKitModelDownloader,OcrIndexDatabase}.kt, src/test/kotlin/.../ocr/{OcrEngineTest,OnDemandModelDownloaderTest,OcrSearchRepositoryTest}.kt}`
`docs/p6-stats-ocr-design.md`、`docs/patches/p6-stats-ocr.patch`

修改：`src/assets/locales/{en,zh-CN}.json`、`android/app/src/main/assets/locales/{en,zh-CN,manifest}.json`（新增 `"Word count"` + 同步）。

**未修改硬约束文件**：`android/app/build.gradle`、`android/settings.gradle`、`android/build.gradle`、`package.json`、`main.js`（可用 `git status` 验证，见 patch 与执行报告）。

## 10. 参考

- ML Kit 文本识别 v2（Android）：<https://developers.google.cn/ml-kit/vision/text-recognition/v2/android?hl=en>（按需/内置构件、体积、manifest meta-data、ModuleInstallClient）
- Google Play services 按需模块（`ModuleInstallClient`）：<https://developers.google.cn/android/guides/module-install-apis?hl=en>
- ML Kit APK 瘦身（dynamic feature / ABI / playstore-dynamic-feature-support）：<https://developers.google.cn/ml-kit/tips/reduce-app-size?hl=en>
- ML Kit native 库 16 KB 对齐实测与结论：<https://github.com/googlesamples/mlkit/issues/987>
- 仓库内：`scripts/check-elf-16kb.js`、`docs/android-native-migration.md`（§6 / 附录 A.4 / 第 175 行 Pdfium 排除依据）、`docs/android-pdf-poc.md`
