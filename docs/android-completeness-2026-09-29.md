# Android 原生轨完成度复审 · 2026-09-29

> 对照基线：`docs/android-completeness-2026-09-24.md`（下称"924 审查"）。
> 间隔内 android/ 下共 **84 个 commit**。本复审基于：路由源码直读、
> 全部 module 的 JUnit XML 实测聚合、`scripts/check-p6-entries.js` 实跑、
> CI 配置复核。**真机项仍未验证**（无设备）。

---

## 0 · 一句话结论

924 审查的判断「**代码交付面很宽，产品接线面很窄**」已不再成立：
**接线面已基本补齐** —— 全部文本格式走原生阅读屏、漫画走原生 ComicViewer、
P6 六个增强模块四个已挂 UI，单元测试从 46 失败收敛到 **0 失败**。
剩余缺口集中在：翻译弹窗无选词源、OCR 模型下载层、CBR（设计使然）、
真机指标与 release 签名。

## 1 · 关键指标对比

| 维度 | 924 审查 | 本次（09-29） |
|---|---|---|
| 单元测试 | 1154 唯一 / **46 失败**（6 模块） | **1599 执行 / 0 失败**（1184 JVM + 415 debug variant，逐 XML 聚合） |
| 幽灵 module | 4 个（settings 注册了不存在目录） | **全部实装**：`:core:archive` 36 测、`:engine:fb2` 8、`:engine:htmlbook` 15、`:engine:docx` 9 |
| 新注册 module | — | `:core:designsystem`（128 测，自含 selfCheck）、`:core:ui` |
| 格式路由（书库点开书） | 仅 PDF 原生；其余全部占位页 | EPUB/TXT/MD/MOBI/AZW/AZW3/HTML/HTM/XHTML/XML/MHTML/MHT/FB2/DOCX → `NativeEpubScreen`；PDF → `NativePdfScreen`；CBZ/CBT/CB7 → `ComicViewerActivity`；**仅 CBR 落兜底岛**（ADR-006 §4.2 决策） |
| intent 路由（P8-F1） | 未做，挡住兜底岛剥离 | ✅ `IntentRoutePolicy.kt` + `MainActivity` 实装 |
| P6 入口 | 统计/词典/OCR 可达；TTS/翻译未挂 | `check-p6-entries.js`：StatsScreen / DictManagementScreen / **TtsControlSheet** wired；OCR 以 pipeline 形式接入 PDF 屏；**TranslationPopup 仍 pending**（缺阅读器选词源） |
| 已知崩溃 | — | "一打开就闪退"（NavArgument defaultValue=null 建图期崩溃）已修（4cda4821） |
| CI 审查三项问题 | 机器路径入库 / 手动装 Gradle / ubuntu-22.04 | 机器路径已移出 `gradle.properties`；CI 改用 `./gradlew`（含 `chmod +x`，b145aa39） |

测试全绿明细（debug 变体 + 纯 JVM 合并，来源：各 module `build/test-results` XML）：

- JVM 模块 21 个全绿：archive 36 · common 22 · dbio 22 · designsystem 128 · importer 56 · locale 55 · annotate 112 · cfi 4 · docx 9 · fb2 8 · feature 84 · gesture 40 · htmlbook 15 · image 78 · layout 175 · link 72 · mobi 74 · pdf 65 · text 71 · toc 43 · crash 15
- Android 变体模块全绿：app 146 · dictionary 63 · ocr 39 · stats 34 · translate 78 · tts 55
- 924 审查的 6 个失败模块（toc 17 / annotate 11 / gesture 6 / locale 4 / pdf 5 / dbio 3）**全部清零**

## 2 · 阅读器功能面（924 后新增接线）

EPUB 系阅读屏（`epubhost/NativeEpubScreen.kt`）在间隔内补齐了核心体验闭环：

- 双击选词 + 高亮/笔记菜单（7b097892）
- 高亮渲染（含笔记存储颜色、绿色值修复）（a22d075d、a72e8f52）
- 删除高亮 + 字典查询 + 目录浏览（80f29e10）
- 字号调整 + 书签 + 全文搜索（a7623ff5）
- TTS 朗读：🔊 按钮 + TtsControlSheet + 自动翻页（82cee71f）
- 全局崩溃日志（无 adb 可抓崩溃）+ 启动加固（eca1dff1、27617bd5）

## 3 · 剩余缺口（按优先级）

| # | 缺口 | 性质 | 建议 |
|---|---|---|---|
| 1 | **TranslationPopup 未挂载** | check-p6-entries 唯一 pending 项；组件本身 stateless 已就绪，缺阅读器选词文本源 | 阅读器已有双击选词链路（高亮菜单同源），把选中文本接给 popup 即可 |
| 2 | **OCR 模型下载适配层** | `OptionalModuleApi` 不存在导致 ML Kit 下载器被隔离；当前工作树有 `OcrModelInstaller.kt` + 测试的替代实现（未提交/进行中） | 完成替代实现并接线 manifest meta-data（`docs/p6-stats-ocr-design.md` §5） |
| 3 | **兜底岛仍在默认构建** | `-PstripIsland` 仍 opt-in（默认 false）。但唯一前置 P8-F1 intent 路由**已完成** | 可考虑将 strip 设为默认并跑一次 intent 冒烟（真机/模拟器）后固化 |
| 4 | **PDF 批注不可用** | 栅格宿主无文本层（924 已记录，未变） | 长期项，需文本层或换渲染策略 |
| 5 | **CBR 不原生** | ADR-006 §4.2 明确决策（UnRAR 许可 + .so 16KB），走兜底岛 | 设计使然，无需动作 |
| 6 | **真机指标空白** | 冷启动/翻页/内存 Macrobenchmark、1000 本导入、intent 冒烟均未在设备上验证 | 有设备后跑 `scripts/ci-macro-benchmark.sh` |
| 7 | **release 未签名** | release APK 为 unsigned | 上架前配签名（CI secrets） |

## 4 · 风险与备注

- **工作树有未提交改动**（本复审时）：`feature/ocr` 的 `MlKitModelDownloader.kt` 修改 + 新增 `OcrModelInstaller.kt` / 测试 —— OCR 缺口正在收口，提交前需 `gradle -p android :feature:ocr:test` 复核。
- `:core:ui`（Compose 设计系统）已注册，与 `:core:designsystem` 构成 UI/UX 子项目的值层+组件层，924 时尚不存在。
- `.review/` 与 KSP 中间产物已入 gitignore（2b0625fc），复审用的测试日志均为本地产物。
- CI 审查（`docs/audit-ci-design-alignment.md`）的 HIGH/MEDIUM 项中机器路径与手动 Gradle 已修；`ubuntu-22.04` 废弃项属 `release.yml`（桌面端），本次未复核。

---

*复审方法说明：测试数字为各 module 最近一次 `gradle test` 的 JUnit XML 直接聚合（非全量重跑）；路由结论为 `ShellNavHost.kt` / `IntentRoutePolicy.kt` / `MainActivity.kt` 源码直读。*
