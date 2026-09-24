# FB2 / DOCX / HTML / MHTML 原生化立项评估（P5）

| 项 | 值 |
|---|---|
| 任务卡 | `t-muexn64a-j64yvl`（P5：FB2 / DOCX / HTML / MHTML 原生立项评估） |
| 评估基准 commit | `9abe7904`（worktree `.worktrees/p5-fb2-eval`，branch `task/p5-fb2-eval-fb2d`） |
| 上游 pin | `koodo-reader/kookit` @ `3a5bdb651d016c21d8772a9b774015163611bd48`（与 `docs/android-loc-baseline.json` 同一 ref） |
| 关联文档 | [`android-native-migration.md`](android-native-migration.md) §3.1 / §6 P5 / 附录 A.2 · [`ADR-002`](adr/ADR-002-cfi-compat.md) · [`ADR-003`](adr/ADR-003-fallback-island.md) · 结论 ADR：[`ADR-006`](adr/ADR-006-fb2-docx-html-mhtml-native.md) |
| 评估方式 | 上游源码逐文件走查（raw @ pin）+ 本仓 `docs/`、`android/` 现存模块走查 + 行级 DOM 依赖静态统计；**无真机、无运行态对照** |
| 工时口径 | 1 名熟悉 Kotlin/Android + 阅读器领域的全职工程师（与 §6 阶段计划同口径） |

---

## 0. 结论摘要

1. **结论：四格式全原生立项（P5.5），不采用"兜底岛长期保留"**（决策与备选见 §6 与 ADR-006）。
2. **工时合计 34–51 人/天**（约 7–10 周单人）：FB2 8–12 · MHTML 5–8 · HTML 8–12 · DOCX 12–18（选型见 §4.4；若回退自研子集则 16–24，合计 37–56）。
   另有一个**共享前置 D0**：`XHTML/HTML → List<TextBlock>` 扁平化器（本属 P2 EPUB 范围，见 §3.4）。
3. **四格式不是四个阅读器**。桌面端四格式收敛到同一条 `HtmlReader` 管线（`src/router/index.tsx:158-165`），差异只在"文件 → HTML/XHTML"的前置转换器；三个 renderer 各只有 51–61 行代码，DOM API 命中行分别只有 2 / 1 / 6 行（正则口径，§2.2）。
4. **原生的真正成本是"格式 → `TextBlock[]` 生产者"**：`engine/layout` 的 `EpubDocument / SpineItem / TextBlock` 就是 DOM 等价层，`engine/cfi`、`engine/annotate`、`engine/toc`、`engine/link`、`engine/gesture` 全部格式无关（§3）。
5. **兜底岛是"全有或全无"成本**：为一个格式留岛 = 为四个格式留岛（WebView 宿主 + `assets/webapp` + 引擎 bundle + 41 locale + 契约守卫 + 双轨 CI），且 ADR-003 ③ 冻结纪律意味着这四类书的非崩溃缺陷**按约定不修**（§6 备选 A）。
6. **DOCX 是唯一需要做库选型决策的格式**：Apache POI 与 docx4j 都因 Android 缺 StAX/JAXB、体积与维护问题被否决（§4.4）；而"mammoth 只有 JS 版"是错的——`org.zwobble.mammoth:mammoth:1.12.2`（BSD-2、**零运行时依赖**、与桌面同一套内容模型）确实存在，只是开箱在 Android 上因 `SAXNotRecognizedException` 不可用，需 vendor 源码 + 一处补丁（上游 issue #42 未修）。
7. **最大工程风险不是这四个格式，而是并行 P5 CBZ 卡带来的基建重复**（zip / 图片 / 编码探测三块），且**已经可观测**（§7 R1–R3；另有四格式共同的解析器输入安全项 R9）。

---

## 1. 评估范围与不做什么

**范围**：四个格式（FB2、DOCX、单文件 HTML 家族 `.html/.htm/.xhtml/.xml`、MHTML）的原生化可行性、工时、依赖选型、结论。

**不做**（按任务约束）：不写新 Kotlin 模块、不产出实现；不改 `android/settings.gradle` / `android/app/build.gradle` / `android/build.gradle`（接入用 patch 模板见 [`docs/patches/p5-fb2-settings-gradle.patch`](patches/p5-fb2-settings-gradle.patch)）；不做设备实测。

**已知评估局限**（据实记录）：
1. 本机**没有 kookit 本地克隆**（`CLAUDE.md` 里的 `D:\Project\kookit` 不存在），因此上游源码是用 `raw.githubusercontent.com` @ pin 逐文件取回后**离线统计**的（非 AST，见 §2.2 口径说明；代码行数与仓库基线逐项一致，已做交叉校验）。
2. 无 Android 设备/AVD（`docs/android-baseline.json` 的 `device.*` 均为 `pending-device`），因此本评估**没有任何运行态数据**，"读得出来/排得对不对"全部基于代码走查。
3. 无遥测数据，因此**无法按真实使用率给四格式排收益权重**；§5 的收益部分按"消解固定成本"计价，不按"每格式用户量"计价。

---

## 2. 桌面实现事实（源码走查结果，非推测）

### 2.1 四格式在桌面收敛为一条管线

```js
// src/router/index.tsx:158-165
<Route component={HtmlReader} path="/docx" />
<Route component={HtmlReader} path="/fb2" />
<Route component={HtmlReader} path="/html" />
<Route component={HtmlReader} path="/xhtml" />
<Route component={HtmlReader} path="/mhtml" />
```

即：**四个格式共用同一个 `HtmlReader` 页面 + `HtmlBook` 模型 + `rendition` API**，桌面侧的差异完全在"文件 → HTML/XHTML"的前置转换：

| 格式 | kookit 入口 | 前置转换（真正的脏活） | 汇入 |
|---|---|---|---|
| FB2 | `renders/Fb2Render.ts` → `libs/fb2.js` (`makeFB2`) | **仓库内 JS，351 code 行**，自研 XHTML 结构 + 元数据 | `makeFB2()` → book 对象 |
| DOCX | `renders/DocxRender.ts` | **外部 npm 包 `mammoth@^1.8.0`**（`package.json`） | `libs/html.ts: makeHtmlBook(html,false)` |
| HTML / XHTML / XML | `renders/HtmlRender.ts` | 无（直接读文本） | 同上 |
| MHTML | `renders/HtmlRender.ts`（`format === "MHTML"` 分支） | **外部 npm 包 `mhtml2html@^3.0.0`**：`mhtml2html.convert(html).window.document.documentElement.innerHTML` | 同上 |

`kookit.min.js` 的头部 import 行印证了依赖集：`underscore, rangy, jszip, fflate, chardet, js-untar, mammoth, marked, mhtml2html`（`src/assets/lib/kookit.min.js`，仅读其 import 头，未读实现体）。

**这条事实直接决定工时量级**：桌面为 DOCX/MHTML 各花了 51/61 行胶水 + 一个成熟 npm 包；为 FB2 花了 351 行自研。原生化要复刻的是**转换器**，不是阅读器。

### 2.2 源码对照表（要求项 ③）

