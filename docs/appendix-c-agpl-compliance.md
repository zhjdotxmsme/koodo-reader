# 附录 C：License 与合规自查（AGPL-3.0）

| 项 | 值 |
|---|---|
| 任务卡 | `t-muexn6g8-xxvzbr` 验收清单 [5]「AGPL-3.0 合规自查 + 移植来源标注」 |
| 日期 | 2026-09-24 |
| 基线 | worktree `.worktrees/p8-wrap-up`，分支 `task/p8-wrap-up-xxvzbr`，commit `9abe7904` |
| 依据文件（本机实读） | 根 `LICENSE`（GNU AGPL-3.0，661 行；§5 见 L196–221，§6 见 L233–329，§1 "Corresponding Source" 定义见 L122–140）；`docs/android-loc-baseline.json`（上游 commit 固定）；`docs/android-native-migration.md` §2 与附录 C |
| 上游固定版本 | `koodo-reader/kookit` @ `dev` = `3a5bdb651d016c21d8772a9b774015163611bd48`；`johnfactotum/foliate-js` @ `main` = `78914aef4466eb960965702401634c2cb348e9b1` |

---

## C.1 结论（一句话）

本仓库与全部上游移植源均为 **AGPL-3.0**（foliate-js 为 MIT，见 C.2.1），因此**移植与再分发无法律障碍**；但"以 AGPL 开源"不等于"自动合规"——**当前 Android 产物缺少 AGPL/第三方许可文本与应用内法律声明**（C.2.3 / C.6），这是本附录的主要发现。

---

## C.2 移植来源清单

### C.2.1 已移植（Kotlin 原生轨，逐条有代码证据）

证据形式：源文件 KDoc 里显式写明的上游文件/符号（`grep` 可复现）。

| 上游（桌面）源 | 许可证 | Android 落点 | 证据（文件:符号） |
|---|---|---|---|
| kookit `src/libs/cfi.ts` | AGPL-3.0 | `engine/cfi/Cfi.kt` | KDoc 明列 `koodo-reader/kookit → src/libs/cfi.ts (AGPL-3.0)` |
| foliate-js `epubcfi.js` | MIT | `engine/cfi/{CfiOps,CfiParser,CfiSerializer,CfiTokenizer}.kt` | "port of foliate-js `collapse`/`buildRange`/`compare`/`fake`/`parser`/`tokenizer`" |
| kookit `layoutUtil.ts`、`GeneralRender`（部分） | AGPL-3.0 | `engine/layout/{LayoutEngine,BlockModel,CfiAddressing}.kt` | `LayoutEngine.kt:45` 明示 CSS multi-column ⇒ 自绘分页 |
| kookit `touchUtil.ts`、`readerMode`、`getTouchAction` | AGPL-3.0 | `engine/gesture/{GestureMode,TapZone}.kt` 等（13 文件/5 测试） | `TapZone.kt:4` "3×3 tap-zone grid, mirroring the desktop kookit `getTouchAction`" |
| kookit `noteUtil.ts`、`annotationUtil.ts` | AGPL-3.0 | `engine/annotate`（14 文件/6 测试） | 模块 KDoc + ADR-002 黄金向量 |
| kookit `navigationUtil.ts` | AGPL-3.0 | `engine/toc` | 模块 KDoc（spine+TOC/进度/搜索索引） |
| kookit `openImage` 守卫逻辑 | AGPL-3.0 | `engine/link/Image.kt:78` | "Faithful port of `openImage`'s two guards" |
| kookit `mobi.js`（PalmDOC/HUFF-CDIC/KF8/EXTH） | AGPL-3.0 | `engine/mobi`（16 文件/7 测试） | 模块 KDoc；HUFF/CDIC 未实现（挂账 `t-muexn60r`） |
| kookit `textProcessor.ts`（`isTitle`/`cleanText`/`startWithDI`）、`TxtRender.ts`（chardet） | AGPL-3.0 | `engine/text/{ChapterSplitter,CharsetDetector,Charsets}.kt` | 文件级 "NATIVE PORT of …" 注释 |
| kookit `paragraphModeUtil`/`speedReadingUtil`/`readingRulerUtil`/`bionicUtil`/`textRuleUtil`/`selectionAutoTurn.ts` | AGPL-3.0 | `engine/feature`（18 文件/8 测试） | 每个文件 "Reference: kookit `xxx`" |
| kookit `PdfRender`/`searchBox` 行为、PDF 视图模式 | AGPL-3.0 | `engine/pdf/{PdfViewportMath,PdfSearchEngine,PdfAnnotations,PasswordGate,PdfViewMode}.kt`（19 文件/9 测试） | 文件级等价性说明 |
| kookit `KookitConfig.PresetThemeList`、桌面 reader config 令牌 | AGPL-3.0 | `core/designsystem`（5 测试） | `ThemeSpec.kt:62` "order must match the desktop KookitConfig.PresetThemeList index" |
| 桌面 DDL / 备份结构（`kookit-extra` schema） | AGPL-3.0 | `core/dbio/DesktopDdl.kt` | "Desktop database order (kookit `databaseList`)"；`scripts/check-dbio-ddl.js` 逐字守卫 |
| kookit `EpubRender`/`epub.js` 语义、`comic-book.js` | AGPL-3.0 | `core/importer/{EpubBook,ComicCover}.kt` | `EpubBook.kt:35` "Parity notes vs the desktop engine (kookit `EpubRender` / `epub.js`)" |
| 桌面 SAF 导入规则（`folderBridge.js` 清单） | AGPL-3.0（同仓） | `core/importer/BookRules.kt` | Kotlin 单一事实源；`scripts/check-import-rules.js` 守卫 |
| kookit `kookit.min.js` 引擎事件语义（21 事件） | AGPL-3.0 | `:app` `NativeEventDispatcher.kt` | "Decoded engine semantics (kookit.min.js, verified)" |
| 桌面 41 个 locale 键集 | AGPL-3.0（同仓） | `core/common`（3 测试） | `scripts/sync-locales-android.js --check` 守卫 |

