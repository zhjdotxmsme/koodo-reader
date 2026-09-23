# Android PDF 渲染 POC 结论（Phase 0 checklist #5）

> 目标：为原生 Kotlin 版 Koodo Reader 选定 PDF 渲染方案，结论需覆盖渲染质量、文本层（划词/搜索）、
> 包体积、以及 Android 15+ 16KB 页大小（Google Play 强制）约束。
>
> 性质：**静态 POC**——基于仓库内代码事实 + 先例应用的调研 + 依赖可用性核查；
> 真机渲染耗时/内存数字并入 Phase 0 checklist #4（性能基线），设备就绪后回填本页 §4。

## 1. 事实约束（从代码推出）

1. **桌面端 PDF = pdf.js**：渲染入口 `public/lib/pdfjs/pdf.mjs` + `pdf.worker.mjs`（`public/index.html`
   设置 workerSrc），文本层 `text_layer_builder.css`；**foliate-js 还 vendored 了一份 PDF.js（Apache-2.0）**。
   桌面 Koodo 的 PDF 阅读路径与 kookit/foliate 引擎同源。
2. **ADR-001 双轨架构**：kookit/foliate 是浏览器引擎（依赖 DOM/Range/CSS columns），PDF 若走原生库
   会与 epub/cbzs 的文本层、划词、CFI 定位（ADR-002）产生**两套坐标系**——这是最大的架构风险。
3. **AGPL-3.0 传染**：本仓库与 kookit 均 AGPL，任何新增依赖许可需兼容；Apache-2.0 / MIT 均可。

## 2. 候选方案对比

| 方案 | 许可 | 文本层/划词 | CJK 字体 | 16KB 页(Play 强制) | 包体积 | 与桌面一致性 | 维护现状（2025-26） |
|---|---|---|---|---|---|---|---|
| **pdf.js**（WebView/引擎内） | Apache-2.0 ✅ | ✅ 原生文本层，与 epub 同一套 Range/CFI | ✅ 走系统字体 + web font | ✅ 纯 JS，无 .so | +~1MB（已随 assets 打包） | ✅ 桌面同源，像素级一致 | Mozilla 持续发版 |
| **PdfRenderer**（framework） | Android SDK ✅ | ✗ 只出 Bitmap，文本需另配 PdfRenderer 的 text API（API 29+ 部分能力）或 OCR | ✅ | ✅ 无自有 .so | 0 | ✗ 桌面无对应 | 平台内置，随系统 |
| **PdfBox-Android** | Apache-2.0 | ✅ 有，但 API 弱 | ⚠️ 需自带字体 | ✅ 纯 Java | +~4MB | ✗ | ⚠️ 渲染慢（上游 SO issue #588、StackOverflow 慢渲染问题），更新停滞 |
| **PdfiumAndroid**（.so 绑定） | Apache-2.0/.so BSD | ✅ 绑定较完整 | ✅ | ❌ **已知爆雷**：`libpdfiumandroid.so` 未对齐 16KB，在 Android 15 崩溃（react-native-pdf #947、barteksc/PdfiumAndroid #95，仅第三方 fork binhtran2001 修了） | +~5-8MB | ✗ | ❌ 上游 barteksc 长期不维护 |

先例：**GrapheneOS/PdfViewer**（pdf.js + sandboxed WebView + CSP）、Readest 等阅读器均在 WebView 内用
pdf.js；原生侧 react-native-pdf 因 pdfium 的 16KB 问题在 Play 上反复爆雷，可作反面教材。

## 3. 结论

**选定：pdf.js 作为 P2 PDF 渲染路径（在引擎 WebView 内运行），PdfRenderer 仅作导出/打印快照的可选后端；不引入 PdfBox-Android 与 PdfiumAndroid。**

理由：
1. **架构一致性（决定性）**：pdf.js 的文本层产出 DOM Range，与 epub/cbzs 共用同一套划词、
   ADR-002 的 Range→CFI 定位、搜索高亮——原生库（PdfRenderer/PdfBox/pdfium）输出 Bitmap，
   必须另起文本坐标系与 OCR/提取管线，等于双倍工作 + 双倍缺陷面。
2. **许可与体积**：Apache-2.0，且 pdf.js 已在 `public/lib` 与 foliate-js vendored 里存在，
   不新增任何二进制依赖；APK 不引入新 .so，天然规避 16KB 页大小风险。
3. **桌面同源**：与桌面版逐像素一致（同一 worker、同一渲染器），回归测试可复用桌面用例；
   Phase 0 checklist #4 的性能基线可直接与 WebView 版对比。
4. **排除项**：PdfiumAndroid 16KB 问题未解且上游不维护；PdfBox-Android 渲染性能差、
   纯 Java 大对象易 OOM，无一处胜过 pdf.js；PdfRenderer 无文本层，不满足划词/搜索核心需求，
   但其零依赖渲染 Bitmap 的特性适合"导出当前页为图片/打印"这类旁路场景，保留为可选后端。

**风险与对策**：pdf.js 大文件 range 读取在移动端有 OOM 先例（Readest #4670 已 throttle 修复）——
P2 接入时按其方案对 range 请求限流；详见 §4 待验证项。

## 4. 待真机验证项（回填 #4 性能基线）

- [ ] 500页 20MB PDF：首屏耗时、翻页 P95、常驻内存（对比 WebView 版基线）
- [ ] 大 PDF range 读取是否触发 OOM（验证 §3 风险对策）
- [ ] CJK 扫描版 PDF 的文本层命中率（ToUnicode 映射）
- [ ] 打印/导出路径用 PdfRenderer 快照的清晰度（如该 feature 进入排期）