行数取自 `docs/android-loc-baseline.json`（与本评估的代码行统计逐项一致）；"DOM 命中行"= 非空非注释代码行中命中 DOM/Web API 正则的行数（口径与可复现方法见下表下方说明）。

| 桌面文件 | lines | code | 依赖 | DOM 命中行 | 占比 | 移植结论 |
|---|---|---|---|---|---|---|
| `src/renders/Fb2Render.ts` | 58 | 56 | `utils/layoutUtil`、`utils/generalParser`、`libs/fb2`、`GeneralRender`、`libs/cache` | 2 | **4%** | 骨架直译（3 个方法）；另有 4 行是宿主骨架调用（`element`/`createIframe`/`getDocument`/`handleLayout`），与 EPUB 共享 |
| `src/renders/DocxRender.ts` | 53 | 51 | 同上 + `libs/html` + **`mammoth`** | 1 | **2%** | 骨架直译；`parse()` 一行调 mammoth——原生要重写的是 mammoth 的职责 |
| `src/renders/HtmlRender.ts` | 64 | 61 | 同上 + **`mhtml2html`**、`Blob`、`FileReader` | 6 | **10%** | 骨架直译；MHTML 分支 100% 依赖 `window.document`（不可移植） |
| `src/libs/fb2.js` | 391 | 351 | 无第三方（`DOMParser`/`Blob`/`URL`/`fetch`/`TextDecoder`） | 56 | **16%**（剔除 47 行 CSS 模板 + 45 行映射表后 56/259 = **22%**） | **结构等价重写**：输出是 XHTML **字符串**，Kotlin 侧可用 XML 解析 + StringBuilder 等价产出，但必须逐元素复刻结构（§3.3） |
| `src/libs/html.ts` | 128 | 110 | `libs/textProcessor`、`DOMParser`、`Blob` | 22 | **20%** | 结构等价重写：章节切分 + `kookitmarker` 语义必须复刻 |
| （外部）`mammoth@^1.8.0` | — | — | jszip；DOCX → HTML | — | — | JS 实现**不可直接调用**；但存在零依赖的 Java 端口 `org.zwobble.mammoth:mammoth`（BSD-2），开箱在 Android 上不可用 → 需 vendor + 一处补丁（§4.4 事实核查） |
| （外部）`mhtml2html@^3.0.0` | — | — | 浏览器 `DOMParser`（Node 下用 jsdom） | — | — | **不可移植**；但 MIME 解析本身是确定性规范，重写量小（§4.2） |
| （共享基类）`src/renders/GeneralRender.ts` | 1987 | 1906 | — | 高 | — | **不重复付费**：对应 P2 的 `engine/layout` + reader 宿主，四格式复用 |

**口径说明（可复现）**：把上游 5 个文件按 pin 的 raw URL 取回（`web_fetch`）→ 原样落盘到临时目录 → 用 PowerShell 逐行分类（空行 / 注释行 / 代码行），再对**代码行**做正则命中计数：

```
document\.|window\.|DOMParser|new Blob|URL\.createObjectURL|FileReader|HTMLElement|
querySelector|getElementById|createElement|createTextNode|createDocument|innerHTML|
outerHTML|classList|nodeType|firstChild|nextSibling|getAttribute|append\(|TextDecoder|
nodeName|\.children|\.textContent|innerText|childNodes|parentNode|replaceChild|
getElementsByTagName|\.nodeValue|\.result
```

`占比 = 命中行 / 代码行`。**交叉校验**：本统计的代码行数（56 / 51 / 61 / 351 / 110）与仓库既有基线 `docs/android-loc-baseline.json` **逐项完全一致**；`lines` 列沿用该基线（基线把文件末尾换行计入，故比统计值大 1）。临时目录已删除并确认不存在。

**注意这个口径会低估"DOM 绑定"程度**：`renderTo(element)`、`createIframe(element, …)`、`this.getDocument()`、`handleLayout(element, …)` 这类宿主骨架调用不命中上面的正则，但语义上 100% 依赖 DOM。按"语义 DOM 相关行"人工判定，三个 renderer 分别约为 6 / 6 / 12 行（≈11% / 12% / 20%）。**两种口径都指向同一结论**：格式特有逻辑几乎不碰 DOM，DOM 部分是与 EPUB 共享的宿主骨架（§2.3）。

### 2.3 关于"DOM 依赖占比"的正确读法

上表的百分比**不能**直接当作"不可移植代码的比例"，两个原因：

1. **三个 renderer 的 DOM 触达全部落在与 EPUB 共享的宿主骨架上**（`renderTo(element)`、`getDocument()`、`createIframe()`、`handleLayout()`）。这些行在 EPUB/P2 轨道上已经被原生 reader 宿主取代过一次，四格式**不新增成本**。

2. **`fb2.js` / `html.ts` 的 DOM 用法是"构建/读取 HTML 输出"的手段，而不是目标**。`fb2.js` 的产物最终被 `template()` 包成 XHTML **字符串**（`libs/fb2.js` 末尾的 `str = template(el.outerHTML)`），`html.ts` 的产物也是字符串切片。因此 Kotlin 侧的等价实现是"XML 解析 + 字符串输出"，**不需要 DOM**——但**必须复刻元素顺序、`id`、`class` 与嵌套结构**，否则 `elementIndex` 变化会让 CFI 与桌面错位（§3.3，ADR-002）。

---

## 3. 原生侧集成缝（工时估算的基础）

### 3.1 已落地的接缝

| 接缝 | 位置 | 对四格式的意义 |
|---|---|---|
| DOM 等价层 | `android/engine/layout/.../BlockModel.kt:69` `TextBlock`（`elementIndex`/`elementId`/`sourceOffsets`）、`:162` `SpineItem`、`:187` `EpubDocument` | **四格式的唯一产出目标**：产出 `List<TextBlock>` 即接入分页 |
| 排版/分页 | `engine/layout`（`LayoutEngine`、`CfiAddressing`） | 格式无关，零成本复用 |
| 定位/标注 | `engine/cfi`（76 黄金向量）、`engine/annotate` | 格式无关（依赖 spine `elementIndex`） |
| 目录/进度/搜索 | `engine/toc` | 格式无关 |
| 看图/脚注/内链 | `engine/link`（链接分类、脚注抽取、图片尺寸策略） | FB2 的 `notes` body、DOCX 脚注、HTML 锚点**直接落到这个模块** |
| 手势/主题/字体 | `engine/gesture`、`core/designsystem` | 格式无关 |
| 编码探测 | `engine/text/.../CharsetDetector.kt`、`Charsets.kt`、`TextDecoder.kt` | FB2 的 `<?xml encoding?>`、MHTML 各 part 的 charset、HTML `<meta charset>` 全部复用（**不要再写一份**，§7 R3） |
| 导入与格式识别 | `android/core/importer/.../BookRules.kt:38-43`（18 格式含 `fb2`/`docx`/`mhtml`/`xhtml`/`xml`）、`:46-65`（MIME 映射） | **导入层零改动**：这四个格式已经被识别、入库、算 MD5、生成书行 |
| Intent / 兜底岛路由 | `AndroidManifest.xml:51-59`（VIEW/FILE 的 mime 清单已含 docx/fb2/xhtml/mhtml/xml）、`MainActivity.kt:378-383`（mime → format）、`LocalAssetServer.kt:385` | 兜底岛已能收这四类文件；原生切换只需改路由表一行 |

