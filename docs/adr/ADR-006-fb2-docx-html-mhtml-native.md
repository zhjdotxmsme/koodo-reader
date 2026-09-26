# ADR-006 FB2 / DOCX / HTML / MHTML：原生化立项 vs 兜底岛长期保留

- 日期：2026-09-24
- 状态：提议（待用户确认后转为"已采纳"）
- 关联：迁移方案 §3.1 格式路由、§6 阶段计划（P5/P8）、风险 R5–R7；ADR-002（定位与标注兼容）、ADR-003（兜底岛生命周期）；任务卡 `t-muexn64a-j64yvl`
- 评估依据：[`docs/fb2-docx-html-mhtml-eval.md`](../fb2-docx-html-mhtml-eval.md)（本 ADR 的论证与数据来源）

## 背景

迁移方案 §3.1 与附录 A.2 把 FB2 / DOCX / HTML / MHTML 四个格式登记为"**兜底岛长期驻留**，单独立项"。ADR-003 据此把兜底岛的生命周期推向 ③ 冻结期：**只修崩溃，不加功能**。

本评估（P5 评估卡）对四个格式做了源码级走查后，发现上述登记的两条隐含前提都不成立：

1. **"四格式是四个独立的大工程"不成立**。桌面端四个格式收敛到**同一条管线**（`src/router/index.tsx`：`/docx`、`/fb2`、`/html`、`/xhtml`、`/mhtml` 全部指向 `HtmlReader`），差异只在"文件 → HTML/XHTML"的前置转换器。三个 renderer 各只有 51–61 行代码，且 DOM 触达 ≤ 20%，真正的重活在两个第三方 JS 库（`mammoth`、`mhtml2html`）里。
2. **"保留兜底岛不划算就留着"不成立**。兜底岛的成本是**全有或全无**：只要还有一个格式走 WebView，APK 就必须携带 WebView 宿主 + `assets/webapp` + 引擎 bundle + 41 个 locale + `nativeBridge.js`/`folderBridge.js` 契约守卫（ADR-003 配套纪律 1），双轨 CI 也得保留。为 1 个格式留岛，与为 4 个格式留岛，成本几乎一样。

同时，原生侧的集成缝已经在 P0–P2 落地：`engine/layout` 的 `EpubDocument / SpineItem / TextBlock` 就是"DOM 等价层"，`engine/cfi`、`engine/annotate`、`engine/toc`、`engine/link`、`engine/gesture` 全部与格式无关；`core/importer/BookRules.kt` 已含 18 格式与 MIME 映射（含 `fb2`/`docx`/`mhtml`/`xhtml`）。**四格式原生化 = 写 4 个"文件 → List<TextBlock> 生产者"，而不是写 4 个阅读器。**

## 决策

**立项为原生，四格式全部原生；不采用"兜底岛长期保留"。**

1. 四格式登记从"兜底岛长期驻留"改为"**原生立项（P5.5）**"，按 `FB2 → MHTML → HTML → DOCX` 顺序推进（先做确定性最高、最便宜的，把不确定性最大的 DOCX 放在最后）。
2. 新增模块（均为 `engine/*` 纯 Kotlin JVM 模块，**零外部运行时依赖**，遵守现有模块约定）：`engine/fb2`、`engine/htmlbook`（HTML + MHTML 共用）、`engine/docx`；另立 `core/archive` 作为**唯一** zip 门面（见风险 R1）。`engine/docx` 的 DOCX 解析采用 **vendor `mammoth-java`（`org.zwobble.mammoth:mammoth`，BSD-2，248 KB，零运行时依赖）+ 一处 Android 兼容补丁**，而不是 Apache POI / docx4j（否决理由见评估 §4.4）。
3. 每完成一个格式即按 ADR-003 ② 从兜底岛路由下线该格式（改一行路由 + 跑该格式回归集）。
4. 兜底岛仍按 ADR-003 ③ 冻结，**目标在 P8 退役**；不得因为"反正还要留着"而给这四个格式加功能。
5. **保真度让步要写进产品文案**：原生 HTML/MHTML 走"块级扁平化"近似（与 EPUB 分页视图同一近似），复杂 CSS（多栏、浮动、背景图拼版、公式）不承诺像素级一致；只承诺 **CFI 定位与标注与桌面一致**（ADR-002），UI 展示章节 + 百分比而非绝对页码（与 R3 一致）。

## 备选方案与理由

| 备选 | 结论 | 理由 |
|---|---|---|
| A. 维持 §3.1 原状：四格式长期驻留兜底岛 | **否决** | 成本全有或全无：为一个格式保留整条 WebView 轨（宿主 + webapp + 引擎 bundle + locale + 契约守卫 + 双轨 CI），且冻结纪律（ADR-003 ③）意味着这四类书的**非崩溃缺陷按约定不修**——用户可见的长期退化。R6/R7 永久化，与迁移初衷冲突。 |
| B. 只原生便宜的（FB2/MHTML），贵的（DOCX）留在岛上 | **否决** | 岛仍需完整保留 → A 的全部成本照付，只省下 DOCX 的 12–18 人/天，收益为负（岛的不动成本远大于此）。 |
| C. 全原生（本决策） | **采纳** | 边际成本 = 4 个转换器（合计 34–51 人/天，约 7–10 周单人），复用 P2 已建的排版/CFI/标注/目录/手势栈；换来 P8 兜底岛彻底退役（R7 体积 + R6 双轨同时消解）。 |
| D. 岛降级为**按需动态特性模块**（Play Feature Delivery），仅 DOCX 用 | **保留为兜底计划** | 这是 A/B 的唯一"体面版本"：base APK 不含岛，DOCX 首次打开时下载。但它保留维护面（WebView 宿主 + 契约守卫 + 冻结纪律），且引入下载/离线/审核复杂度。**仅当 DOCX 原生排期滑出 P8 时启用**，不作为默认。 |
| E. 用第三方 Android 阅读 SDK 替代 | **否决** | 数据格式（CFI / schema）与桌面不兼容，破坏 ADR-002；且无覆盖 FB2+MHTML 的成熟 SDK。 |
| F. DOCX 改为导入期一次性转换（导入即转成 HTML 书） | **否决为"省钱手段"** | 转换代码量不变（仍要产出与 `mammoth` 等价的 HTML），只是把调用点从读取期挪到导入期；若产出的 HTML 结构与桌面不一致，反而**破坏 CFI 对齐**。仅可作为"接受 DOCX 标注不与桌面互通"的降级项单独立项。 |