### C.2.2 计划移植 / **未**移植（仍在兜底岛，或未开工）

以下上游文件**尚未产出 Kotlin 派生代码**，因此当下**不构成**"已修改作品"的分发；一旦移植，须按 C.3 的 §5(a) 加注来源与日期，并同步本表：

| 上游（桌面）源 | 许可证 | 状态（2026-09-24 核实） | 影响 |
|---|---|---|---|
| kookit `ComicRender.ts`(1003) + `comic-book.js` | AGPL-3.0 | **未移植**（`engine/image` 不存在、`ComicRender` 0 命中） | 漫画只能走兜底岛（`docs/p8-rollout-checklist.md` §2.1） |
| kookit `Fb2Render.ts`(56) + `fb2.js`(351) | AGPL-3.0 | **未移植**（迁移方案列为"兜底岛长期驻留"） | FB2 只能走兜底岛 |
| kookit `DocxRender.ts`(51) | AGPL-3.0 | **未移植** | DOCX 只能走兜底岛 |
| kookit `HtmlRender.ts`(61) | AGPL-3.0 | **未移植** | HTML/MHTML/XML 只能走兜底岛 |
| kookit `zh-convert.ts`(8143) | AGPL-3.0 | **未移植**（`zhConvert`/`OpenCC` 0 命中） | 简繁转换缺失 → 原生轨与桌面行为不一致 |
| kookit `ttsUtil.ts` + 15 个 voice 插件 | AGPL-3.0 | 未移植（`TextToSpeech` 0 命中） | P6 |
| kookit `dictUtil.ts` + `js-mdict` | AGPL-3.0 / 见上游 | 未移植 | P6 |
| `plugins/renderer/translation/*`（25 源）、`aiBridge.ts` | AGPL-3.0（同仓） | 未移植 | P6 |
| `src/pages/stats/*` | AGPL-3.0（同仓） | 未移植 | P6 |
| OCR（`public/lib/{tesseractjs,onnxruntime-web,esearch-ocr}`） | 见 C.2.3 | 未移植（后端依赖 ML Kit 拟替代） | P6 |

### C.2.3 随包分发的第三方（**不**是移植，但必须保留声明）

兜底岛（`assets/webapp`）与 PDF 引擎（`assets/pdfengine`）会把这些二进制/JS 一起打进 APK。当前**通报保留情况不完整**：