### 3.2 格式分派目前藏在闭源 bundle 里

桌面侧的 `format → Render` 分派**在混淆产物 `kookit.min.js` 内部**（本仓 `src/` 中检索不到 `Fb2Render`/`DocxRender`/`HtmlRender` 的任何引用）。原生侧需要一个显式的分派表（`when (format)`），这是新增的、但很小的一块工作（计入各格式工时）。

### 3.3 CFI 结构对齐是硬约束（影响每个格式的实现方式）

ADR-002 要求桌面 ⇄ 安卓标注位置一致率 ≥ 99%。CFI 是**结构性**地址（spine 步 + 元素步 + 字符偏移），所以：

- FB2 的原生转换器必须复刻 `fb2.js` 的**输出结构**（每个 `<section>` → 一个 spine item；`title/epigraph/poem/table` 的元素映射与嵌套层级逐一对齐；`data-foliate-id` 语义对齐），否则同一本书两端 CFI 不同 → 标注错位。
- HTML/DOCX 的原生转换器必须复刻 `html.ts` 的**章节切分语义**（`h1,title` 标记 + `kookitmarker` 切分 + 无标题时回退到"单文本节点且 `isTitle()`"），否则 TOC/进度/CFI 的 spine 序不同。
- **对策**：参照 `scripts/gen-cfi-golden.js` 的既有做法，为四格式各建一个 **golden-structure 守卫**（上游生成的章节数/元素路径/文本 → 与 Kotlin 侧输出逐项比对），进 CI。这是把"保真度"从口头承诺变成可回归断言的关键一步，已计入各格式工时。

### 3.4 共享前置 D0：`XHTML/HTML → List<TextBlock>` 扁平化器（当前不存在）

`BlockModel.kt:14-16` 明确写着：装饰元素"由 EPUB 解析层（P2，后续卡）扁平化进文本块"。**这个扁平化器目前尚未落地**（`engine/layout` 只有模型 + 分页，`android/engine/` 下没有 epub 模块）：

- 若 P2 的 EPUB 轨道落地了"XHTML → `TextBlock[]`"（含 CFI 的 `elementIndex`/`sourceOffsets` 语义），则四格式**全部复用**，本评估的工时成立。
- 若它只覆盖"良构 XHTML"而不覆盖 tag-soup HTML（现实中的 `.html` 电子书大量不规范），HTML/MHTML 需要额外的容错解析（§4.3），**追加 5–8 人/天**。
- **立项前置条件**：P5.5 开工前必须先确认 D0 的接口与覆盖范围（建议把它作为 P2 卡的显式交付项，而不是隐含假设）。

---

## 4. 逐格式评估

### 4.1 FB2（FictionBook 2 XML）— 建议原生，**先做**

**可行性：高**。纯 XML，无压缩容器，无第三方库，无许可证问题，无 `.so`。

| 工作项 | 说明 | 人/天 |
|---|---|---|
| XML 解析层 | 候选三选一：**`javax.xml.parsers`（SAX/DOM）**——Android 与 JDK **两侧都有**，纯 JVM 模块可编译可单测（**推荐**）；`android.util.Xml`（XmlPullParser，平台内是 repackaged 的 `com.android.org.kxml2.io.KXmlParser`）——Android-only，会破坏"`gradle test` 不需要 SDK"的约定；`org.xmlpull.v1` 在 libcore 内（设备上无需 jar），但 Maven 上的副本极旧（`net.sf.kxml:kxml2` 最新 **2.3.0 / 2009**）→ 若走 XmlPull 路线，JVM 单测要挂一个 2009 年的 jar（推论，待 spike）。**StAX(`javax.xml.stream`) 在 Android 上不存在**，必须排除 | 1.5–2 |
| 元素映射表 | 移植 `fb2.js` 的 5 张映射表（`STYLE/TABLE/POEM/SECTION/BODY`，约 60 行数据）+ `stanza`/`anchor`/`image` 三个特例 | 2–3 |
| `<binary>` 图片与封面 | base64 解码（`<binary content-type>`）+ `coverpage` 抽取；产物走 `core/importer` 的封面约定（`<bookKey>.<ext>`） | 1–1.5 |
| 元数据映射 | `title-info`（title/author/genre/annotation/lang/date）+ `document-info`（id/author/program-used/date）+ `publish-info` → 与桌面同名的 Book 字段 | 1–1.5 |
| 多 body / 脚注 | 第二个 `body` → `notesBodyType`（`linear: "no"`）语义；`type="note"` 锚点 → `engine/link` 脚注 | 1–2 |
| 目录与 id 映射 | 章节 title → TOC；`id` → spine/锚点映射（对应 `book.resolveHref` / `data-foliate-id`） | 1 |
| golden-structure 守卫 + ≥20 本语料回归（含 cp1251/koi8-r 编码、未闭合标签、超长 base64） | 见 §3.3 | 2–3 |
| **小计** | | **8–12** |

**要点/坑**：① 现实 FB2 常带损坏 XML（未转义 `&`、未闭合标签）→ 解析器需要容错与降级，不能一崩了之；② 编码来自 XML 声明（`windows-1251`/`koi8-r` 常见）→ 复用 `engine/text` 的 `CharsetDetector`/`Charsets`，不要只按 UTF-8 读；③ 图片以内嵌 base64 为主（体积大）→ 需要内存预算策略（§7 R2）；④ **范围外**：现实里常见的 `.fb2.zip`（zip 打包的 FB2）不在桌面 18 格式清单内（`BookRules.kt:38-43`），原生也**不做**，保持两端一致。

**第三方库调研结论：没有可用的 FB2 库 → 自研是唯一路线（不是偏好）**。Maven Central 搜 `fictionbook` 零命中；`KursX/fb2parser`（34★、Apache-2.0）**2019-03-27 后停更、从未发布到中央仓、JitPack 构建全失败**（唯一可构建产物是 0★ 的 fork `fb2parser-light:v1.0.2`）；`martinrotter/fb2parser`、`facebook/fbreader` 均不存在（404）。行业先例也是自研：FBReaderJ 用自己的 `ZLXMLParser` 解 FB2，而不是 SAX/XmlPull。这反过来说明 FB2 的解析面确实小（351 行 JS 就是全部）。

### 4.2 MHTML — 建议原生

**可行性：中高**。规范确定、无布局算法，但仓库内**没有任何可移植的实现**。

