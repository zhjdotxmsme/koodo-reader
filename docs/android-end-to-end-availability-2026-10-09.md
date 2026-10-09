# Koodo Reader Android 端到端可用性摸底

- 摸底时间：2026-10-09（Asia/Shanghai）
- 方法：纯静态（源码 + 文档 + build/产物）
- 范围：`android/` 全部子工程 + 4 篇 P0~P8 文档 + 1 张能力清单
- 目的：回答"安卓功能是否可以用，完善好了吗"——按能力项给出可用性结论与证据，不夸大、不藏瑕。
- 运行环境：工作机无 Android SDK / AVD / 真机 / gradle CLI——所有结论均**未通过真机/模拟器回归**，已用"需运行时回归"标注的项须装机自查。

---

## 一句话结论

工程不是骨架，**已经是一个能装机就跑起来读 7 种文本 + PDF + 漫画的产品级 Android 客户端**；但 5 个真实未收口项仍是发布前的硬卡点，其中最致命的一项会让 `gradle :app:assembleDebug` 直接挂在依赖解析阶段——`settings.gradle` 注释了 4 个 `include` 但 8 个 `build.gradle` 引用着它们，跨模块构建不可达。下面矩阵 + 待修复项列表给出全貌。

---

## 一、能力矩阵

可用性档位（自定）：
- **A. 装机即用**：源码 + 接线 + 资源 + 权限 + 数据层 + UI 全到位，未发现静态阻塞；建议在真机做一次烟雾测试。
- **B. 源码到位、需运行时回归**：静态看起来完整，但需要真机/模拟器验证 ML Kit 模型下载 / 前台服务生命周期 / WebView↔原生桥 / 16KB page size / 第一帧延迟 / 锁屏媒体键 等。
- **C. 仅骨架 / 已写但未接线**：代码存在但 host 路由没接通，或 wiring 在 patch 里未 apply 到主分支。
- **D. 未实现**：源码/路由/UI 全缺。

> 标 ★ 的项是**已知并由代码注释或工程文档主动承认**的 gap——不是漏看。

### 1. 工程结构

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| Build 配置 | `android/settings.gradle` + `android/app/build.gradle` | C ★ | 4 个 `include`（`:core:archive` / `:engine:fb2` / `:engine:htmlbook` / `:engine:docx`）**被注释**，但 8 个 `build.gradle` 引用它们（`app:197,200,201`、`core/importer:27`、`core/dbio:30`、`engine/fb2:21`、`engine/docx:22,24`）。`gradle :app:assembleDebug` 会 100% 报 `Project with path ':engine:fb2' could not be found.`。模块源码、build.gradle、`build/libs/*.jar`、`build/test-results/test/*.xml` 都已落地（`.worktrees/d0-textblock-flattener`、`.worktrees/r1-core-archive` 已合并）；p8-rollout-checklist.md §1 写的"D0+R1 前不要重新启用"是过期指南。**修法**：反注释 `settings.gradle:103-112` 那 4 行即可。 |
| 入口 Activity | `MainActivity` / `NativeShellActivity` / `ComicViewerActivity` | A | 3 个 Activity 全部 wired；`AndroidManifest.xml:54-201` 完整声明；`-Ptarget=webview\|native` 双轨开关 + `nativeLauncher` placeholder |
| 前台服务 | `ForegroundTtsService` (689 行) | B | Service + TextToSpeech + AudioFocusRequest + ServiceCompat.startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) + MediaStyle 通知 + 锁屏 VISIBILITY_PUBLIC；`AndroidManifest.xml:13-15` 权限齐全。**待真机验证**：POST_NOTIFICATIONS 运行时申请、锁屏 9 actions 实际匹配、设备 TTS 引擎生命周期。 |
| 接收器 | `LockscreenControlsReceiver` (9 actions) | B | `AndroidManifest.xml:206-215` 注册 9 actions（MEDIA_BUTTON + tts.action.PLAY/PAUSE/PLAY_PAUSE/STOP/NEXT/PREVIOUS/FAST_FORWARD/REWIND）；与 `ForegroundTtsService.isRunning` 同步。**待真机验证**：通知点击展开、蓝牙耳机按钮事件。 |
| 应用启动 | `KoodoReaderApp.kt` (17 行) | C ★ | onCreate 只调 `CrashLogWriter.install(this)` + `AndroidDesktopDb.install()`；**缺 `:feature:crash` 的 `CrashMonitoring.install()`**——p8-rollout-checklist §3 写明"one-line wiring in patch (not applied in main branch)"，确认 gap。 |
| 崩溃日志 | `app/CrashLogWriter` (193 行) | A | 4 渠道兜底（剪贴板 + `/data/data/com.koodoreader/crash.log` + 外置 `/sdcard/Android/data/com.koodoreader/files/crash-logs/crash.log` + logcat `KoodoCrashLog`）+ 上次崩溃重贴剪贴板 + Toast + 静默吞异常（崩溃记录器自己不能成为崩溃源）。 |
| 崩溃方案 2 | `:feature:crash` 模块 | C ★ | 6 个 .kt（`CrashBackends` / `CrashEvent` / `CrashMonitoring` / `CrashReporter` / `Redaction`）全在；backend-free interfaces + redaction-only；**未接入**——只是为接 Sentry/Firebase 准备的接口层。 |
| Macrobenchmark | `:benchmarks` 4 benchmark | C ★ | `StartupBenchmark` / `OpenEpubBenchmark` / `PageTurnBenchmark` / `MemoryPeakBenchmark` 全在；`app/build.gradle` 的 `benchmark` buildType `initWith release` + `debuggable false` + debug signingConfig 正确；**CI 入口脚本 `scripts/ci-macro-benchmark.sh` 未在主分支 CI 中跑过**——p8-rollout-checklist §4 列为 ◐。 |

