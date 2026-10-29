# Android 全格式阅读能力设计（Koodo + Legado/Librera 借鉴）

> 日期：2026-10-21 · 状态：**M3 已落地**（M3 PDF 裁边/重排 + EPUB Foliate 双页 spread；M2 见 `plans/2026-10-21-android-reader-m2-gestures-perf.md`）
> 范围：`android/` 原生轨（`-Ptarget=native`），默认 Foliate 重排 + Compose 阅读壳
> 关联：`docs/android-native-migration.md`、`docs/superpowers/plans/2026-10-20-readest-reflow-engine.md`

---

## 1 · 目标与非目标

### 目标

在安卓端交付与桌面 **Koodo Reader 数据模型一致** 的本地阅读产品，覆盖：

| 类别 | 能力 |
|------|------|
| 格式 | EPUB、MOBI、AZW3/AZW、PDF、TXT（及 MD、HTML、FB2、DOCX 等 Foliate 已路由格式） |
| 阅读 | 渲染、目录、翻页/滚动、手势、单书 **双页对开**（PDF/漫画已有；重排书扩展） |
| Legado 向 | **净化规则**（文本替换，与桌面 `TextRule` 同形） |
| Librera 向 | 可配置点击区/滑动、PDF **重排**、**白边裁剪**、低性能设备档 |
| Koodo 向 | 本地 Room、桌面 `.db`/备份 zip、CFI 标注、词典/翻译/TTS |
| 定制 | **用户 CSS**（桌面 `customBookCSS` 等价） |

### 非目标

- **云同步**：不做 OneDrive/Google/WebDAV/S3 等（用户确认「仅本地」；与既有 ADR 一致）
- **Legado 书源订阅**：不在本规格范围
- **桌面插件 / AI 全量 parity**：按现有 feature 模块渐进接线
- **CBR 原生**：仍走兜底岛或显式不支持（ADR-006）
- **PDF 矢量批注**：扫描版/栅格宿主不承诺；重排模式仅文本层阅读

---

## 2 · 架构决策

**主路径（方案 A）**：重排类书籍统一 **Foliate WebView + Compose 壳**（`ReaderEnginePolicy` 默认 `foliate`）；`-Preader=canvas` 保留回滚与单测。

```
Library / Import / Backup (Room + AndroidDesktopDb + BackupBundle)
        │
        ▼
NativeEpubScreen │ NativePdfScreen │ ComicViewerActivity
  ReaderFeatureState（排版、规则、CSS、性能档、双页）
  ReaderGesturePrefs（点击区、滑动 — M2）
        │
   FoliateHostBridge          PdfJsHostBridge
   engine.mjs + readest-style   pdfengine（栅格 │ 重排 — M3）
        │
   Room: books / notes / bookmarks / progress(CFI)
   Backup: textRulesJson, customBookCss, ReaderFeaturePrefs
```

**「同步」定义**：导出/导入 Koodo 备份 zip、与桌面 `koodo.db` 双向读写；无网络同步服务。

**「分屏阅读」定义**：**单书双页对开**（`doublePage` / spread），非 Android 多窗口双书。

---

## 3 · 能力现状与缺口（2026-10-21 基线）

| 能力 | 状态 | 里程碑 |
|------|------|--------|
| EPUB/MOBI/AZW3/TXT 渲染 | Foliate 默认；Readest 排版部分在途 | M1 |
| 目录 / 翻页 | `FoliateHostBridge` toc/goTo/next/prev + Compose 手势 | M1 回归 |
| 净化规则 UI + 存储 | `ReadingSettingsRoute` + `textRulesJson` | M1 Foliate 生效 |
| 用户 CSS | 桌面有；安卓 `engine.mjs` 中 `userStylesheet: ''` | M1 |
| 标注 / CFI | Foliate annotation API + Room | M1 矩阵测试 |
| 翻译 / TTS | `NativeEpubScreen` + `feature:translate` / `feature:tts` | M1 与净化文本一致 |
| 本地存储 / 备份 | Room + dbio + Backup | M4 扩展字段清单 |
| PDF 阅读 | 栅格 + 目录/搜索/OCR | M3 重排+裁边 |
| 双页对开 | PDF/漫画 `doublePage` | M3 EPUB spread |
| 手势可配 | 固定 1/3 点击区 | M2 |
| 低性能档 | 无产品化 | M2 |
| MOBI HUFF/CDIC | Foliate 覆盖为主；Kotlin mobi 引擎缺口 | M1 监控 / 后续 |

---

## 4 · 里程碑

### M1 · 重排书闭环（规则 + CSS + Readest）

1. 收口 Readest：`ReadestViewSettings` → `setViewSettings`（主题、字号、边距、滚动/分页、栏数）。
2. **净化规则进 Foliate**：与 `TextRuleEngine` / 桌面 `ttsUtil.applyTextRules` 语义一致；TTS、翻译、复制共用净化后文本。
3. **customBookCss**：进 `ReaderFeatureState` + `ReaderFeaturePrefs`；传入 foliate `userStylesheet`；设置页开关对齐桌面 `isCustomBookCSS`。
4. 标注/进度/目录：fixture 回归 + 桌面 CFI 抽样。