| 工作项 | 说明 | 人/天 |
|---|---|---|
| MIME `multipart/related` 解析 | 边界解析、part 头（`Content-Type`/`Content-Location`/`Content-Transfer-Encoding`/`Content-ID`）；**JDK/Android 都不提供 `javax.mail`**，而 `jakarta.mail`(Angus) 虽可用却拖 3 个 artifact、对纯 JVM 模块过重 → 手写（约 300–500 行 Kotlin） | 2–3 |
| 传输编码解码 | `base64` / `quoted-printable`（软换行 `=`、`=XX`）/ `7bit`/`8bit`/`binary`；QP 解码器约 60–100 行 | 1–1.5 |
| 引用重写 | `cid:` → `Content-ID` 映射；相对 URL → `Content-Location` 基准解析；产物改写为 `data:` 或本地资源表 | 1–1.5 |
| charset 处理 | 每个 part 独立 charset（复用 `engine/text`） | 0.5 |
| 与桌面行为对齐 + 语料/守卫 | 桌面先把**整个文件按 UTF-8 读成文本**再交给 `mhtml2html`（`HtmlRender.ts` 的 `readAsText(blob,"UTF-8")`）——这是桌面的既有行为，原生必须**同样先整体 UTF-8 读**再按 part 自己的编码解，否则同一文件两端结果不同（bug-for-bug，与 ADR-002/R2 的策略一致） | 1–2 |
| **小计** | | **5–8** |

**第三方库调研结论：没有 MHTML 库 → 手写（零依赖）**。`org.apache.james:apache-mime4j-core:0.8.15`（130 KB）+ `apache-mime4j-dom`（354 KB）只解 part、**不做聚合也不做 `cid:` 解析**，还要拖 `commons-io`；Jakarta Mail/Angus 能用但拖 3 个 artifact；**Tika 没有 MHTML parser**（`.mht` 被误判为邮件、路由到 `RFC822Parser`，TIKA-2723 仍开着）。规模上，WebKit 的 `MHTMLParser.cpp` 只有约 250 行（boundary 切分 + 自带 QP 解码器），因此**估计 600–1,200 行 Kotlin + 测试**——与上表 5–8 人/天互相印证。

**规范面（写清是为了避免实现时漏项）**：`RFC 2557` Content-Location(§4)、base-URI 优先级(§5，末尾回退到 `thismessage:/`)、`cid:`/Content-ID(§8.3)；`RFC 2387` multipart/related；`RFC 2045 §6` base64/quoted-printable；`RFC 2047` encoded-words —— **encoded-word 必须先解码再做 URI 比较**（真实陷阱）；`Content-Base` 在 2557 中已删除（只存在于 RFC 2110）→ **忽略它**。WebKit 的实现在相对 Content-Location 解析上是直接 FIXME 跳过的，原生侧建议**实现**它（更正确，但要注意与桌面的差异：`mhtml2html` 走的是 DOMParser + jsdom 路径，行为需实测对齐）。

### 4.3 HTML（`.html/.htm/.xhtml/.xml`）— 建议原生，但**必须公开保真度让步**

**可行性：中**。技术不难，难在"HTML 不是格式而是编程语言的子集"。

| 工作项 | 说明 | 人/天 |
|---|---|---|
| 容错 HTML 解析 | `javax.xml`（JDK/Android 都有，纯 JVM 友好）**只能解良构 XML**，现实 `.html` 大量是 tag-soup。两条路：(a) 引入 **jsoup**（MIT，约 400 KB，纯 Java、无 StAX/JAXB、Android 广泛验证，可在纯 JVM 模块中使用并照样 `gradle test`）；(b) 自写最小 HTML5 tree builder 子集（隐式闭合 `p/li/td`、实体解码、属性宽松）。**推荐 (a)**，理由：这是本仓 `engine/*` 第一次引入运行时依赖，但它换掉的是"自写解析器 + 无穷边界 bug"，且体积远小于 WebView；若坚持零依赖则选 (b) 并接受更差的容错 | 2–3（jsoup 接线）/ 4–6（自写） |
| 章节切分复刻 | 复刻 `html.ts`：`querySelectorAll("h1,title")` → 插入 `kookitmarker` → 按标记切章 → 无标题时回退 `isTitle()` 文本启发式（`textProcessor`）；标签名与顺序是 TOC/进度/CFI 的一部分（§3.3） | 2–3 |
| CSS 子集 → `ParagraphStyle` | 只吃影响分页的属性：`font-size`（→`fontSizeScale`）、`text-align`、`text-indent`、`margin-top/bottom`、`line-height`；`engine/layout` 的 `LayoutTokens`/`ParagraphStyle` 已经是这个形状 | 2–3 |
| 元数据/标题 | `<title>` → 书名；`<meta name="author">` 等（桌面基本只取 title，保持同语义即可） | 0.5–1 |
| 保真度降级清单 + 用户可见说明 | 多栏、浮动、背景图拼版、`<table>` 复杂版式、RTL、`<pre>` 诗歌的对齐：**明确不承诺**（写进文档与 changelog） | 0.5 |
| golden-structure 守卫 + 语料回归 | 与桌面章节切分结果逐项比对 | 1–2 |
| **小计** | | **8–12** |

**判断依据（"值得原生自绘 vs 长期保留兜底岛"）**：
- **技术前提（必须先接受，否则整个方案不成立）**：**不存在可用的原生 HTML/CSS 渲染引擎**。Compose 侧 `AnnotatedString.fromHtml(...)` 只做行内样式（span 模型，无盒模型）；平台的 `Html.fromHtml` 只认约 25 个标签、3 个 CSS 属性，明确"不支持所有标签"，**没有表格与盒式布局**；第三方选择全是 span/Markdown 取向（`compose-html` 2023 停更、`html-text` 2023、`multiplatform-markdown-renderer`、`compose-richtext` 自述 "very experimental"）。因此所谓"原生自绘 HTML"**只能是块级扁平化近似**（正是 `BlockModel.kt` 的既有取舍），**不是**复刻 CSS。真正的 CSS 排版只有 WebView 能给——这是"留岛"论的唯一硬论据，也是本格式必须公开让步的原因。
- **值得原生**：四格式共用一个转换器（HTML 是 DOCX/FB2/MHTML 的**共同汇入点**），因此 HTML 轨道的成本被三个格式摊薄——它是 P5.5 里唯一"一次投入三次复用"的一块。对**以文字为主**的 HTML 书（网页另存、Project Gutenberg 类），扁平化近似与 WebView 的阅读体验差异很小。
- **让步必须公开**：复杂 CSS 版式的 HTML 书原生渲染一定不如 WebView，这是**已知且不可消除**的差异（除非永远留岛）。因此产品侧要接受"HTML/DOCX 类书籍排版近似"，只承诺 CFI/标注/目录一致（ADR-002 + R3 的既定策略：UI 展示章节 + 百分比，不展示绝对页码）。
- **反方（何时该留岛）**：若实测发现真实用户库中 HTML/MHTML 书籍占比极低且版式复杂，则把它们并入"按需动态特性模块"（ADR-006 备选 D）比强行原生更理性——**建议在 P5.5 开工前用一次真实书库抽样（≥200 本）确认占比**，这是本评估唯一建议"先测量再决定"的点。

### 4.4 DOCX — 建议原生，**排最后**；选型见下

**可行性：中**（四格式里唯一需要做库选型决策的一项）。原因：DOCX 不是"一本书"，而是一个完整的字处理文档格式（OOXML WordprocessingML：分节、样式继承、编号/多级列表、表格、图片与 relationship、脚注尾注、字段、文本框、公式、批注、修订）。

**关键依赖选型（要求项 ②）**

