# Android Native Shell 使用指南

> 本文档描述 `android/` 原生壳（Compose + Material3）的功能、交互方式与已知局限。
> 阅读前置知识：`android/` 是独立于桌面端（Electron + React）的 Android 应用壳，
> 共享 `engine/`（CFI / 分页 / TOC）与 `core/`（数据 / 设计系统）两棵子模块树。

---

## 1. 底部导航 4 Tab

应用启动后落在**书库**页，底部栏依次排列：

| Tab | 图标 | 说明 |
|---|---|---|
| 书库 | `Icons.Default.MenuBook` | 书籍列表，按书名排序 |
| 笔记 | `Icons.Default.StickyNote2` | 跨书高亮 / 笔记 / 书签 |
| 统计 | `Icons.Default.BarChart` | 阅读时长与频率图表 |
| 设置 | `Icons.Default.Settings` | 内容源 / 语言 / 数据 / 关于 |

**交互约定**

- 进入阅读器时底部栏**自动隐藏**（沉浸式），返回按钮在左上角。
- 设置页的三个叶子节点（备份与恢复 / 回收站 / 词典）**保留底部栏**，属设置内部的纵深导航。
- 统计页顶层入口**不显示 ✕ 关闭按钮**——底部栏即为退出方式，关闭按钮是冗余的。

### 已知局限

- 旋转屏幕 / 深浅色切换的实机行为**未验证**（开发环境无设备）。
- 底栏进出阅读器不泄漏的**单测已覆盖逻辑层**，真机观感需人工确认。

---

## 2. 书库（Library）

### 顶部栏

收敛为：**标题「书库」+ 📤 导入 + 📋 视图菜单**。

- **导入**菜单包含两项：
  1. 「选择文件」— 从 Android 文件选择器选 EPUB / PDF / TXT / MD / MOBI / AZW3 / FB2 / DOCX / HTML
  2. 「选择文件夹」— 批量导入目录
- **视图菜单**（`MoreVert` 图标）控制列表布局（网格 / 列表，取决于实现）。

### 书籍卡片

- 封面：若书籍有 base64 封面则渲染原图，否则按书名哈希从 8 色调色板取占位色。
- 长按书卡触发上下文菜单（删除 / 详情等）。
- 点击书卡跳转阅读器。

### 已知局限

- **搜索**功能未接入（属子项目 ③）。
- **平板自适应**栅格未实现。

---

## 3. 笔记（Notes）

### 三态筛选

顶部 `SingleChoiceSegmentedButtonRow` 三档：

| 档 | 含义 |
|---|---|
| 全部 | 高亮 + 笔记 + 书签 |
| 高亮笔记 | 高亮行 + 有正文的笔记行 |
| 书签 | 书签行（无选中文本，以 `label` 或章节为显示文本） |

### 行为

- 按**书名**分组展示，组内保持原始顺序。
- **无记录时**显示居中提示文字「No notes yet」。

### 标注跳转（CFI）

**自 v1.0.0+（本轮新增）起，点击任意标注行可跳转到对应位置：**

```
读者操作：Notes → 点击一行
内部路径：onJump(bookKey, cfi)
         → ShellNavHost.navigate(ShellNav.readerWithCfi(bookKey, cfi))
         → NativeEpubScreen(initialCfi = cfi)
         → session.resumePage(cfi)   // pageForCfi(cfi) 命中 → 落页
```

**粒度：页级（非字符级）**

当前渲染引擎（`engine/layout/paged-document`）的分页模型是「页面 → 行块」，不提供
"从 CFI 偏移量" API。因此跳转到标注所在**页**，但不会滚动/高亮到确切字符位置。
若需要字符级定位，需 `engine/layout` 新增锚点偏移能力（另立工作项）。

**不可解析 CFI 的处理**

若书籍被重新导入或分页参数变化，`pageForCfi(cfi)` 返回 `null`，
当前实现静默回落到**第 0 页**。

> ⚠️ 这对"恢复上次进度"是合理的兜底，但对"用户主动跳转"有误导性
>（看起来跳到了，实际位置不对）。
> 建议：后续版本应先探测 `pageForCfi(cfi) != null` 再导航，
> 否则显示"位置不可用"提示。见 P0-1 评估。

---

## 4. 统计（Stats）

复用 `feature/stats` 的统计视图，数据来自 `core/data` 的 Book / ReadingSession 表。

- 阅读时长（日 / 周 / 月）
- 热力图（日历视图）
- 折线图（趋势）

**配色**：所有颜色经 `MaterialTheme.colorScheme` 与 `ChartTokens` 解析，
跟随系统深浅色。深色模式下热力图色阶方向反转（浅→深 vs 深→浅）
是刻意的视觉选择。