| 组件 | 位置 | 随包许可文件 | 结论 |
|---|---|---|---|
| pdf.js | `assets/webapp/lib/pdfjs`、`assets/pdfengine` | `assets/pdfengine/cmaps/LICENSE`（Adobe CMaps 版权）；`assets/webapp/LICENSE` 是**通用 Apache-2.0 全文**，未标明归属 | 需补 `NOTICE`（Apache-2.0 §4(d)） |
| 7-Zip (7z-wasm) | `assets/webapp/lib/7z-wasm` | `License.txt` **有**（Igor Pavlov，含 unRAR 限制条款） | 已随包；仍需在应用内可见 |
| libunrar | `assets/webapp/lib/libunrar` | **无**许可文件 | unRAR 许可证有再分发限制 → 需人工核对并在 NOTICE 中声明 |
| sql.js / sqljs-wasm、tesseract.js、onnxruntime-web、fabric.js、highlight.js、pdf-lib、vex-js、各 CSS 主题 | `assets/webapp/lib/*` | **无**许可文件 | 逐项核实 → 生成 NOTICE（MIT/ISC/Apache 均要求保留版权与许可文本） |
| `kookit-extra.min.mjs`（引擎 bundle，位于 `assets/webapp/static/js/main.*.js`） | staged webapp | 无 | 属同项目上游（`koodo-reader/kookit-extra`）→ 对应源码为该仓库；**发布前须确认其许可证文件**（AGPL 同族待证） |
| Compose / AndroidX / Room / Kotlin stdlib | APK dex | 无（依赖其 Maven 元数据 + 各自 NOTICE） | Apache-2.0，需在 NOTICE 汇总 |

> **注意**：`docs/android-native-migration.md` 附录 C 已声明“kookit = AGPL-3.0、foliate-js = MIT（vendored zip.js BSD-3 / fflate MIT / PDF.js Apache）”。本附录不推翻该结论，只是把**随包分发的第三方清单**补全为可执行动作。

---

## C.3 AGPL-3.0 §5 对应源码可得性声明

### C.3.1 条款原文（本机 `LICENSE` L196–221 摘录）

> **5. Conveying Modified Source Versions.**
> You may convey a work based on the Program, or the modifications to produce it from the Program, in the form of source code under the terms of section 4, provided that you also meet all of these conditions:
> **a)** The work must carry prominent notices stating that you modified it, and giving a relevant date.
> **b)** The work must carry prominent notices stating that it is released under this License and any conditions added under section 7. …
> **c)** You must license the entire work, as a whole, under this License to anyone who comes into possession of a copy. …
> **d)** If the work has interactive user interfaces, each must display Appropriate Legal Notices; …

"Corresponding Source" 定义见 L122–140：生成、安装、运**行**目标代码并修改该作品所需的全部源码，含接口定义文件与"作品被特意设计为需要"的动态链接子程序源码；可用其它部分的对应源码自动再生成的内容**无需**包含。

### C.3.2 本项目的声明（正式表述）

> **本作品（Koodo Reader，含 Android 客户端）是 AGPL-3.0 作品的修改版本。**
> 修改内容：在 `android/` 子树以 Kotlin/Compose 重写渲染与数据层，并以 `engine/*`、`core/*`、`feature/*` 模块**移植**上游渲染引擎（来源见 C.2.1）。修改自 2026-09-23 起持续进行，逐次发布于本仓库的 tag。
> 本作品整体以 **GNU Affero General Public License v3.0** 发布（根 `LICENSE`），未附加 §7 允许范围之外的任何额外条款（§10）。
>
> **对应源码（Corresponding Source）的提供方式**：
> 1. 完整源码仓库：本仓库（AGPL-3.0）；每个发布 tag 对应一个可构建的完整源码快照。
> 2. 随每个 APK 发布同时提供该 tag 的源码归档（`git archive <tag>`，无密码、无密钥、可直接解包）。
> 3. 构建所需材料（**均在上述归档内**）：`android/` 全部 Gradle 脚本与 `settings.gradle`；`scripts/build-android.js`（资产暂存 + 出包）与各守卫脚本；环境要求 JDK 17、Gradle 8.5、AGP 8.2.2、Kotlin 1.9.24、compileSdk 34（见 `docs/android-baseline.json` 的 `provenance`）。
> 4. 上游移植源（不随 APK 以二进制形式再分发，仅作为改写依据）：`koodo-reader/kookit` @ `3a5bdb65…`、`johnfactotum/foliate-js` @ `78914aef…`（固定 commit 便于逐行比对）。
> 5. 兜底岛内随包分发的 web 引擎产物（`assets/webapp`）的对应源码 = 本仓库 + `koodo-reader/kookit-extra`（待确认许可证，见 §C.8 R3）。
> 6. 第三方（C.2.3）的对应源码由各自上游提供，本项目仅在 `NOTICE` 中汇总归属与许可证，不作修改。