| 备选 | 结论 | 理由（工程事实） |
|---|---|---|
| ① **vendor `mammoth-java`**（`org.zwobble.mammoth:mammoth:1.12.2`，BSD-2-Clause，**零运行时依赖**）+ 修 1 个文件的 Android 兼容问题，落在 `engine/docx` | ✅ **首选** | 见下方"事实核查"。它是**与桌面 `mammoth@^1.8.0` 同一套内容模型**的 Java 实现（同一作者 mwilliamson），因此输出结构最接近桌面 → **CFI 对齐最容易**（§3.3 的硬约束）。代价：需要 vendor 源码 + 一处补丁 + 保留 BSD-2 许可证与出处声明 |
| ② **自研 WordprocessingML 子集**（`engine/docx`，纯 JVM，零依赖，接 `core/archive`） | 备选（回退路线） | 与现有模块约定完全一致（`:engine:text`/`:mobi`/`:pdf`/`:cfi` 都是"零运行时依赖 + JUnit5 test-only"）；无第三方代码负担；可精确控制支持子集。代价：要自己写 OOXML 解析（`document.xml` 的 `w:p/w:r/w:t/w:b/w:i/w:u/w:hyperlink/w:tbl/w:drawing`、`styles.xml` 样式继承、`numbering.xml` 列表编号、`_rels` + `word/media/*`、`footnotes.xml`），**并且要自己把输出结构对齐到 `mammoth` 才能保住 CFI**——路线①买到的正是这部分 |
| ③ Apache POI（`org.apache.poi:poi-ooxml:5.5.1`） | ❌ 否决 | **体积**：poi-ooxml 2.05 MB + poi 3.01 MB + poi-ooxml-lite 6.00 MB + xmlbeans 2.21 MB ≈ **13 MB**（还带 log4j-api、commons-compress/io/collections4、curvesapi）。**Android 改造**（参照 `centic9/poi-on-android`）：因平台无 StAX，必须自带 `com.fasterxml:aalto-xml:1.3.3` + `stax:stax-api:1.0.1`、排除 `META-INF/services/javax.xml.stream.*`、relocate `javax.xml.stream`/`namespace`/`XMLConstants`，并在启动时设 3 个系统属性；**平台无 `java.awt`** → 列宽/图片等图形操作直接崩，参考工程自述不会全部重写；参考工程要求 **minSdk 26（本仓是 24，见 `android/app/build.gradle:27`）**；方法数/Dex 限制也是已知问题。且 **POI 不提供 docx→HTML 导出器**，只给 XWPF 模型，"选 POI"之后仍要自己写 HTML 生成 |
| ④ docx4j（`docx4j-core` + `docx4j-JAXB-*`） | ❌ 否决 | 两条版本线：11.5.14（`javax.xml.bind`）与 17.2.0（**Jakarta**）；`docx4j-core` 3.88 MB + `docx4j-openxml-objects` 2.31 MB，17.2.0 还叠加 `jakarta.xml.bind-api:4.0.5`、`jaxb-runtime:4.0.9`、pdfbox 3.0.8、Xalan、ANTLR、mbassador。JAXB 在 JDK 11+ 已移除、Android 不提供 `javax.xml.bind` → 必须整条 JAXB 运行时搬进来。**其唯一的 Android 工件 `plutext/AndroidDocxToHtml` 自 2017-01-06 起再无推送（9 个未关 issue）** → 实质无人维护 |
| ⑤ `droiddoc`（Kotlin-first、SAX 流式、自带 HTML 导出、API 26+） | ❌ 否决 | 许可与可复现性双不合格：**BSL 1.1**（非 OSI 开源，商用需 $99/年）与 AGPL-3.0 生态不兼容；且只发布 **`0.1.0-SNAPSHOT`**（JitPack），不符合可复现构建。另一个 `e-reznik/DocxJavaMapper` 是 Java 17 + Jakarta JAXB + Lombok，同样不适配。**结论：不存在"维护中 + 许可宽松 + 纯 Kotlin"的 DOCX 库** |
| ⑥ 直接复用 kookit `DocxRender` + JS `mammoth` | ❌ 不可行 | `mammoth`（JS）Kotlin 无法调用。但注意：**"mammoth 无法在 JVM 上用"是错的**——Java 端口存在且零运行时依赖，见 ①；桌面 `DocxRender.ts` 的 51 行里真正的解析全在 mammoth 内部，所以"复用 kookit DocxRender"本身没有可复用的代码，可复用的是 **mammoth 的内容模型** |
| ⑦ 保留兜底岛（不原生） | 见 §6 | 因"岛成本全有或全无"，单独为 DOCX 留岛不划算 |

**事实核查（本次独立验证，2026-09-24）**

1. **端口存在且覆盖够用**：Maven Central `org/zwobble/mammoth/mammoth/` 有 1.12.2（2026-09-12 发布），更早版本可追至 2016 → 长期维护中。**jar 仅 248 KB、Java 8 字节码**，且能力覆盖本项目所需：标题、列表、表格、脚注/尾注、图片、粗/斜/下划线/删除线/上下标、链接、换行、文本框、批注、自定义样式映射、图片转换钩子、`extractRawText`。
2. **许可证友好**：`mammoth-1.12.2.pom` 声明 `The BSD 2-Clause License` → 与 AGPL-3.0 再分发兼容，须保留版权与许可证声明。
3. **零运行时依赖（已核实）**：POM 里所有 `<dependency>` 都是 `<scope>test</scope>`（hamcrest-all、junit-jupiter-api/engine）→ 运行时无传递依赖，符合 `engine/*` 的约定。
   *待 spike 确认*：源码是否使用 `java.time` / Stream 等需要 **core library desugaring** 或 minSdk 抬升的 API（本仓 `minSdk 24`、`compileSdk 34`，且目前**未启用** core library desugaring —— `android/app/build.gradle:80-81` 只配了 `sourceCompatibility JavaVersion.VERSION_17`）。
4. **开箱在 Android 上不可用（这是唯一真障碍）**：其 `SimpleSax` 调 `SAXParserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`；Android 的 `SAXParserFactory.newInstance()` 固定返回 `org.apache.harmony.xml.parsers.SAXParserFactoryImpl`，对该 Xerces 专有 feature 抛 `SAXNotRecognizedException`。上游 issue `mwilliamson/java-mammoth#42`（标题即 `SAXNotRecognizedException`，2024-03-17 开、2025-01-15 仍有活动）**未修**：维护者以 DTD/XXE 加固为由拒绝吞掉异常，也未提供 parser 注入钩子；换 `xerces:xercesImpl:2.12.2` 无效（Android 忽略 ServiceLoader / 系统属性覆盖）。
   → **可行做法**：把 mammoth 源码 vendor 进 `engine/docx`（BSD-2 允许），在 `SimpleSax` 一处做"feature 不支持时降级且保持安全默认"，或把 SAX 解析换成自研解析器（此时 ① 与 ② 合流：**用 mammoth 的内容模型 + 自研的解析前端**）。
5. **上游明确声明"不做净化"（必须转成我方约束）**：mammoth README 写明 *"performs no sanitisation"*，且对病态输入有 CPU 放大风险。→ 对策：解析前加输入大小上限、解析放在后台线程并设超时/可中断、限制单本并发；输出 HTML 走白名单清洗（本仓已有先例：桌面导入网页路径 `src/components/importLocal/component.tsx:811` 用 DOMPurify `USE_PROFILES: { html: true }`）。列为本评估 R9。

