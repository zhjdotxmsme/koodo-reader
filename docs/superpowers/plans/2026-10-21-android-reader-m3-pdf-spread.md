# Android Reader M3 — PDF 裁边/重排 + EPUB 双页 spread

**Goal：** 在原生轨（Foliate WebView + pdf.js 栅格引擎）上补齐规格 M3：重排书 **双页对开**、PDF **均匀白边裁剪**、文字 PDF **重排阅读**；扫描版仍走栅格 + OCR，不做假重排。

**Status：** Implemented 2026-10-21.

**边界（与总规格一致）：**

- 同步仍仅本地；**双页 = 单书 spread**（`ReaderFeatureState.doublePage`），非系统分屏。
- PDF 标注/`selectedRect` 仍受栅格无 text layer 限制；重排模式为 **阅读视图**，不替代 P6 标注管线。
- 漫画裁边可复用同一 `cropMarginPx` 参数；独立 `engine/image` 裁边未在本里程碑展开。

---

## 1 · 需求映射

| 规格项 | 实现策略 | 状态 |
|--------|----------|------|
| EPUB/MOBI spread 双页 | `doublePage` → Foliate `flow=paginated` + `max-column-count=2`（与 `columnsPerPage` 分离） | ✅ |
| PDF 白边裁剪 | `pdfCropMarginPx` → `renderPage(n, width, cropPx)` 输出像素均匀 inset | ✅ |
| PDF 重排 | `reflowHtml` + 可见 `WebView`；菜单/设置切换 `pdfReflow` | ✅ MVP |
| 扫描 PDF | 重排无文本时引擎返回占位段落；用户切回栅格 + OCR | ✅ 文档化 |

---

## 2 · 设计要点

### 2.1 EPUB spread（`ReaderSpreadPolicy`）

- **问题：** `columnsPerPage=2` 是「单屏两栏」，与 Librera/Koodo 式 **对开 spread**（paginator `max-column-count-spread`）不是同一语义。
- **策略：**
  - `doublePage == true` → `foliateScrolled = false`，`foliateMaxColumnCount = 2`（**Lite 档也强制 2 列 spread**）。
  - `doublePage == false` → 仍走 M2 的 `ReaderPerformancePolicy.effectiveMaxColumns` + `features.scrolled`。
- **推送：** `ReadestViewSettings.from` → `FoliateHostBridge.setViewSettings`；`NativeEpubScreen` 的 `remember`/`LaunchedEffect` 依赖含 `features.doublePage`。

### 2.2 PDF 裁边

- **参数：** `ReaderFeatureState.pdfCropMarginPx`，clamp `0..48`，持久化 `ReaderFeaturePrefs`。
- **语义：** 在 **已缩放栅格** 上四边各去掉 `cropPx` 像素（非 PDF user space pt）；适合去掉扫描页固定白边，非智能内容检测。
- **双页：** `PdfReaderController.spread` 时对左/右页均传同一 `cropMarginPx`。

### 2.3 PDF 重排

- **引擎：** `pdfengine/engine.mjs` — `getTextContent()` 按 baseline Y 粗分行 → HTML 段落；字号随 `targetWidthPx` × `fontScale`（阅读设置 **Font size**）。
- **宿主：** 隐藏 1×1 引擎 WebView 不变；另建 **只读展示 WebView** `loadDataWithBaseURL`；翻页仍走 `PdfReaderController` 页码与手势。
- **未做（刻意 defer）：** 跨 item 搜索高亮、选区标注、pdf.js 完整 reflow viewer CSS；纯扫描自动提示可后续用「无文本」检测 + Toast。

---

## 3 · 交付清单

| 项 | 位置 |
|----|------|
| Spread 策略 | `android/.../ReaderSpreadPolicy.kt` |
| Foliate 快照 | `ReadestViewSettings.kt`（`scrolled` / `maxColumnCount`） |
| EPUB 重推设置 | `NativeEpubScreen.kt`（`doublePage` in `remember` keys） |
| PDF prefs | `ReaderFeatureState` — `pdfCropMarginPx`, `pdfReflow` + `ReaderFeatureCodec` |
| PDF 桥接 | `PdfHostBridge.renderPage(..., cropMarginPx)`, `reflowHtml(...)` |
| PDF JS | `assets/pdfengine/engine.mjs` — crop + reflow + `METHODS` |
| PDF 宿主 | `PdfJsHostBridge.kt`, `PdfReaderController.kt`, `NativePdfScreen.kt` |
| 设置 UI | `ReadingSettingsRoute.kt` — **PDF display** 区块 |
| 菜单 | `NativePdfScreen` — 栅格 ↔ 重排 |
| i18n | `src/assets/locales/en.json` + `scripts/sync-locales-android.js` |
| 规格状态 | `docs/superpowers/specs/2026-10-21-android-full-reader-design.md` → M3 已落地 |