### C.3.3 逐条对照（§5 a–d）

| 条款 | 本项目现状 | 差距 / 动作 |
|---|---|---|
| a) 修改声明 + 日期 | **部分满足**：已被移植的 Kotlin 文件普遍带来源 KDoc（C.2.1 证据列） | 未统一含"修改日期"；建议按 C.7 模板补齐并加守卫 |
| b) 以本许可证发布的声明 | 根 `LICENSE` 存在；仓库 README/包元数据已声明 AGPL | APK 内**未携带 AGPL 文本**（`grep LICENSE` 在 `build-android.js`/`app/build.gradle` 中 0 命中）→ C.6 A1 |
| c) 整体以 AGPL 授权、不附加额外限制 | 无附加条款；无 DRM/设备锁定 | §C.8 R2（与闭源崩溃 SDK 的关系需法务确认） |
| d) 交互界面显示 Appropriate Legal Notices | **未满足**：Android 应用内**没有**"关于/开源许可"页面（grep `About|license|许可` 0 命中） | C.6 A2：新增 About 页（AGPL 全文 + NOTICE + 源码获取链接） |

---

## C.4 §6 物体代码分发（APK）与 Installation Information

| 项 | 本项目情形 | 结论 |
|---|---|---|
| §6 提供机器可读对应源码的方式 | 采用 §6(d)：在**同一发布位置**（GitHub Release / 下载页）同时提供 APK 与该 tag 源码归档链接 | 满足 |
| §6 "User Product" 与 Installation Information | APK 面向普通消费设备（手机）；本项目**不锁定**设备——用户可自由安装修改版（自签名/侧载），不依赖本项目持有的密钥 | 无 Tivoization 问题；无需额外提供安装信息 |
| 签名密钥 | 发布用自签名密钥仅用于签署官方包，**不构成**安装修改版的障碍 | 保持即可 |

---

## C.5 §13 网络交互（AGPL 特有）

AGPL 在 GPL 之上增加：若用户通过网络与修改版交互，须提供获取对应源码的机会。本项目：

| 情形 | 是否触发 §13 | 说明 |
|---|---|---|
| 云同步 / WebDAV / S3 | **不适用** | 迁移方案明确"不做云同步"（附录 A.1 ✗） |
| KOReader / OPDS（Go HTTP 服务） | 桌面端特性 | 服务端源码即本仓库（`httpserver/`），已可获取 |
| 崩溃上报后端（Sentry / Crashlytics 托管服务） | **不触发**：本项目不分发也未修改这些服务的服务端代码，仅调用其客户端 SDK | 但客户端 SDK 的许可证与隐私条款需在 NOTICE/隐私说明中列明（C.2.3、C.8 R2） |
| 未来若自建任何网络服务（同步/账号） | **会触发** | 届时必须在该服务的界面上提供下载对应源码的显著入口 |

---

## C.6 发布前合规动作清单（DoD）