**验收**：Node `test-foliate-host.js` + `:app:testDebugUnitTest` 相关类全绿；3 本烟雾（复杂 EPUB、MOBI、大 TXT）静态/真机清单通过。

### M2 · 手势 + 低性能档

1. `ReaderGesturePrefs`：左/中/右动作、滑动翻页、音量键（与 PDF/EPUB 一致）。
2. `perfTier`: `normal` | `lite` — 降低 PDF 渲染宽度、Foliate `max-inline-size`、减少动画、默认单栏。
3. WebView 预热：复用 `LocalAssetServer` 单例策略，避免每书冷启动。

**验收**：lite 档在 API 28 模拟器连翻 50 页无 ANR（Macrobenchmark 可选）。

### M3 · PDF 增强 + 重排书双页

1. EPUB/MOBI **spread 双页**（与 `columnsPerPage=2` 区分）。
2. PDF **白边裁剪**：`cropInsets` 作用于渲染位图；漫画可复用 `engine/image`。
3. PDF **重排模式**：pdf.js 文本流视图；扫描版仍栅格+OCR。

**验收**：文字 PDF 可切换重排；裁边后页眉空白 visibly 减少（截图对比）。

### M4 · 本地数据一体化

1. `BackupBundle` / 导出含：`textRulesJson`、`customBookCss`、阅读 prefs、按书规则 scope（对齐 `getTextRules(bookKey)`）。
2. 更新 `docs/android-user-guide.md`：无云同步、双页/重排/裁边/规则/CSS。

**验收**：桌面改标注 → 备份 → 安卓恢复 → CFI 与颜色一致；规则/CSS 不丢失。

---

## 5 · 数据与配置键

| 键 / 字段 | 存储 | 桌面等价 |
|-----------|------|----------|
| `textRulesJson` | `ReaderFeaturePrefs` | `textRuleList` + `textRules.*` |
| `customBookCss` | `ReaderFeaturePrefs`（新增） | `customBookCSS` |
| `isCustomBookCssEnabled` | 布尔 prefs（新增） | `isCustomBookCSS` |
| `scrolled`, `columnsPerPage`, `fontScale`, … | 已有 `ReaderFeatureState` | `readerConfig` |
| `doublePage` | 已有 | 双页/spread |
| `perfTier`, `gesture*` | M2 新增 | 无（Librera 向本地扩展） |

规则作用域：优先实现 **全局规则**；按书规则与桌面 `getTextRules(bookKey)` 对齐为 M4 或 M1 子任务（实现计划细化）。

---

## 6 · 错误处理与回退

- Foliate `open` 失败：用户可见错误 + 高级/Debug 可选「Canvas 打开」（`-Preader=canvas`）。
- 规则/CSS 非法：忽略单条规则并 log warn；CSS 解析失败不 crash WebView。
- PDF 重排不可用（纯扫描）：提示切换「原版式」。
- 备份恢复版本不匹配：显式失败信息，不 silent 丢字段。

---

## 7 · 测试策略

| 层 | 内容 |
|----|------|
| JVM | `ReadestViewSettingsTest`、`ReaderFeatureStateTest`、`TextRuleJson`、`TextRuleEngine` |
| Node | `scripts/test-foliate-host.js`（规则、CSS、flow、theme） |
| 集成 | 固定 EPUB fixture 在 CI（已有 foliate 守卫则扩展） |
| 真机 | 复杂 EPUB、AZW3、文字/扫描 PDF、低内存设备 lite 档 |

---

## 8 · 风险

| 风险 | 缓解 |
|------|------|
| Foliate 路径规则仅 Canvas 生效 | M1 首要交付 |
| WebView 内存 | M2 perfTier + 单 WebView 池 |
| PDF 重排工期 | 独立 M3，不阻塞 M1 |
| AGPL Readest style bundle | 与现有 `readest-style.mjs` 一致，APK 继续 AGPL |

---

## 9 · 实现计划索引

| 文档 | 内容 |
|------|------|
| `docs/superpowers/plans/2026-10-20-readest-reflow-engine.md` | Readest 排版（并入 M1） |
| `docs/superpowers/plans/2026-10-21-android-reader-m1-foliate-rules-css.md` | **M1 可执行任务分解** |
| `docs/superpowers/plans/2026-10-21-android-reader-m2-gestures-perf.md` | **M2 手势 + 性能档** |
| `docs/superpowers/plans/2026-10-21-android-reader-m3-pdf-spread.md` | **M3 PDF 裁边/重排 + EPUB spread** |

---

*Spec self-review（2026-10-21）：无 TBD；M1–M4 边界清晰；同步=本地、分屏=双页已写死；规则/CSS 明确 Foliate 为主路径。*