**工作量（路线 ① 为主，② 为回退）**

| 工作项 | 说明 | 人/天 |
|---|---|---|
| vendor + Android 适配补丁 | 源码入库、`SimpleSax` 补丁（或换成自有解析器）、许可证/出处声明、`NOTICE` 更新 | 3–4 |
| OOXML 容器接入 | mammoth 自带 zip 读取；需评估是否改接 `core/archive`（关系到 §7 R1：不能再多一份 zip 实现） | 1–2 |
| 输出结构对齐桌面 | JS `mammoth@1.8` 与 Java `mammoth 1.12` 是两套实现，HTML writer 细节可能不同 → 逐项差异清单 + golden-structure 守卫（§3.3） | 3–4 |
| 图片 | `word/media/*` → 资源表 + 尺寸（复用 §7 R2 的图片管线）；mammoth 默认输出 data URI，需评估体积/内存上限 | 2–3 |
| 脚注/链接 | mammoth 支持 footnotes/links → 接 `engine/link` | 1–2 |
| 语料回归（≥20 本：Word 直存 + Calibre 转换两种来源）+ 不支持子集/差异清单文档 | 分节/文本框/公式/批注/修订/分栏/首字下沉明确不承诺 | 2–3 |
| **小计（路线 ①）** | | **12–18** |
| **小计（路线 ② 回退，自研子集）** | | **16–24** |

**关于"能不能更便宜"**：路线 ② 若**降低保真度标准**（只取段落文本 + 粗斜体 + 标题层级 + 图片，放弃列表编号/表格/样式继承）可压到 **8–12 人/天**，代价是很多 DOCX 的层级与列表丢失。建议按语料抽样结果二选一，并把选择写进立项卡。

**DOCX 为什么仍排在最后**：路线 ① 把不确定性从"能不能做出来"降到"vendor 补丁是否够用"，但仍有三个未验证点（补丁深度、两套 mammoth 的输出差异、desugaring/minSdk）。前三个格式的收益独立成立，**DOCX 失败不阻塞其余三者**，因此把它排在最后是为了让"收益确定的部分先兑现"。

**关于"导入期一次性转换"**（ADR-006 备选 F）：**不省钱**。转换代码量不变，只是把调用点从读取期挪到导入期；若产出的 HTML 与桌面 `mammoth` 不一致，反而破坏 CFI 对齐。仅当明确接受"DOCX 标注不与桌面互通"时才作为降级项。

---

## 5. 工时与收益汇总（要求项 ①）

### 5.1 工时

| 格式 | 人/天 | 折合 | 依赖 | 备注 |
|---|---|---|---|---|
| FB2 | 8–12 | 1.5–2.5 周 | `core/archive`（不需要）/ `engine/text`（编码）/ D0 | 最确定、最便宜，先做 |
| MHTML | 5–8 | 1–1.5 周 | `engine/htmlbook`、`engine/text` | 投入产出比最高 |
| HTML 家族 | 8–12 | 1.5–2.5 周 | D0、jsoup（或自写解析器）、`engine/layout` | DOCX/FB2/MHTML 的共同汇入点，成本被摊薄 |
| DOCX | 12–18（回退 16–24） | 2.5–3.5 周 | `core/archive`、D0、`engine/htmlbook`、mammoth-java vendor | 唯一需库选型；路线① = vendor mammoth-java + Android 补丁 |
| **合计** | **34–51** | **≈7–10 周单人** | | 不含 D0；DOCX 走回退路线则 37–56 |
| （条件项）D0 容错解析缺口 | +5–8 | +1–1.5 周 | 仅当 P2 的扁平化器不覆盖 tag-soup HTML | §3.4 |
| （条件项）`core/archive` | +3–5 | | 仅当 P5 CBZ 的 `engine/image` 未提供可复用的 zip 门面 | §7 R1 |

**与既有排期的关系**：§6 的 P5 是"6–8 周 = TXT/MD + CBZ + 四格式评估"；本评估给出的 P5.5 是 **34–51 人/天（7–10 周单人）**，即"四格式原生"本身就是一个完整阶段，**不应塞进 P5**（这也正是 §3.1 当初把它们单独立项的原因——单独立项是对的，只是结论应为"原生立项"而不是"长期驻岛"）。

### 5.2 收益：为什么按"消解固定成本"计价

本仓**无遥测、无云同步**（`android-native-migration.md` §1.1 / 附录 A.1），因此无法用真实使用率给四格式加权。收益因此按两条可验证的账目计：

1. **消解 R6/R7 的固定成本（主要收益）**：只要还有一个格式走兜底岛，就必须保留 WebView 宿主 + `assets/webapp` + 引擎 bundle + 41 locale + `nativeBridge.js`/`folderBridge.js` 契约守卫 + 双轨 CI（ADR-003 配套纪律 1/2）。四格式全原生后这些可以整体退役（P8），APK 体积相对 **20.03 MB** debug 基线（`docs/android-baseline.json`）的下降可量化。
2. **P6 功能一次性覆盖（次要但确定）**：TTS、字典、划词翻译、段落模式/速读/阅读尺、文本替换、统计、OCR 全部挂在原生 reader 管线（`rendition` 等价层）上。走兜底岛的格式要么拿不到这些功能，要么需要在 WebView 里再做一套。**原生化的四格式免费获得 P6 全部能力。**

3. **不可量化项（据实说明）**：单本打开速度/内存/手势体验的改善无法给出数字（无设备、无基线，`device.*` 均为 `pending-device`）。P2/P3 的同类指标（翻页 P90 < 50 ms、内存峰值 < 350 MB）可作为四格式的**目标值**，但需要真机回填。

---

## 6. 结论 ADR（要求项 ④）

完整 ADR 见 [`docs/adr/ADR-006-fb2-docx-html-mhtml-native.md`](adr/ADR-006-fb2-docx-html-mhtml-native.md)。摘要：

> **决策：四格式全部原生立项（P5.5），按 `FB2 → MHTML → HTML → DOCX` 顺序推进；不采用"兜底岛长期保留"。**
>
> 每完成一个格式即按 ADR-003 ② 从路由表下线该格式；兜底岛维持 ADR-003 ③ 冻结纪律，目标在 P8 退役。
>
> **备选与否决理由**：
> - A 维持现状（长期驻岛）→ 否决：岛成本全有或全无；且冻结纪律意味着这四类书的非崩溃缺陷按约定不修。
> - B 只原生便宜的（FB2/MHTML），DOCX 留岛 → 否决：岛仍完整保留，只省 12–18 人/天，收益为负。
> - C 全原生 → **采纳**。
> - D 岛降级为**按需动态特性模块**（Play Feature Delivery，仅 DOCX 用）→ **保留为兜底计划**：仅当 DOCX 排期滑出 P8 时启用。
> - E 用第三方阅读 SDK → 否决：破坏 ADR-002（CFI/schema 不兼容），且无覆盖 FB2+MHTML 的成熟 SDK。
> - F DOCX 导入期一次性转换 → 否决为"省钱手段"：工作量不变且可能破坏 CFI 对齐。

