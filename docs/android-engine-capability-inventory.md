# Android 引擎能力盘点（P0 Checklist ①）

> 日期：2026-09-23 · 方式：代码走查 + 既有守卫/单测证据（本机无设备，未做交互式 QA）。
> 逐项对应附录 A 的 33 项能力，为「状态列」提供依据。状态：☑ 已落地可验证 / ◐ 部分落地 / ☐ 未开始 / ✗ 不做。
> 复现命令均已在本机跑通（guards 全绿、`:app:assembleDebug -Ptarget=native` 出包成功）。

## 1. 数据与书库（附录 A.1，8 项）

| 能力 | 状态 | 依据 / 缺口 |
|---|---|---|
| 本地数据库（books/notes/bookmarks/plugins/words + temp-*） | ◐ | `core/data` Room 实体/DAO + `check-room-schema.js` 全绿；`core/dbio` 桌面⇄原生双向桥 + `check-dbio-ddl.js` + 11 单测（含 5 表 round-trip、temp-* 两走向）。缺口：迁移桥 4 项审查发现（path 改写/封面流式导出/事务包裹/连接复用）待修；无真机恢复冒烟 |
| 批量导入（本地目录+多选文件） | ◐ | `core/importer`（18 格式=桌面清单）+ `check-import-rules.js` + 41 单测含 1000 本 JVM 压测；SAF 目录/多选入口已接 Compose。缺口：真机 1000 本回填；审查 3 项（ComicCover 非局部 return/静默丢文件/失败不可区分）待修 |
| 封面生成/缓存 | ◐ | EPUB（OPF→cover meta/回退）+ CBZ（natural sort）+ `CoverStore`（桌面 `<key>.<ext>` 约定）+ 单测。缺口：PDF/MOBI 封面随 P2+ |
| 书库/书架/收藏/回收站 | ◐ | Compose 网格 + Room + ImportState 进度已落地。缺口：排序/视图模式/收藏/回收站（→ t-mudyjphx） |
| 备份/恢复/数据导入导出 | ◐ | Backup 页（导入/导出桌面 zip）已落地（原 P7 行提前具备数据双向能力）；书籍文件本体迁移与云备份仍 P7 |
| 书籍拖拽排序/视图模式 | ☐ | t-mudyjphx 范围 |
| 多语言（41 locale） | ☐ | t-mudyjpjt 范围；当前 shell 为硬编码英文 |
| 云同步 / 插件系统 | ✗ | 附录 A 明确不做（✗） |

## 2. 格式渲染（附录 A.2，7 项）

| 能力 | 状态 | 依据 / 缺口 |
|---|---|---|
| CFI 定位/解析（列在 A.3，此处提及因是格式无关基础） | ☑ | `engine/cfi` 76 黄金向量 + `gen-cfi-golden.js --check` CI 防漂移 |
| EPUB | ◐ | 解析侧 `core/importer` EpubBook（OPF 元数据/封面）已落地；渲染/分页/目录属 `engine/epub`（P2）未开始 |
| PDF | ☐ | POC 已定 pdf.js（兜底岛内），原生 `engine/pdf` 未开始（`docs/android-pdf-poc.md`） |
| MOBI/AZW3、TXT/MD、漫画、FB2/DOCX/HTML | ☐ | P4/P5 与兜底岛驻留，未开始；导入白名单已覆盖全部格式（入库不解析正文） |
| 简繁转换 | ☐ | P6 |

## 3. 阅读器内核（附录 A.3，8 项）

| 能力 | 状态 | 依据 / 缺口 |
|---|---|---|
| CFI 定位/解析 | ☑ | 同上（唯一完成项） |
| 排版引擎、高亮/笔记/书签、目录/进度/导航、手势、全书搜索、主题/字体、看图/脚注/内链 | ☐ | 全部 P2（`ReaderPlaceholderScreen` 占位）。WebView 兜底岛当前承接全部阅读功能（`-Ptarget` 双轨，MainActivity 轨可用） |

## 4. 阅读增强（附录 A.4，9 项）——全部 ☐（P6）

段落模式/速读/阅读尺、仿生、文本替换、选中自动翻页、TTS、词典、划词翻译/AI、阅读统计、OCR。兜底岛覆盖。

## 5. 基础设施盘点（跨切面，本次新增核实）

| 设施 | 状态 | 证据 |
|---|---|---|
| 桌面 .db schema 固化 | ☑ | `schema.lock` + `check-room-schema.js` + `check-dbio-ddl.js` |
| 导入规则单一事实源 | ☑ | `BookRules.kt`（18 格式）+ `check-import-rules.js`（桌面清单相等+folderBridge 子集） |
| 双轨启动（native / webview） | ☑ | `-Ptarget=native` LauncherAlias；两条 MainActivity 轨均可出包 |
| CI 守卫矩阵 | ☑ | release-android.yml：4 守卫脚本 + `:engine:cfi:test` + `:core:importer:test` + `:core:dbio:test` |
| 性能基线 | ◐ | `docs/android-baseline.json`：APK 体积/JVM 压测已测；冷启动/打开/翻页/内存 pending-device（附测量配方） |

## 6. 结论

- 已可验证落地：数据层（Room+双向桥+导入管线）与 CFI 内核，守卫矩阵完整，可支撑 P2 并行开工。
- P2 前必须清理：审查发现的迁移桥 4 项 + 导入 3 项（见看板卡评论）。
- 唯一阻断性外部依赖：真机/模拟器（性能四项 + 1000 本真机回填 + 桌面恢复冒烟）。
