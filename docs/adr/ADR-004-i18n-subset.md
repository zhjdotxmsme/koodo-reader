# ADR-004 安卓 i18n 子集策略（桌面 locale 桥）

- 日期：2026-09-23
- 状态：已采纳

## 背景

桌面端使用 react-i18next，`src/assets/locales/*.json` 共 **41 个 locale、每包约 1.4k 扁平 key→string 条目**（en.json ≈ 95 KB）。安卓原生壳（P1）需要与桌面**完全一致的 key**（"key 与桌面一致"），但：

- 安卓资源系统要求 string 资源名为合法 Java 标识符，而桌面 key 是任意自然语言句子（如 `"Click the import button to add books"`），生成 `values-*/strings.xml` 需要名字混淆/哈希映射，调试与 diff 都不可读；
- 全量打包 41 个 locale 会让 APK 增加约 4 MB 未压缩资产，而 P1 阶段原生壳只服务中文/英文用户（其余语言由兜底岛 WebView 承接）。

## 决策

1. **不使用 Android string resources**。桌面 locale JSON 原样（逐字节 copy）打进 `android/app/src/main/assets/locales/<code>.json`，运行时用 `core:common` 的零依赖扁平 JSON 解析器（`FlatJson`）加载，`Localization.t(key)` 查表。
2. **子集打包**：仅随包 `en` + `zh-CN`（回归 mandated 的两种语言）。回退链：所选语言 → en → key 本身（与桌面缺失键行为一致）。
3. **构建期同步守卫**：`scripts/sync-locales-android.js` 负责 copy + 校验（JSON 合法、值全为 string、key 数>0）+ manifest（含源文件 sha256）。CI 跑 `--check`，桌面 locale 漂移即红。
4. **语言切换持久化**：`LibraryPrefs.language`（"system" | 代码），跟随系统为默认；切换即时生效（`Localization.language` 可变 + Compose State 驱动重组）。
5. 其余 39 个 locale 的"按需加载"（应用内下载 locale 包或 Dynamic Feature）**推迟到 P6+**，由本脚本的 `ANDROID_LOCALES` 白名单扩展，无需改动运行时。

## 影响

- 正面：key 零转换零混淆；与桌面同步只需一个脚本；APK 增量 ≈ 190 KB（en+zh-CN）。
- 负面：非中英文用户在原生壳内暂见英文（兜底岛仍提供 41 语言阅读体验）；`t()` 需要显式接入 Compose（`LocalI18n`），漏接的字符串退化为英文硬编码。

## 验证方式

- `sync-locales-android.js --check`（CI）：资产与 `src/assets/locales` 逐字节一致 + manifest sha256 对齐。
- `core:common` JVM 测试：解析器转义/Unicode/非字符串容忍；回退链 en→key；语言切换与 normalize（`zh_cn`→`zh-CN`）。
- 中英文回归：抽样的全部壳层 UI key 在 en.json 与 zh-CN.json 均存在（sync 脚本校验两包），`t("Deleted Books")` 中文环境返回「回收站」。

## 回滚方案

若未来需要 Android 原生资源（如接入系统级 per-app language）：写一个生成器把 JSON 映射为混淆资源名 + 反查表，`t()` 接口不变，仅换实现。
