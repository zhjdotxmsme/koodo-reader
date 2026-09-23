# ADR-001 架构选型：WebView 壳 → Kotlin/Compose 原生 + 兜底岛双轨

- 日期：2026-09-23
- 状态：已采纳（Phase 0）
- 关联：`docs/android-native-migration.md` §1–§3、§7；风险 R1/R6/R7/R8

## 背景

现有 Android 版是「WebView 壳 + 回环静态服务 + 桌面同款 React 应用」。代码可直接推出的结构性根因（§1.2）：

1. **启动**：React 首屏 + 41 个语言 JSON + 引擎 bundle + 8 个 WASM/JS 库（pdf.js、sql.js、7z、unrar、tesseract、onnxruntime、esearch-ocr、fabric）一次性加载。
2. **交互**：翻页/划词/菜单走 `evaluateJavascript` 回注 + `postMessage`，链路长且频繁跨 JS 边界。
3. **内存**：pdf.js / sql.js / tesseract / onnxruntime 全部驻留 WebView。
4. **手势/滚动**：非原生，惯性、边缘回弹、输入法与无障碍受限。
5. **能力缺失**：`better-sqlite3`、云同步插件、原生 OCR 在 APK 中不可用。

同时已核实（§2）：渲染引擎 kookit 是 TypeScript 浏览器引擎（依赖 `document`/`iframe`/`Range`/CSS columns），**Kotlin 无法直接调用**；官方移动端（koodo-reader-expo）仍是同一套 WebView 引擎，生态内无 Kotlin 原生先例。LOC 基线（`docs/android-loc-baseline.json`）：kookit code=32806（57 文件）、foliate-js 自有 code≈6.7k + vendored pdf.js 87956——移植工作量可量化、可控。

## 决策

采用**双轨架构**：

1. **原生轨**：Kotlin/Compose 重写客户端；以 kookit / foliate-js 为**规范与参考实现**，逐模块移植为 `engine/*`（cfi 已落地，epub → pdf → mobi → text/image 按 §3.1 路由表推进）；UI 为 `feature/*`。
2. **兜底轨**：现有 WebView 资产（`LocalAssetServer.kt`、`nativeBridge.js`、`folderBridge.js`、`NativeEventDispatcher.kt`、`assets/webapp`）**整体保留为兜底岛** `feature/webisland`，冻结维护，按格式逐个下线（详见 ADR-003）。
3. 构建系统双目标 `--target webview|native` 已落地（commit `97503d1f`），迁移期间两个变体都可发布。

## 备选方案与理由

| 备选 | 否决理由 |
|---|---|
| 继续优化 WebView 版 | WASM 内存/启动开销与 JS 桥延迟是**结构性**的，优化只能缓解不能消除 |
| 改用 React Native/Expo 与官方对齐 | 官方 Expo 版仍是 WebView 引擎，换壳不换引擎，根因照旧 |
| Flutter 重写 | 无现成 EPUB 引擎可借力，移植量相同且团队栈是 Kotlin |
| 完全自研、不参考 kookit | 失去 CFI/标注结构/SQL schema 兼容，桌面↔安卓数据无法互通（见 ADR-002） |

## 影响

**正面**：体验指标可达 §8 目标（冷启动 P90 ≤1.5s、翻页 P90 <50ms、原生 fling）；现有资产零作废，迁移期始终有可发布版本；AGPL 复用无法律障碍。
**负面**：双轨维护成本（R6，对策：兜底岛冻结 + 按格式限期下线）；双引擎并存期 APK 体积（R7，对策：ABI 拆分/动态特性）；衍生 APK 须继续 AGPL-3.0 开源（R8）。

## 验证方式

- P0 基线（LOC、schema.lock、性能基线）可复现；P1 起用 Macrobenchmark 固化 §8 指标。
- 每个阶段验收以「该格式脱离兜底岛」为准。

## 回滚方案

`webview` target 永久保留在构建系统中；任一格式可随时切回兜底岛渲染；全部 Kotlin 代码位于 `android/engine|feature`，移除不影响 WebView 变体。