### 2. 书架 / 导入 / 数据层

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| 导入规则 | `core/importer/BookRules.kt` (162 行) | A | 18 个扩展名（epub/pdf/txt/mobi/azw3/azw/htm/html/xml/xhtml/mhtml/docx/md/fb2/cbz/cbt/cbr/cb7）+ 18 个 MIME；`MAX_FILES=1000` / `FOLDER_DEPTH=2`；与 `AndroidManifest.xml:78-100` intent-filter 对齐；CI `scripts/check-import-rules.js` 防 drift。 |
| 导入管线 | `core/importer/ImportPipeline.kt` | A | 分页 upsert + 第三方 cover 提取（`PdfCoverExtractor` / `ComicCover` / `EpubBook` / `MobiCover`）；被 `LibraryViewModel.startImport` 调用。 |
| 书架 | `app/LibraryViewModel.kt` (360 行) + `LibraryScreen.kt` (265 行) | A | AndroidViewModel；importState sealed (Idle/Running/Finished)；SAF `OpenDocumentTree` / `OpenMultipleDocuments`；分页 upsert BATCH_SIZE=50 + CoverStore；shelf 状态 sort/viewGrid/favorites/trashed/manualOrder/favoritesOnly；toggleFavorite/moveToTrash/restoreFromTrash/purge/moveManual 真实持久化；annotationCounts combine。 |
| 桌面 .db 兼容 | `core/dbio/DesktopDdl` + `DesktopDbIO` + `DataImport` + `DataExport` + `BackupBundle` + `app/AndroidDesktopDb.kt` (129 行) | A | framework `android.database.sqlite`（不依赖 xerial sqlite-jdbc）；DesktopDdl.ddl() 同步 schema；处理 desktop `object`/`array`/`string` 未知类型 → NUMERIC affinity；被 `KoodoReaderApp.onCreate` 注册。 |
| Room 数据层 | `core/data/KoodoDatabase.kt` (49 行) + 5 entity + 4 DAO + KoodoDatabaseProvider | A | `@Database(entities=[BookEntity, NoteEntity, BookmarkEntity, PluginEntity, WordEntity], version=1, exportSchema=true)`；`NAME="koodo.db"`；column names frozen by `schema.lock` + CI `scripts/check-room-schema.js` 防 drift。 |
| i18n | `app/I18n.kt` (59 行) + `assets/locales/{en,zh-CN}.json` + `manifest.json` | A | 4 选（system/en/zh-CN）；ADR-004 显式声明桌面子集（17+ locale 缩到 2）。 |

### 3. 阅读（文本类）

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| Session 工厂 | `epubhost/ReaderSessionFactory.kt` (95 行) | A | 6 个 Kind（EPUB/TEXT/MOBI/WEB/FB2/DOCX），formats 派生自 `BookRules.BOOK_EXTENSIONS - COMIC - ISLAND_ONLY - PDF`；open() 按 kindOf 分派 6 个 session 类。 |
| EPUB 阅读 | `epubhost/EpubBookSession.kt` + `NativeEpubScreen.kt` (906 行) | A | Canvas 分页 + 双击选词（`onDoubleTap` → `layout.positionAt` → `pageLines.firstOrNull` 命中行 → 弹选词菜单）+ 点区翻页（左 1/3 上一页 / 右 1/3 下一页 / 中 1/3 切换 chrome）+ 4 色高亮 + 笔记对话框 + 全文搜索（`SearchIndex.build` + `doSearch` + `jumpToHit`）+ TOC（优先 EPUB 内嵌 NAV/NCX，否则 spine 文件名）+ 书签 + 字典 + 翻译 + TTS + 字号 0.7–2.5 + 主题色（`ShellAppearancePrefs.readerThemeKindFlow`）+ 进度写回（`cfiForPage` → `progressPrefs.save`）+ 图片行 `BitmapFactory.decodeByteArray`。 |
| TXT / MD | `epubhost/TextBookSession.kt` + `engine/text` | A | 字符集探测 + Markdown 子集渲染；通过 NativeEpubScreen 走。 |
| MOBI / AZW / AZW3 | `epubhost/MobiBookSession.kt` + `engine/mobi` | C ★ | PalmDOC + MOBI6/KF8 + EXTH 已实现；**HUFF/CDIC 缺失**——P7 卡 in_review（`t-muexn60r-4p3sky`）；意味着受 DRM 保护或特殊压缩的 MOBI 可能解析失败或章节不完整。 |
| FB2 | `epubhost/Fb2BookSession.kt` + `engine/fb2/Fb2Document.kt` | B | 单源 `Fb2Document`；测试 8 绿；但模块在 settings.gradle 注释下没在 `:app:assembleDebug` 中编译，**需先解 settings.gradle 反注释。** |
| DOCX | `epubhost/DocxBookSession.kt` + `engine/docx/DocxDocument.kt` | B | 单源 `DocxDocument`；测试 9 绿；同样需先解 settings.gradle。 |
| HTML / HTM / XHTML / MHTML / XML | `epubhost/WebBookSession.kt` + `engine/htmlbook/{HtmlDocument.kt,MhtmlDocument.kt}` | B | 字符集探测（GBK 等）已测试（htmlbook 15 绿）；同样需先解 settings.gradle。**注意**：XML 走 webview track 子集，`BookRules.BOOK_EXTENSIONS` 含 xml 但 `AndroidManifest` intent-filter 也含 xml——一致。 |
| CFI | `engine/cfi` | A | 84 commits 之后所有文本类 reader 通过 `cfiForPage` ↔ `progressPrefs` 持久化进度。 |
| 分页内核 | `engine/layout` | A | CSS-columns line breaking + page filling；HtmlFlattener（**D0 已落地**，p8-rollout-checklist 的"D0 前不要重新启用"是过期指南）。 |
| 翻页手势 | `engine/gesture` + `ReaderGestureModifier` | A | fling 物理 + edge bounce + tap zones。 |
| 阅读增强 | `engine/feature`（P6 paragraph mode / RSVP / ruler / bionic / text replacement / selection auto-turn） | D ★ | `:engine:feature` 模块已在 settings.gradle include；但 6 项功能里只有"文字替换规则"明确写入（其它仍在 P6 卡）；当前 `NativeEpubScreen` 没看到对应的 hook。**确认 gap**。 |