| ID | 动作 | 验收判据 | 状态 |
|---|---|---|---|
| **A1** | 把 AGPL-3.0 全文 + `NOTICE` 打进 APK（两种变体都要：`-PstripIsland` 会删掉 `assets/webapp/LICENSE`，故不能依赖它） | `unzip -l *.apk \| grep -E 'license\|NOTICE'` 非空 | ☐ 待办 |
| **A2** | 新增应用内"关于 / 开源许可"页面：AGPL 声明、修改声明与日期、NOTICE 列表、源码获取链接（§5(d)） | 设置页可达；文本含 AGPL 与源码 URL | ☐ 待办 |
| **A3** | 生成第三方 `NOTICE`（C.2.3 逐项核实许可证文本） | `docs/`（或根）存在 NOTICE 文件，覆盖全部随包组件 | ☐ 待办 |
| **A4** | 每个移植源文件加统一的来源+日期头（§5(a)） | 新守卫脚本校验（C.7） | ☐ 待办 |
| **A5** | 发布流程：APK 与 tag 源码归档同页发布（§6(d)） | Release 页面同时含 APK 与 `*-src.tar.gz` | ☐ 待办 |
| **A6** | 本附录随每次格式移植更新 C.2.1/C.2.2 表 | PR 检查项 | ◐ 机制待建 |
| **A7** | 确认 `kookit-extra` 仓库许可证并与本附录一致 | 上游 `LICENSE` 文件 + 本附录条目 | ☐ 待办（C.8 R3） |
| **A8** | 兜底岛下线后复核：nativ 变体不再分发 webapp → C.2.3 中仅 pdf.js 相关条目仍适用 | 重新生成 NOTICE | ☐ 待办（依赖 P8-F1） |

---

## C.7 如何自动守住（建议守卫）

`scripts/check-agpl-provenance.js`（建议新增，Node 侧、可在 CI 跑）：

1. 扫描 `android/{engine,core,feature}/**/*.kt`，要求凡命中上游符号白名单（`cfi.ts`/`layoutUtil`/`touchUtil`/`noteUtil`/`mobi.js`/`textProcessor`/`paragraphModeUtil`/`zh-convert`/`PdfRender` … 见 C.2.1）的文件，头部 40 行内含 `Ported from:` 与 `Upstream license:` 标记；
2. 校验标记里的日期格式 `YYYY-MM-DD`；
3. 校验 `NOTICE` 覆盖 `assets/webapp/lib/*` 的全部目录（每个目录至少被 NOTICE 提及一次）。

统一头模板：

```kotlin
/*
 * Ported from: koodo-reader/kookit @ 3a5bdb651d016c21d8772a9b774015163611bd48
 *              src/libs/<upstream-file>.ts  (AGPL-3.0)
 * Modified for the Android native engine on 2026-09-24.
 * This file is part of Koodo Reader, licensed under AGPL-3.0 (see LICENSE).
 */
```

> 现状：既有文件多为自然语言 KDoc（如 `Cfi.kt` 明列上游与许可证），信息齐全但格式不统一 → A4 是一次纯格式化改动，不改逻辑。

---

## C.8 已知风险与待法务确认项

| # | 风险 | 说明 | 建议 |
|---|---|---|---|
| **R1** | 应用内无法律声明（§5(d)） | 交互式 UI 未展示 AGPL/第三方声明，属**明确未满足**的条款 | A2 优先于发布 |
| **R2** | 闭源崩溃 SDK 与 AGPL 并存 | Sentry SDK 为 MIT，风险低；**Firebase Crashlytics 是闭源 Google SDK**，与 AGPL 作品一起分发是否构成 §10 意义上的"附加限制"需法务判断 | 默认优先 Sentry；Crashlytics 适配器落地前先确认（本卡 `android/feature/crash` 不引入任何后端依赖，故未扩大风险面） |
| **R3** | `kookit-extra`（webapp 引擎 bundle）许可证未核实 | 若其非 AGPL 兼容，兜底岛分发受影响 | A7：读上游 `LICENSE` 并记录 |
| **R4** | libunrar 的 unRAR 许可证限制 | 该许可禁止用其逆向 RAR 算法；随包分发需保留条款文本 | A3 中显式列出；考虑替换为无限制实现或延迟加载 |
| **R5** | 大部分 web 库无随包许可证文件 | MIT/Apache 均要求"保留版权与许可声明"，缺失即为违规 | A3 生成 NOTICE 并从上游取回许可证文本 |
| **R6** | 移植声明仅有"仓库名"未固定 commit | 影响可追溯性（非法律硬性要求，但利于 §5(a) 举证） | C.7 模板固定 commit（本附录已给出两个 SHA） |

---

**附**：本附录不构成法律意见；上述条款判断基于根 `LICENSE` 原文与上游公开元数据（`docs/android-loc-baseline.json` 的 commit 记录），发布前建议由项目维护者或法务按 C.6 清单复核。