---

## 4 · 任务分解（实现顺序）

1. **Spread 策略 + Foliate 接线** — `ReaderSpreadPolicy`、单测、`ReadestViewSettingsTest` 双页用例。
2. **PDF crop 协议** — 扩展 `PdfHostBridge` / `engine.mjs` / `PdfReaderController`；设置滑块。
3. **PDF reflow** — `reflowHtml`、Controller 分支、`NativePdfScreen` 展示 WebView + 菜单切换。
4. **i18n + locale sync** — en key + Android assets。
5. **Node 测试** — `scripts/test-pdfengine.js` crop/reflow 段。

---

## 5 · 验收

| 场景 | 预期 |
|------|------|
| EPUB 横屏/宽屏 + **Double-page view** | Foliate 分页，一次可见左右栏 spread；Lite 档仍为双列 spread |
| PDF **PDF margin crop** > 0 | 栅格页四边空白减少（同 zoom 下 bitmap 尺寸变小） |
| 文字 PDF **PDF reflow view** | 可滚动 HTML，字号随 Font size；翻页/手势仍有效 |
| 扫描 PDF 重排 | 提示无文本或空白占位；切 **PDF raster view** 正常栅格 |
| 回归 | M2 手势、perfTier、M1 规则/CSS 不受影响 |

**真机 smoke 清单（逐步勾选 + 截图路径）：**  
`docs/superpowers/plans/2026-10-21-m3-device-smoke-checklist.md`  
一键安装/推送/fixture：`scripts/android-m3-smoke.ps1`

---

## 6 · 验证命令

```powershell
# JS 引擎（含 M3 crop/reflow）
node scripts/test-foliate-host.js
node scripts/test-pdfengine.js

# Android JVM（Gradle 锁冲突时可 --no-build-cache 或换 GRADLE_USER_HOME）
cd android
.\gradlew.bat --no-build-cache :app:testDebugUnitTest `
  --tests com.koodoreader.reader.shell.ReaderSpreadPolicyTest `
  --tests com.koodoreader.reader.epubhost.ReadestViewSettingsTest `
  --tests com.koodoreader.reader.shell.ReaderFeatureStateTest
.\gradlew.bat --no-build-cache :engine:pdf:test `
  --tests com.koodoreader.engine.pdf.PdfHostBridgeTest
```

**已知 CI/本地：** `test-pdfengine.js` 中 `dispatch` 成功路径 `error` 字段可能为 `null` 而非 `""`（历史断言，与 M3 无关）。

---

## 7 · 后续（M4+ / 增强，非 M3 范围）

| 项 | 说明 |
|----|------|
| PDF 智能裁边 | 按像素扫描白边 / `CropBox` 自动 inset，而非均匀 margin |
| 重排标注 | 需 text layer 或 reflow DOM 选区 → CFI/PDF range 映射 |
| 漫画 | `engine/image` 与 `doublePage` 已有；裁边参数可复用 |
| 备份 | M4 将 `pdfCropMarginPx` / `pdfReflow` 纳入 `BackupBundle` |
| 用户指南 | M4 更新 `docs/android-user-guide.md` |

---

## 8 · 风险与缓解

| 风险 | 缓解 |
|------|------|
| 重排 HTML 过大 / XSS | 仅本地 `loadData`，`javascriptEnabled=false`；文本 `escapeHtml` |
| spread 与 `scrolled=true` 用户预期 | spread 强制分页；设置页可注明双页需分页 |
| 裁边误伤内容 | 上限 48px + 默认 0；不做自动裁边 |
| WebView 双实例（引擎+重排） | 重排只读、无 worker；lite 档仍可用 raster |

---

*Plan self-review（2026-10-21）：与总规格 M3 三条一一对应；MVP 重排明确边界；验收可截图/可脚本。*