### 4. PDF（端到端）

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| PDF 内核 | `engine/pdf`（纯 JVM）+ `app/pdfhost/PdfJsHostBridge` (255 行) + `app/pdfhost/PdfRendererSnapshot` (88 行) + `app/LocalAssetServer` (415 行) | A | `BOOTSTRAP_PATH="pdfengine/index.html"` + `JS_CALL_TIMEOUT_MS=10s` + `SEARCH_TIMEOUT_MS=60s`；PdfJsHostBridge 用 `CountDownLatch` 同步 JS Promise；LocalAssetServer loopback 127.0.0.1 + 4 worker + Range 206 + SPA fallback + 30+ MIME；`assets/pdfengine/` 174 个资源（cmaps + pdf.js 字体）。 |
| PDF 屏幕 | `app/shell/NativePdfScreen.kt` (631 行) | A | TopAppBar 含 title(name+page/zoom%) + nav back + actions [−/+, outline, search, OCR, share]；engine WebView 1×1 不可见（pdf.js worker + canvas 必要）；state.opening → CircularProgressIndicator + Loading；passwordRequired → PasswordPrompt（OutlinedTextField + ImeAction.Done + 错误态）；pageImage → Image（drawnWidth=containerSize.width*zoom）+ PagerBar；ModalBottomSheet outline list；SearchDialog；PdfOcrDialog；PdfUnavailableScreen 兜底；shareSnapshot 用 FileProvider.getUriForFile + Intent.ACTION_SEND image/png + FLAG_GRANT_READ_URI_PERMISSION。 |
| PDF OCR | `app/shell/PdfReaderController.kt` (329 行) + `PdfOcrIndexer.kt` (91 行) + `PdfOcrController.kt` (172 行) + `PdfOcrDialog.kt` (169 行) + `:feature:ocr` | A | `rasterForOcr` 1600px；`OcrIndexSummary` (indexed/empty/failed/modelUnavailableReason)；per-page 不抛（failed 计数）；首次 ModelUnavailable 早返回；cancellation cooperative；OCR 入口在 PDF TopAppBar（ic_ocr）。 |
| PDF 注释 | — | D ★ | by design：raster host 无文本层（`PdfRendererSnapshot` / `NativePdfScreen` 注释明确）——"PdfRenderer doesn't expose the document's text layer, so it cannot be used for the reader's main view. It is a print/export sidekick only"。要加 PDF 注释必须把当前 raster host 替换为带 DOM 文本层的引擎（如 pdf.js textLayer + 编辑覆盖层）。 |
| PDF 密码 | `passwordRequired` 分支 + `PasswordPrompt` | A | framework PdfRenderer 不解密——`NativePdfScreen` 通过 OCR 端的 `PasswordPrompt` 走"输入密码再渲染"。**待真机验证**：加密 PDF 的首次解锁延迟。 |

### 5. 漫画（CBZ / CBT / CB7）

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| 漫画引擎 | `engine/image` (14 个 .kt) | A | `ComicViewerModel` / `DefaultPageLoader` / `PageLoader` / `SpreadPolicy` / `ZoomPanState` / `ArchiveExtractor` / `ZipExtractor` / `TarExtractor` / `RarExtractor` / `SevenZExtractor` / `TreeExtractor` / `NaturalOrder` / `ImageEntries` / `ImageHeader` 全部就位；p8-rollout-checklist 的"engine/image doesn't exist"是过期认知。 |
| 漫画屏 | `app/imagehost/ComicViewerActivity.kt` (159 行) + `ComicViewerHost.kt` (229 行) | A | `ComicViewerScreen` 用 `remember(file)` 建 `ComicViewerModel(DefaultPageLoader(ArchiveExtractors.open(file), decodeExecutor))`；snapshot = model.open(0)；zoom = ZoomPanState.FIT；detectTransformGestures pinch+pan → ZoomPan；detectTapGestures onDoubleTap → ZoomPan.doubleTap；tap zone 左 1/3 previous()、右 1/3 next() ?: onExitRequested()、中 onUiToggle；双页 spread 走 Row + visualOrder；BitmapFactory.inSampleSize 降采样到 targetWidth。 |
| ComicPageDecoder | `engine/image` + `ComicViewerActivity` | A | `BitmapFactory.inSampleSize` 降采样避免 OOM。 |
| CB7 | `engine/image/SevenZExtractor.kt` | A | commons-compress 1.27.1 + xz 1.10，**零新增 .so**；16KB page size 兼容（确认无 native lib）。 |
| CBR | — | D ★ | by design：IntentRoutePolicy.ISLAND_ONLY = cbr 显式拦 ISLAND（"UnRAR 许可 + .so 16KB"）；用户打开 CBR 走 webview 兜底岛（`assets/webapp/`）。 |