---

## 7. 风险（要求项 ⑤）

### R1（高）zip 层重复：DOCX 会成为第 5 份 ZIP 实现

**事实（已可观测）**：本仓已有 4 处各自直接使用 `java.util.zip`：

| 位置 | 用途 |
|---|---|
| `android/core/importer/.../EpubBook.kt:56` | EPUB 容器 |
| `android/core/importer/.../ComicCover.kt:33` | CBZ 封面抽取 |
| `android/core/dbio/.../BackupBundle.kt:134` | 桌面备份 zip |
| `.worktrees/p5-image/android/engine/image/.../ZipExtractor.kt:46`（**并行 P5 CBZ 卡，进行中**） | CBZ 页面读取 |

DOCX 是 OOXML（zip）→ 若直接写第 5 份，重复与漂移风险确定发生。

**并行卡的现状（已读其 worktree）**：`engine/image` 的 `ArchiveExtractor` / `ZipExtractor` 是**漫画页语义**的抽象——`entries: List<PageEntry>` 已按 natural 顺序排好并且**只保留图片条目**（`ZipExtractor.listPages` 用 `ImageEntries.isImage` 过滤）。**因此它不能被 DOCX 直接复用**（DOCX 需要按名字随机访问 `word/document.xml`、`[Content_Types].xml`、`_rels/.rels`…）。但它的 `ArchiveKind.sniff()`（魔数探测）是通用的、可直接复用。

**对策（建议写进 P5.5 立项卡）**：
1. 先立 **`core/archive`** 作为唯一 zip 门面（`entries()` 全量随机访问 + `open(name)` + 大小/CRC），基于 `java.util.zip`（或与 CBZ 一致的 `commons-compress`，见下）。
2. `engine/image` 与 `engine/docx`（以及将来的 EPUB 解析层）**都消费 `core/archive`**；`EpubBook.kt`/`ComicCover.kt`/`BackupBundle.kt` 的迁移可推迟到各自卡，但**禁止新增第 5 份实现**。
3. 注意 `engine/image/build.gradle` 已写明 CB7(7z) 将走 **Apache Commons Compress 的 `SevenZFile`**——这意味着 `engine/*` 的"零运行时依赖"约定**已经开始松动**。若 CB7 落地引入 `commons-compress`，则 `core/archive` 应直接建在它之上（zip + 7z 一套），否则会出现"CBZ 用 commons-compress、DOCX 用 java.util.zip"的第二类不一致。
4. **并行纪律**：`android/settings.gradle` 是共享热点文件（本仓库已有多轮并行 include 追加）。四格式若要建模块，include 行必须**串行合并**（本评估已提供 patch 模板 `docs/patches/p5-fb2-settings-gradle.patch`，未应用）。

### R2（中高）图片管线重复：三格式 vs CBZ

FB2（`<binary>` base64）、DOCX（`word/media/*` + `r:embed`）、MHTML（内嵌图片 part）都需要：字节 → 资源表 → 尺寸探测 → 内存预算 → 解码。并行 CBZ 卡已经建了其中大半：`ImageHeader.read(bytes)`（纯 JVM 固有尺寸探测）、`PageLoader`/`LoadWindow`（**当前页 + 后 3 页，卸载 ≤ current-4** 的懒加载窗口）、`PageDecoder`（解码钩子，宿主可换 BitmapFactory 版）。

**对策**：图片资产走**同一个懒加载窗口抽象**（四格式共用），不要在 `engine/fb2`/`engine/docx` 里各自实现"读图 + 缓存 + 释放"；`engine/link` 的图片尺寸策略应成为唯一尺寸来源。

### R3（中）编码探测重复

`engine/text` 已交付 `CharsetDetector` / `Charsets` / `TextDecoder`（P5 TXT/MD 卡）。FB2 的 XML 声明、MHTML 各 part 的 charset、HTML 的 `<meta charset>` 都必须**复用**它。**禁止**在 `engine/fb2`/`engine/htmlbook` 里新写启发式。

### R4（中）CFI 结构漂移

原生转换器若"顺手写得更合理"（改元素顺序、合并段落、换标题标签名），会静默破坏 ADR-002 的 ≥99% 指标，而且**在两端对照测试之前不可见**。对策：§3.3 的 golden-structure 守卫（进 CI）+ 每格式 ≥20 本两端对照。

### R5（中）DOCX 保真度无底洞

字处理文档的排版空间远大于电子书（分节、文本框、公式、批注、修订、分栏、首字下沉）。对策：**显式支持子集清单 + 显式降级**（"文本可读、结构可导航"），不承诺像素级一致；并把 DOCX 排在最后（前三个格式的收益已经独立成立，DOCX 失败不阻塞其余）。

### R6（中）HTML 保真度的产品风险

复杂 CSS 的 HTML 书原生渲染必然不如 WebView。对策：① UI/文案只承诺 CFI/标注/目录一致（沿用 R3 的"章节 + 百分比"策略）；② 开工前用真实书库抽样（≥200 本）确认 HTML/MHTML 占比，据此决定是否把这两个格式转入"按需动态特性模块"（ADR-006 备选 D）。

### R7（低）`.mhtml` 的系统级歧义

`BookRules.kt:64` 与 `AndroidManifest.xml:59` 都把 `.mhtml` 登记为 `message/rfc822`（对齐桌面），部分文件管理器会把它交给邮件客户端。不影响引擎，记录备查。

### R8（低）工作量估算本身的置信度

本评估无设备、无运行态、无遥测，且上游源码为远端逐文件读取。**FB2/MHTML 的估算置信度较高**（格式确定、无库选型分歧）；**DOCX 的估算区间最宽（12–18；回退自研子集 16–24，降保真 8–12）**，且依赖一个尚未验证深度的 vendor 补丁（§4.4 事实核查第 4 条），建议立项后用第一周做"20 本语料 spike"校准。

### R9（中）解析器输入安全与 CPU 放大

四格式都是"外来文件直接进解析器"，且 DOCX 选型（mammoth）**官方声明不做净化**（§4.4 事实核查第 5 条），MHTML/FB2 同理（内嵌 base64、外部引用、实体展开）。对策（建议写进各格式的实现卡，不要留给运行期发现）：
1. **输入上限**：文件大小上限；OOXML/MHTML 解包后的**条目数与单条目/总解压体积上限**（zip bomb）；FB2 `<binary>` 累计 base64 上限。
2. **可中断**：解析在后台线程 + 超时；提供"取消"路径，UI 不能因为一本坏书卡死。
3. **不做实体展开**：SAX/DOM 侧关闭外部实体与 DTD（这也是 mammoth 那个 `disallow-doctype-decl` feature 的初衷——**打补丁时不能把它简单地删掉**，只能是"平台不支持该 feature 时用等效的禁 DTD 配置"）。
4. **输出清洗**：生成的 HTML 走白名单（桌面先例：`src/components/importLocal/component.tsx:811` DOMPurify `USE_PROFILES: { html: true }`），避免把书里的脚本/远程资源带进渲染层。

---

## 8. 立项建议（不直接改主仓）