## 影响

**正面**
- P8 可以真正删掉 `assets/webapp` + WebView 宿主：APK 体积（R7）、双轨维护（R6）、契约守卫负担一次性消解。
- 四格式获得与 EPUB 相同的原生体验：原生手势/fling、TTS、字典、翻译、统计、OCR（P6 全部功能对四格式一次性生效，而兜底岛内它们是"另一套实现"或不可用）。
- 排期可见：4 个格式 = 4 个可独立验收的里程碑（每格式下线即一个 §3.1 路由行变更）。

**负面**
- 新增 3–4 个模块与相应单测/守卫，短期人力从 P6 挤占约 7–11 周单人。
- HTML/MHTML 的原生近似会有**可见的排版差异**（复杂 CSS），需要 changelog 与 UI 提示；DOCX 的保真度上限低于桌面（mammoth 的样式覆盖更全）。
- 4 个新模块 + `core/archive` 会与正在并行的 P5 CBZ（`engine/image`）争抢 zip / 图片 / 编码探测三块基建（见评估文档 §7）。

## 验证方式

- **每格式回归集**：≥ 20 本真实语料（FB2：含 cp1251/koi8-r 与嵌套 poem/table 的俄语/中文书；MHTML：浏览器另存的图文页；HTML：单文件书与 `kookitmarker` 章节切分样本；DOCX：Word 直存 + Calibre 转换两种来源），逐本"可读 + 目录正确 + 图片显示"。
- **CFI 对齐**：桌面 ⇄ 安卓 双向位置一致率 ≥ 99%（ADR-002 既定指标），用 golden-structure 守卫固化（见风险 R5）。
- **兜底岛下线证据**：路由表该行变更 + WebView 不再被该格式加载（启动/打开日志断言）。
- **P8 终态**：`-Ptarget=native` 主变体启动路径无 WebView 加载；APK 体积相对 20.03 MB 基线（`docs/android-baseline.json`）下降可量化。

## 回滚方案

- 单格式回滚：格式路由是数据驱动的单表（ADR-003 回滚方案），改回一行即恢复兜底岛渲染；已合入的原生模块保留但不接线路由，不产生运行时成本。
- 整体回滚：兜底岛代码（`LocalAssetServer`、`nativeBridge.js`、`folderBridge.js`、`webapp` staging）在 P8 之前不删除，`--target webview` 始终可出包（ADR-003 配套纪律 2）。
- DOCX 专项回滚：若 vendor `mammoth-java` 路线受阻（`SimpleSax` 补丁不成立 / 需要 core library desugaring 或抬升 minSdk / 两套 mammoth 的输出差异无法收敛），先回退到自研 WordprocessingML 子集（16–24 人/天）；若自研子集在语料上仍不达标（< 80% 可接受率），退回备选 D（按需动态功能模块）或改用导入期转换 + 明确的标注不互通声明。

## 实施状态（2026-09-25）

四个生产者模块全部落地并接入原生阅读管线，均为**纯 JVM、零第三方运行时依赖**（走 ADR 的"自研子集"路线，未引 mammoth）：

| 模块 | 交付 | 测试 |
|---|---|---|
| `engine/htmlbook` | `HtmlDocument`（HTML/XHTML/HTM/XML → TextBlock，复用 D0 + engine:text 编码探测）、`MhtmlDocument`（MIME 拆包 + base64/quoted-printable + charset，只取 text/html part） | 15 项 |
| `engine/fb2` | `Fb2Document`：FB2 XML → HTML 等价物（`<title>`→`<h1>`、`<subtitle>`→`<h2>`、epigraph/poem 去标签留文本、`<binary>`/`<description>` 剥离、notes 体跳过）→ 扁平化；声明编码优先（`encoding=`），否则探测 | 8 项 |
| `engine/docx` | `DocxDocument`：OOXML zip（`:core:archive` 门面）→ `word/document.xml` 段落/run/制表/换行/标题样式/表格拍平 + `docProps/core.xml` 标题 → 扁平化 | 9 项 |

接线（`:app`）：`WebBookSession` / `Fb2BookSession` / `DocxBookSession` 三个 `ReaderSession` 实现，`NativeEpubScreen` 按 format 分派，`ShellNavHost` 路由 HTML/HTM/XHTML/XML/MHTML/MHT/FB2/DOCX → 原生阅读屏。**至此规划的全部可原生格式（EPUB/PDF/TXT/MD/MOBI/AZW/AZW3/CBZ/CBT/CB7/HTML/XHTML/XML/MHTML/FB2/DOCX）共用一条分页+CFI 管线；仅 CBR 按本 ADR §4.2 明确不原生（UnRAR 许可），保留兜底岛。**

**未闭环**：§验证方式 的 ≥20 本真实语料回归与"图片显示"（纯文本阅读不含图片渲染）、桌面⇄安卓 CFI ≥99% 黄金守卫、真机回归——均需设备与语料，挂账后续卡。