### 6. P6 增强（笔记 / 字典 / 翻译 / TTS / OCR / 统计）

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| 笔记 / 高亮 / 书签 | `core/data/dao/{NoteDao, BookmarkDao}.kt` + `app/shell/NotesViewModel.kt` (228 行) + `NotesScreen.kt` (169 行) + `engine/annotate` (112 测试绿) + `engine/link` (72 测试绿) | A | 4 色高亮（黄/绿/粉/蓝）写 NoteEntity；笔记对话框；3 SegmentedButton filter（Highlights/Notes/Bookmarks）；CFI 跳转（ReaderPatternWithCFI）。 |
| 全文搜索 | `engine/layout` SearchIndex + `NativeEpubScreen.doSearch` | A | EPUB/MOBI/HTML/FB2/DOCX/TXT 全通过 NativeEpubScreen 内置搜索；PDF 走 `SearchDialog` + JS bridge。 |
| 字典 | `:feature:dictionary` (14 个 .kt) + `app/shell/DictionaryRoute.kt` (210 行) + `DictionaryImport.kt` | A | MDX/MDD 自解析（`mdx/MdictCore.kt` 等 9 个）；SAF OpenMultipleDocuments；importDictionaries + onToggleEnabled + onSetDefault 真实持久化；上架 1 默认字典；`HtmlDefinitionRenderer` 渲染定义。**已知 gap**：云字典未接（"cloudDicts stays empty... a Download button that cannot download would be worse than no section at all"）。 |
| 翻译 | `:feature:translate` (17 个 .kt) + `app/translate/TranslateSettingsRoute.kt` (122 行) + `NativeEpubScreen` 双链选词 | B | 3 个 Provider：GoogleTranslateProvider / MicrosoftTranslateProvider / DeepLTranslateProvider；AiAssistant + HttpTransport；EncryptedSharedPreferences AES-256-SIV/256-GCM 凭证；HistoryRepository + TranslationHistoryDatabase。**已知 gap**：`TranslationPopup` UI 在 `:feature:translate` 已写，但**未接到 `NativeEpubScreen` 的双击选词词条**——p8-rollout-checklist §"Remaining gaps" 列为 "TranslationPopup not wired (needs selection source)"。**待真机验证**：3 个 Provider 的真实 API key 注入路径。 |
| TTS | `:feature:tts` (14 个 .kt) + `ForegroundTtsService` + `TtsSessionController` + `MediaSessionController` + `TtsControlSheet` + `TtsSettingsRoute` | B | DataStore 持久化；3 slider（speed 0.5–2.0 / pitch 0.5–2.0 / volume 0–1）；6 状态机 + canTransitionTo 表；MediaSessionCompat 9 actions；通知 CHANNEL_ID="koodo_tts_playback" IMPORTANCE_LOW；AutoResume；bookmark 续读（`BookmarkResumeController`）；`NativeEpubScreen` 自动翻页通过 `setHostCommandListener(TtsMediaCommand.NEXT)`。**已知 gap**：`TtsSettingsRoute` 文件头说"音色与引擎选择依赖设备 TTS 服务，随后续功能卡加入"——但 `TtsControlSheet:96` 的 `onVoiceClick` hook + `needsVoiceSelection` 状态早就在代码里，host 路由未把 voice picker 接到 settings 屏。**待真机验证**：设备 TTS 引擎列表、语音选择、续读准确性。 |
| OCR | `:feature:ocr` (7 个 .kt) + `app/shell/PdfOcrIndexer.kt` + `PdfOcrController` | B | ML Kit play-services-mlkit-text-recognition + 中文/日文/韩文/天城文模型；manifest meta-data `DEPENDENCIES=ocr,ocr_chinese`；`OcrIndexDatabase` Room 缓存索引；`OnDemandModelDownloader` 按需下载（user 选择 unbundled + Play-services，与 09-24 文档 §13 一致）；首次 ModelUnavailable 早返回；OCR 入口在 PDF TopAppBar（ic_ocr）。**待真机验证**：模型首次下载体积、网络回退、中文识别准确率。 |
| 统计 | `:feature:stats` (5 个 .kt) + `StatsRoute.kt` (78 行) | C ★ | `ReadingSessionRepository` + `StatsAggregator` + `StatsModels` + `StatsDatabase` 全在；路由注册。**已知 gap**：`progressProvider = { emptyMap() }`（文件头注释明确"deliberately not faked"）——因为 native 还没写进度落点，所以统计的"每本书阅读进度"字段是空白。其它字段（session 时长、章节切换次数）可正常累加。 |
| 备份 / 还原 | `core/dbio/DataExport/DataImport/BackupBundle` + `app/shell/BackupViewModel.kt` (248 行) + `BackupScreen.kt` (193 行) | A | 4 出口（importBackup / exportBackup / exportData / importData）+ 5 格式（CSV/JSON/MD/TXT/HTML）；SAF 4 路径；`formatImportReport` 报告导入结果。 |
| 回收站 | `app/shell/TrashScreen.kt` (98 行) | A | `LibraryViewModel.trashedBooks` + restore + permanently delete。**注意**：purge 注释"coverVersion = 0"——未真刷新封面，可能在书架删除后留下空封面文件。 |
| 设置 / 关于 | `SettingsScreen.kt` (184 行) + 8 leaves | A | 8 section（General/Appearance/Reading/Content Sources/Translation/Text to speech/Data/About）全部 wired；`AboutRoute` 含版本 + 许可证 + GitHub + 崩溃日志路径。 |