**模块划分**（4 个 include 行，patch 模板已在 `docs/patches/`）：

| 模块 | 承载 | 理由 |
|---|---|---|
| `core:archive` | zip/归档门面（唯一） | 消解 R1 |
| `engine:fb2` | FB2 → XHTML 结构 + 元数据 | 与 `engine/mobi` 对称（一个格式一个解析器） |
| `engine:htmlbook` | HTML + MHTML → 章节/块（MHTML 只是"输入解码器"） | 同一管线的两个入口，避免碎片化；DOCX 的产物也汇入这里 |
| `engine:docx` | WordprocessingML 子集 → HTML/块 | 独立成模块，因其依赖/风险面与其余三者不同 |

**阶段拆分（每阶段独立可验收、可单独回滚）**

| 阶段 | 内容 | 交付 | 预估 |
|---|---|---|---|
| P5.5a | `engine:fb2` + golden-structure 守卫 + 语料回归 | FB2 从路由表下线 | 8–12 人/天 |
| P5.5b | `engine:htmlbook`（MHTML 解包）+ 复用 D0 | MHTML 下线 | 5–8（+D0） |
| P5.5c | `engine:htmlbook`（HTML 容错解析 + 章节切分 + CSS 子集） | HTML/XHTML/XML 下线 | 8–12 |
| P5.5d | `engine:docx`（vendor mammoth-java + Android 补丁）+ 语料 spike 校准 | DOCX 下线 | 12–18（回退 16–24） |
| （前置） | 确认 D0（P2 扁平化器）接口与覆盖范围；`core:archive` 落地 | 前置卡 | 3–8 |

**验收指标**（沿用既有口径）：每格式 ≥20 本语料"可读 + 目录正确 + 图片显示"；桌面 ⇄ 安卓 CFI 双向一致率 ≥ 99%（ADR-002）；下线证据 = 路由表变更 + WebView 不再加载该格式。

---

## 附录：证据索引

| 结论 | 证据位置 |
|---|---|
| 四格式共用 `HtmlReader` | `src/router/index.tsx:158-165` |
| DOCX/MHTML 依赖外部 npm 包 | `package.json`（`mammoth@^1.8.0`、`mhtml2html@^3.0.0`）；`kookit.min.js` import 头 |
| 三个 renderer 的代码量与结构 | 上游 @ pin：`src/renders/{Fb2,Docx,Html}Render.ts`（全文读取，见 §2.2 行数） |
| FB2 转换器实现细节（映射表/`<binary>`/notes body/`data-foliate-id`） | 上游 @ pin：`src/libs/fb2.js`（全文读取） |
| 章节切分与 `kookitmarker` 语义 | 上游 @ pin：`src/libs/html.ts`（全文读取） |
| DOM 等价层与 CFI 结构约束 | `android/engine/layout/.../BlockModel.kt:14-22, 69, 162, 187` |
| 导入层已支持四格式 | `android/core/importer/.../BookRules.kt:38-43, 46-65` |
| Intent/MIME 分派已含四格式 | `android/app/src/main/AndroidManifest.xml:51-59`；`MainActivity.kt:378-383`；`LocalAssetServer.kt:385` |
| 编码探测已落地（勿重复实现） | `android/engine/text/src/main/kotlin/.../{CharsetDetector,Charsets,TextDecoder}.kt` |
| APK 体积基线 / 无设备 | `docs/android-baseline.json`（`apk.valueMb=20.03`、`device.*=pending-device`） |
| 兜底岛四态与冻结纪律 | `docs/adr/ADR-003-fallback-island.md:13-26` |
| 并行 CBZ 卡已建 zip/图片基建 | `.worktrees/p5-image/android/engine/image/.../{ArchiveExtractor,ZipExtractor,PageLoader,ImageHeader}.kt`（只读走查） |
| 四格式工时归属与 §3.1 原状 | `docs/android-native-migration.md:82-90, 103, 226` |
| mammoth-java 存在且零运行时依赖（BSD-2） | Maven Central `org/zwobble/mammoth/mammoth/`（最新 1.12.2）+ `mammoth-1.12.2.pom`（依赖全为 `test` scope、license=BSD 2-Clause） |
| mammoth-java 在 Android 上开箱不可用 | 上游 issue `mwilliamson/java-mammoth#42`（标题 `SAXNotRecognizedException`，未修） |
| DOM 命中行统计（可复现方法） | 本评估 §2.2 口径说明（pin 的 raw 源 → 临时目录 → PowerShell 逐行分类 + 正则命中；代码行数与 `docs/android-loc-baseline.json` 逐项一致） |
| Android 平台 XML API 可用性 | `javax.xml.parsers` 在平台可用（由 mammoth issue #42 的失败点反证）；StAX 不可用（[JBTM-1227 "StAX is not available on Android"](https://issues.redhat.com/browse/JBTM-1227)；API-34 的 `javax/xml/` 无 `stream` 包）；平台另内置 XmlPullParser（[CommonsWare Android 书 §8.4](https://commonsware.com/Android/Android-8.4-CC.pdf)、[AOSP `android/util/Xml.java`](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/util/Xml.java)） |
| FB2 无可用第三方库 | Maven Central `fictionbook` 零命中；[`KursX/fb2parser`](https://github.com/KursX/fb2parser) 2019-03 停更且未发布；[FBReaderJ](https://github.com/geometer/FBReaderJ) 自研 `ZLXMLParser` |
| MHTML 无可用库 / 规范面 / 规模 | mime4j-core 0.8.15 不含聚合与 `cid:` 解析；[TIKA-2723](https://issues.apache.org/jira/browse/TIKA-2723)（Tika 无 MHTML parser）；[RFC 2557](https://www.rfc-editor.org/rfc/rfc2557) / [RFC 2387](https://www.rfc-editor.org/rfc/rfc2387) / [RFC 2045 §6](https://www.rfc-editor.org/rfc/rfc2045#section-6) / [RFC 2047](https://www.rfc-editor.org/rfc/rfc2047)；规模参照 [WebKit `MHTMLParser.cpp`](https://raw.githubusercontent.com/WebKit/WebKit/main/Source/WebCore/loader/archive/mhtml/MHTMLParser.cpp)（≈250 行） |
| 无原生 HTML/CSS 渲染引擎 | Compose `AnnotatedString.fromHtml` 仅行内；[AOSP `Html.java`](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/text/Html.java) 约 25 标签 / 3 CSS 属性、无盒模型；第三方均为 span/Markdown 或自述 experimental |
| 否决 POI / docx4j / droiddoc 的依据 | POI 5.5.1 jar 合计 ≈13 MB + [poi-on-android](https://github.com/centic9/poi-on-android) 的 StAX/aalto-xml/relocate/minSdk 26 要求；docx4j 17.2.0 POM 依赖清单 + [AndroidDocxToHtml](https://github.com/plutext/AndroidDocxToHtml) 2017 停更；droiddoc 为 BSL 1.1 + `0.1.0-SNAPSHOT` |
| 本仓 minSdk / 无 desugaring | `android/app/build.gradle:23-28`（compileSdk 34 / minSdk 24）、`:80-81`（仅 `sourceCompatibility 17`，未启用 core library desugaring） |
