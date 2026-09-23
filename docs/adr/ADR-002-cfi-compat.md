# ADR-002 定位与标注兼容策略：CFI 为唯一事实源

- 日期：2026-09-23
- 状态：已采纳（Phase 0，`engine/cfi` 已落地）
- 关联：迁移方案 §2/§8；风险 R2/R3；`schema.lock`（notes 表结构）

## 背景

桌面端标注（高亮/笔记/书签）持久化在 `notes.db` / `books.db`，`schema.lock` 已固化其结构：`notes` 表 12 列含 `cfi`、`range`、`percentage` 等——**位置锚点是 EPUB CFI**。原生端排版引擎（自绘分页）与 WebView 的 CSS columns 分页**必然产生不同页码**（R3），若用页码或字符偏移做跨端定位，标注必然错位、数据不可信（R2）。

上游实现规模：kookit `src/libs/cfi.ts`（883 code）+ `src/libs/epubcfi.js`（309）、foliate-js `epubcfi.js`（296）——纯逻辑、无 DOM 依赖的部分可 bug-for-bug 直译。

## 决策

1. **CFI 是跨端唯一定位事实源**。Kotlin 侧 `android/engine/cfi`（`Cfi.kt` / `CfiParser` / `CfiSerializer` / `CfiTokenizer` / `CfiOps`）对上游**bug-for-bug 对齐**，不"修正"上游行为。
2. **黄金向量回归**：`scripts/gen-cfi-golden.js` 从上游 JS 引擎生成 76 条 CFI 向量（`engine/cfi/src/test/resources/cfi-golden.tsv`），Kotlin 单测逐条比对；CI 跑 `:engine:cfi:test` 与 `--check` 防漂移（commit `29fba9b8`）。
3. **UI 不承诺绝对页码**：进度展示「章节 + 百分比」，`percentage` 列双端 schema 已存在；R3 由此化解。
4. **数据层逐列对齐**：Room 表结构与 `schema.lock` 的列名/类型一致，桌面导出的 `.db`/备份 zip 可直接导入安卓。
5. 验收指标：桌面 ↔ 安卓**双向标注位置一致率 ≥ 99%**（§8）。

## 备选方案与理由

| 备选 | 否决理由 |
|---|---|
| 页码定位 | 两个排版引擎分页不同，页码天然漂移 |
| 纯字符偏移 | 重写排版后 DOM 等价层的文本归一化细节（空白、软换行）会改变偏移；且与桌面存量 CFI 数据不互通 |
| 自定义 XPath+offset 格式 | 重复造 CFI 的轮子，且失去与 foliate/kookit 生态及桌面存量数据的兼容 |

## 影响

**正面**：桌面 ↔ 安卓标注/笔记/书签双向互通；兼容性由黄金向量**机械防回归**，不依赖人工走查；CFI 模块纯逻辑、可在 JVM 单测（无需 Android SDK）。
**负面**：DOM 等价层（CFI → 原生排版位置解析）需要充分单测覆盖（R2）；依赖绝对页码的功能（如纸质书页码对照）在原生端不提供。

## 验证方式

- `gradle :engine:cfi:test`（CI 已接入）；`node scripts/gen-cfi-golden.js --check`（CI 已接入）。
- P2 验收：真实书样本双端互导标注，一致率 ≥99%。

## 回滚方案

`engine/cfi` 为纯逻辑模块，无副作用；若某格式定位精度不达标，该格式按 ADR-003 路由表退回兜底岛渲染，标注数据格式不受影响（两端都仍是 CFI）。