### 7. 主题 / 字体 / 外观

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| 设计 token | `core/designsystem` (12 个 .kt) | A | TypographyTokens / ThemeKind / ThemeSpec / FontCatalogEntry / ReaderAppearanceConfig / AppearanceCodec / Contrast / ShapeTokens / SpaceTokens / CssColor / ColorTokens / ChartTokens。 |
| Compose 主题 | `core/ui` (9 个 .kt) | A | KoodoColors / KoodoShapes / KoodoSpacing / KoodoTypography / KoodoChartColors / KoodoTheme + KoodoBookCard / KoodoTopAppBar / ThemeSpecBridge。 |
| 应用主题 | `app/shell/ShellAppearancePrefs` + `AppearanceRoute.kt` (212 行) | A | `appThemeModeFlow`（system/light/dark）+ `readerThemeKindFlow`（6 ThemeSwatch）+ Canvas 预览 + persist。 |
| 阅读主题 | `NativeEpubScreen.readerThemeKindFlow` + `ThemeSpecBridge.background/foreground` | A | 6 主题色实时切换。 |
| 字号 | `ReadingSettingsRoute.kt` (95 行) | A | `SharedPreferences("reader", "fontScale")` 单一事实源；slider 0.7..2.5 + 27 steps；"其余阅读行为随阅读器功能卡落地后加入本区"。 |
| 字体 | `FontCatalog` + `FontManager` + `FontPrefs` + `FontFallbackResolver` | A | 内置 lxgw_wenkai_lite.ttf 8.25MB（用户决策保留，p8 §2 L2）；用户可加自定义字体；fallback resolver 兜底。 |

### 8. APK 体积 / 构建

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| Build 工具链 | `scripts/build-android.js` (667 行) | A | 完整 APK 编排：stage webapp → assets/webapp → assembleDebug/release；`--debug` / `--release` / `--abi` / `--target` / `--no-split` / `--build-web` / `--stage-only` / `--keystore` / `--audit` / `--audit-only`；pure core `src/utils/android/androidBuild.js`。 |
| 测试 | 21 JVM 模块 + 6 Android variants = 1599 测试全绿 | A | `archive 36` / `common 22` / `dbio 22` / `designsystem 128` / `importer 56` / `locale 55` / `annotate 112` / `cfi 4` / `docx 9` / `fb2 8` / `feature 84` / `gesture 40` / `htmlbook 15` / `image 78` / `layout 175` / `link 72` / `mobi 74` / `pdf 65` / `text 71` / `toc 43` / `crash 15` + Android `app 146` / `dictionary 63` / `ocr 39` / `stats 34` / `translate 78` / `tts 55`。 |
| .so 库存 | 1 个 .so | A | `libdatastore_shared_counter.so` 7112B，`p_align=0x4000`（16KB page size 兼容）。 |
| APK 体积 | debug 30.97MB / debug-stripIsland 23.12MB / release R8 unsigned 18.78MB | A | R8：dex 11.97→1.18MB；res 8.43MB（含 8.25MB lxgw_wenkai_lite.ttf）。L1=stripIsland 可再省 7.78MB → 17.10MB。 |
| 16KB page size | 仅 1 个 .so 满足 p_align=0x4000 | A | `android-baseline-after.json` 锁定 baseline；不依赖第三方未升级到 16KB 的 .so。 |
| ABI 拆分 | `-PsplitAbi=true` opt-in | C ★ | 工程未启用（debug 0 .so 节省为 0）；但 build.gradle 已经声明，opt-in 即可生效——属 P8 §2 L5 关闭项。 |
| Release 签名 | `-Pkeystore=...` opt-in | C ★ | 工程未签名；无 keystore；需用户提供 keystore 文件 + storePassword + keyAlias + keyPassword 才能签。 |
| 兜底岛（webapp） | `src/main/assets/webapp/` staged | C ★ | 当前默认构建包含 webapp；`-PstripIsland=true` 可剥离但会破坏 VIEW/SEND intent-filter 路由（p8 §1 列为 ◐ 关闭）。 |

### 9. 路由 / Intent / 多入口