### 已知局限

- 统计页**没有 ✕ 关闭按钮**（顶层 Tab 入口，底部栏即为退出方式）。
- 图表类型有限（折线 + 热力图），无堆叠条形图。

---

## 5. 设置（Settings）

### 当前可用的 4 个分组

| 分组 | 内容 |
|---|---|
| **内容源** | 词典（点按跳入 `DictManagementScreen`） |
| **语言** | 中英文切换（`I18nState.CHOICES`） |
| **数据** | 备份与恢复 · 回收站 |
| **关于** | 版本号（`BuildConfig.VERSION_NAME`） |

### 设计取舍

设计稿 §5 列了 8 个分组（阅读 / 外观 / 语音 / 翻译 + 上述 4 组），
但 D9 批准时明确"不新增设置项"。已 grep 证实：

- `FontManager` / `FontPrefs` 在 `:app` 中**无 Compose 屏幕调用**
- `TtsControlSheet` **零调用**（仅在阅读器内出现）
- `TranslationPopup` **零调用**

→ **阅读 / 外观 / 语音 / 翻译** 4 组当前无对应 UI 可接，
刻意不做"点了没反应的禁用行"。待子项目 ②（功能扩展）补齐后重新接入。

---

## 6. 阅读器（Native Reader）

### 支持的格式

| 格式 | 引擎路径 |
|---|---|
| EPUB | `NativeEpubScreen` + EpubBookSession |
| TXT / MD | `NativeEpubScreen` + TextBookSession |
| MOBI / AZW3 | `NativeEpubScreen` + MobiBookSession |
| HTML / XHTML | `NativeEpubScreen` + WebBookSession |
| FB2 | `NativeEpubScreen` + Fb2BookSession |
| DOCX | `NativeEpubScreen` + DocxBookSession |
| PDF | `NativePdfScreen`（JS 引擎） |
| CBZ / CBT / CB7 | `ComicViewerActivity`（独立 Activity） |

### CFI 跳转入口

除 Notes 页触发的路由外，**桌面端 / 外部 Deeplink** 也可携带 CFI 参数：

```
koodo-reader://reader/{bookKey}?cfi={epubcfi-string}
```

CFI 格式见 `docs/adr/ADR-002-cfi-compat.md`。

### 翻页与进度

- 点区翻页：左 1/3 上页，右 1/3 下页，中 1/3 呼出进度条
- 每翻一页写回进度 CFI（`ReaderProgressPrefs`）
- 重打开书籍时优先 `initialCfi`，其次存储的进度 CFI

---

## 7. 设计系统（开发者）

### 模块分工

| 模块 | 职责 | 依赖 |
|---|---|---|
| `core:designsystem` | 纯 JVM token 值（颜色 / 形状 / 间距 / 对比度 / CSS 解析）| 无 |
| `core:ui` | Compose 绑定（`KoodoTheme` / 组件）| `core:designsystem`（api 导出）|
| `:app` / `:feature:*` | 消费方 | `core:ui` |

### 关键 Token

| 类型 | 入口 | 说明 |
|---|---|---|
| 颜色 | `KoodoColors` | 25 语义槽位 × 浅/深，WCAG 对比度已验证 |
| 形状 | `KoodoShapes` | M3 五档：4/8/12/16/28dp |
| 间距 | `KoodoSpacing` | 4dp 栅格：4/8/12/16/24/32dp |
| 图表色 | `KoodoChartColors` | 折线/网格/热力图专用，跟随主题 |

### 约定

- **不得** 在 `:core:ui` 中依赖 `:core:data`
- 用户可见文本以参数/闭包传入，**不得**在 `:core:ui` 组件内调用 `t()`
- 新增 token 值先落 `core:designsystem`（纯 JVM + 单测），再落 `core:ui`（Compose）

---

## 8. 未验证 / 后续

| 项 | 状态 | 说明 |
|---|---|---|
| 真机冒烟（4 Tab / 底栏 / 旋转 / 深浅色）| ⛔ 未做 | 开发机无设备 |
| 设置 4 组未接（阅读/外观/语音/翻译）| ⛔ 未做 | 无既有 UI 可接（已 grep 证实） |
| CFI 不可解析时的"位置不可用"提示| ⏳ 待办 | P0-1 已记录，需 `engine/layout` 支持 |
| 字符级 CFI 定位 | ⏳ 待办 | 需 `engine/layout` 锚点偏移 API |
| 书库搜索 | ⏳ 子项目 ③ | 超出本卡范围 |
| 平板自适应 | ⏳ 子项目 ③ | 超出本卡范围 |