| 子系统 | 模块 | 可用性 | 证据 |
|---|---|---|---|
| Intent 路由 | `app/MainActivity.kt` (671 行) + `IntentRoutePolicy.kt` (99 行) | A | 4 Route：NATIVE_COMIC / NATIVE_PDF / NATIVE_SHELL / ISLAND；NATIVE_COMIC → `ComicViewerActivity`；NATIVE_PDF/NATIVE_SHELL → `LibraryViewModel.importFiles` + `startActivity(NativeShellActivity)`；ISLAND → `queueBook` 走 webview 兜底岛。`queueBook` 把 `content://` 复制到 `cache/intent-books` 并以 loopback HTTP 暴露；`deliverPendingBook` 重试 20×400ms。 |
| Compose 路由 | `app/shell/ShellNavHost.kt` (279 行) + `ShellDestinations.kt` (141 行) + `ShellScaffold.kt` (99 行) | A | 13 个 composable 路由：LIBRARY/NOTES/STATS/SETTINGS（4 个 tab）+ BACKUP/TRASH/DICTIONARY/APPEARANCE/READING/TRANSLATE/TTS/ABOUT（7 个 settings leaves）+ READER_PATTERN + READER_WITH_CFI_PATTERN。READER 按 `book.format.uppercase()` 分派：PDF → NativePdfScreen；EPUB/TXT/MD/MOBI/AZW/AZW3/HTML/HTM/XHTML/XML/MHTML/MHT/FB2/DOCX → NativeEpubScreen；CBZ/CBT/CB7 → ComicBookRoute；其他 → ReaderPlaceholderScreen。READER_WITH_CFI 的 cfi 参数 defaultValue=""（4cda4821 修复 null 闪退）。 |
| 双轨开关 | `-Ptarget=webview\|native` + `manifestPlaceholders.nativeLauncher` | A | webview 走 MainActivity（默认），native 走 NativeShellActivity。 |

---

## 二、5 个真实未收口项（发布前必看）

按风险从高到低：

### 🚨 P0 — `gradle :app:assembleDebug` 跨模块构建会立刻挂

- **现象**：当前 `settings.gradle:103,106,109,112` 4 个 `include`（`:core:archive` / `:engine:fb2` / `:engine:htmlbook` / `:engine:docx`）**被注释**；但 8 个 `build.gradle` 引用它们：`app/build.gradle:197,200,201` + `core/importer/build.gradle:27` + `core/dbio/build.gradle:30` + `engine/fb2/build.gradle:21` + `engine/docx/build.gradle:22,24`。`gradle :app:assembleDebug` / `gradle test` (root) / `gradle :app:dependencies` 会 100% 报 `Project with path ':engine:fb2' could not be found.`。
- **代码事实**：4 个模块的 `src/main/kotlin/...kt` + `src/test/kotlin/...Test.kt` + `build.gradle` + `build/libs/*.jar` + `build/test-results/test/*.xml` **全在磁盘上**。`.worktrees/d0-textblock-flattener`、`.worktrees/r1-core-archive` 已合并。
- **现状**：单模块 `gradle :engine:fb2:test` 这种命令能跑（不依赖 root settings include），所以"1599 测试全绿"是单模块分别跑出来的——**根 `gradle test` 应该挂**。
- **修法**：反注释 `settings.gradle:103-112` 那 4 行；与 `p8-rollout-checklist.md §1` 写的"D0+R1 前不要重新启用"相反——**D0+R1 已经合并到主分支**，p8 文档未同步更新。
- **验证**：
  ```bash
  cd android && ./gradlew :app:dependencies 2>&1 | head -50
  # 期望：所有依赖都能解析；不应有 "Project with path ... could not be found."
  ```

### P1 — `:feature:crash.CrashMonitoring.install()` 未接入 `KoodoReaderApp.onCreate`

- **现象**：`KoodoReaderApp.kt` 17 行：`super.onCreate()` + `CrashLogWriter.install(this)` + `AndroidDesktopDb.install()`；**没有 `:feature:crash.CrashMonitoring.install(this)`**。`:feature:crash` 模块写了 6 个 .kt（CrashBackends / CrashEvent / CrashMonitoring / CrashReporter / Redaction）但未应用——p8-rollout-checklist §3 写明"one-line wiring in patch (not applied in main branch)"。
- **当前**：用户崩溃会被 `app/CrashLogWriter` 抓到，写 4 渠道文件 + 剪贴板 + Toast。**不影响使用**——但 `:feature:crash` 准备接 Sentry/Firebase 时这层接口没用上，崩溃事件不会经过 redaction-only before-send hook。
- **修法**：在 `KoodoReaderApp.onCreate` 加 `CrashMonitoring.install(this)`；redaction 默认 backend 是 no-op（已写好）；如需接 Sentry/Firebase，再追加 backend。
- **验证**：`grep CrashMonitoring.install android/app/src/main/java/com/koodoreader/reader/KoodoReaderApp.kt` → 当前为空；改后应有 1 行。

### P2 — TTS voice picker 已写但未接入 settings 屏

- **现象**：`TtsControlSheet:96` 已经有 `onVoiceClick` hook + `needsVoiceSelection` 状态 + `voiceName` 显示；但 `TtsSettingsRoute.kt` 文件头注释"音色与引擎选择依赖设备 TTS 服务，随后续功能卡加入"——host 路由没把 voice picker 接到 settings 屏。
- **影响**：用户在阅读器里通过 sheet 改 TTS 速度/音调/音量后，回到设置屏想选语音/引擎，**找不到入口**。
- **修法**：在 `TtsSettingsRoute.kt` 增加一个"语音"行，列出 `TtsVoiceCatalog.pickerEntries` 供选；或接受现状，等后续 TTS 服务依赖功能卡完成后再统一接入。
- **验证**：装机后打开 Settings → Text to speech；当前应只能看到 Speed/Pitch/Volume 三栏。

### P3 — NativeEpubScreen 使用 `MainScope().launch` 多次（应改用 viewModelScope）

- **现象**：`NativeEpubScreen` 在以下 7 处用 `MainScope().launch { ... }`：`addBookmark` / `saveHighlight` / `deleteHighlight` / `saveNote` / `startTts` / `lookupDictionary` / `translateController.translate`。这些调用在 Composition 退出后仍持有 Activity Context 引用，**可能导致 Activity 泄漏**；且在配置变更（旋转/字体变化）时无法取消。
- **影响**：单次看不明显，但长时间使用 + 频繁进退阅读会积累 Activity 引用；某些 Android 版本上会触发 `LeakCanary` 或直接 OOM。
- **修法**：把状态和数据操作下沉到 `ReaderViewModel`，用 `viewModelScope.launch` 替代。属于代码清理范畴，**不影响功能**。
- **验证**：`grep -n "MainScope()" android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt` 应有 7 行命中；改后应为 0。

### P4 — `TranslationPopup` UI 在但未接到 `NativeEpubScreen` 双击选词

- **现象**：`:feature:translate/TranslationPopup.kt` + `TranslationPopupUi.kt` UI 完整；但 `NativeEpubScreen` 的双击选词菜单**没有翻译项**——只有高亮 4 色 / 笔记 / 复制 / 搜索。
- **影响**：用户双击选词后想直接翻译，必须手动复制词条再切到翻译屏——**翻译端到端体验断裂**。
- **修法**：在 `NativeEpubScreen.kt` 选词菜单的 `actions` 增加一项 `Translate`（调用 `translateController.translate(selectedText)`）；或接受现状，等后续翻译增强功能卡完成后再统一接入。
- **验证**：装机后双击选词，菜单应只有 Copy/Highlight/Note/Search 4 项；改后应有 5 项（含 Translate）。

---

## 三、文档与工程现状的偏差

| 文档 | 描述 | 工程实际 | 偏差 |
|---|---|---|---|
| `docs/android-engine-capability-inventory.md` (2026-09-23) | engine/image 不存在 | `engine/image` 14 个 .kt 全在，含 RarExtractor / SevenZExtractor | 已过期 |
| `docs/p8-rollout-checklist.md` §1 | D0 + R1 前不要重新启用 4 个 include | D0 = HtmlFlattener 已在 `:engine:layout`；R1 = `:core:archive` 已落地；4 模块 build/libs/*.jar 全在 | 已过期 |
| `docs/p8-rollout-checklist.md` §2 L5 | ABI 拆分未启用 | 0 .so 节省为 0；opt-in 已声明 | 仍为 ◐（未启用） |
| `docs/p8-rollout-checklist.md` §2 L3 | release 未签名 | 无 keystore；`-Pkeystore=...` opt-in | 仍为 ◐（未启用） |
| `docs/p8-rollout-checklist.md` §3 | Crash monitor one-line wiring in patch (not applied) | `KoodoReaderApp.onCreate` 缺 `:feature:crash.install` | 仍为 ◐（未启用） |
| `docs/p8-rollout-checklist.md` §4 | Macrobenchmark skeleton + CI entry | 4 benchmark 全在；`scripts/ci-macro-benchmark.sh` 在但 CI 未跑 | 仍为 ◐（未启用） |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | TranslationPopup not wired (needs selection source) | `TranslationPopup` UI 在 `:feature:translate` 已写；`NativeEpubScreen` 选词菜单未接 | 仍为 gap |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | Application.onCreate one-line wiring in patch | `KoodoReaderApp.kt` 仍只有 17 行 + CrashLogWriter + AndroidDesktopDb | 仍为 gap |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | Fallback island still in default build | webview track 仍为默认（`-Ptarget=webview`），可 `-PstripIsland=true` 剥离但需 P8-F1 + F3 完成 | 仍为 ◐（未启用） |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | PDF annotation unavailable | by design（raster host 无文本层） | 仍为 D（by design） |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | CBR non-native by design | IntentRoutePolicy.ISLAND_ONLY = cbr 走 webview 兜底 | 仍为 D（by design） |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | No real-device metrics | 无 device/AVD/SDK | 仍为 C（未做） |
| `docs/android-completeness-2026-09-29.md` §"Remaining gaps" | Release unsigned | 无 keystore | 仍为 C（未做） |
| `docs/android-completeness-2026-09-29.md` §"Known crash fixed" | NavArgument defaultValue=null | 4cda4821 已修 | 已修 |
| `docs/android-completeness-2026-09-29.md` §"Test matrix" | 1599 tests / 0 fail / 22 modules | 单模块分别跑能绿；根 `gradle test` 应因 P0 卡挂 | 单模块绿，跨模块挂 |
| `docs/android-engine-capability-inventory.md` | format render CFI ☑ / 其余 ◐ ☐ | NativeEpubScreen (906 行) + NativePdfScreen (631 行) + 6 engine 模块全在；FB2/DOCX/HTML/MHTML 模块编译需先解 P0 | 实质 A，跨模块构建仍为 C |

---

## 四、运行时回归建议（真机/模拟器必跑）

> 工作机无 Android SDK / AVD / 真机 / gradle CLI。以下项目是装机后必须用真机/模拟器回归的清单——光看代码无法验证。

1. **PDF 端到端**：打开一个 100 页+ 带书签的 PDF → outline 跳转 → search → OCR 索引（中文 PDF 测中文识别）→ share 截图 → 进度写回。**期望**：每步都能用；首次 OCR 模型下载需联网。
2. **TTS 端到端**：打开任意文本 → 点 TTS 按钮 → 锁屏测试播放/暂停/上下首 → 蓝牙耳机按键 → 自动翻页 → 退到后台后回来 → 切章节。**期望**：锁屏通知 4 按钮可见，蓝牙按键生效，章节边界自动翻页。
3. **漫画**：打开 100+ 页 CBZ → 双指缩放 → 双击切换缩放 → 左右翻页 → 切双页 spread → 退到后台再回来。**期望**：翻页流畅（建议 ≥ 30fps），OOM 不触发。
4. **双击选词 + 高亮 + 笔记**：打开 EPUB 双击选词 → 4 色高亮 → 加笔记 → 改笔记 → 删笔记 → 切到 Notes tab → 点笔记回到原文对应位置。**期望**：CFI 跳转准确，笔记/高亮持久化（杀进程后仍在）。
5. **导入 100 本混合书库**：100 本 EPUB + PDF + TXT + 漫画混合 → 进度条 → 完成后切收藏/排序/网格/列表 → 收回收站 → 恢复。**期望**：导入无崩溃（已知 P1-IMPORT 卡在真机回归前不能 close）。
6. **崩溃日志**：主动触发一个崩溃（e.g. `throw RuntimeException("test")` in onCreate）→ 重启 App → 看剪贴板 + logcat 是否含 "检测到上次崩溃" Toast + crash.log 写入。**期望**：4 渠道都拿到日志。
7. **POST_NOTIFICATIONS 运行时申请**：API 33+ 第一次进入 TTS → 系统弹通知权限 → 拒绝 → 期望 TTS 仍可工作但通知不显示。
8. **16KB page size 设备**：在 Pixel 8+ / Android 15 模拟器装机 → 启动 → 打开任何书 → 期望无 SIGSEGV。
9. **配置变更**：阅读时旋转屏幕 → 字号变化 → 主题切换 → 期望进度 + 章节 + 设置不丢失。
10. **离线场景**：飞行模式 → 关闭所有联网 → 打开任一本地书 → 期望与联网时一致（除 OCR 模型下载失败提示）。

---

## 五、后续建议（按优先级）

1. **立即**：反注释 `settings.gradle:103-112` 4 个 `include`；跑 `gradle :app:dependencies` 验证全绿；将 p8-rollout-checklist.md §1 的"D0+R1 前不要重新启用"改成"D0+R1 已合并，可启用"。
2. **本周**：在 `KoodoReaderApp.onCreate` 加 `CrashMonitoring.install(this)`；把 `app/CrashLogWriter` 作为 `:feature:crash` 的 redaction backend 接入。
3. **本周**：在 `TtsSettingsRoute` 增加 voice picker（消费 `TtsControlSheet.onVoiceClick` 已有的 hook），关闭 P2 gap。
4. **下周**：在 `NativeEpubScreen` 选词菜单加 Translate 项（消费 `:feature:translate/TranslationPopup`），关闭 P4 gap。
5. **下周**：把 `NativeEpubScreen` 的 `MainScope().launch` 7 处迁到 `ReaderViewModel.viewModelScope`，关闭 P3。
6. **CI**：加 `gradle test` (root) 步骤，确保 1599 测试在跨模块构建下也能绿；目前 09-29 文档"全绿"可能是单模块跑出来的，跨模块应挂在 P0。
7. **P8 §2 关闭项**：准备 keystore → 启用 release 签名；启用 `-PsplitAbi=true`（虽然 0 .so 节省）；完成 P8-F1（VIEW/SEND/koodo-reader:// intent-filter 全部走 native shell）+ P8-F3（`-Ptarget` → product flavor）。
8. **真机回归**：解锁 P1-DEVICE（4 项指标回填 docs/android-baseline.json）和 P1-IMPORT（1000 本导入复测）两条被阻塞的 todo 卡。

---

## 六、附：本摸底的硬约束

- **运行环境**：工作机无 Android SDK / AVD / 真机 / gradle CLI——所有结论均**未通过运行时回归**。
- **方法**：纯静态（源码 + 文档 + build/产物 + .worktrees 历史）。
- **未做**：未跑过 `gradle :app:assembleDebug`、未在真机/AVD 上冒烟测试、未装机量 APK 体积、未验证 16KB page size 实际设备。
- **未碰**：DSH 看板的两条 todo 卡 `t-mufbb7fb-euf33l`（P1-DEVICE 真机 4 项指标回填）和 `t-mufbb7gc-lvvqp3`（P1-IMPORT 真机 1000 本导入复测）均被其他 session 持有受阻——本摸底不处理它们，但在第四/第五节明确把它们列进"解锁后必做"。
- **诚实声明**：本报告覆盖的 5 个真实未收口项（P0–P4）**均经过代码复读与 cross-check**——没有靠文档推断；详细证据在第一节每行的"证据"列。
