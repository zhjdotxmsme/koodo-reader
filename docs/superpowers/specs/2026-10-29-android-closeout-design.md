# Android 功能收口设计（风险升序四批）

> 日期：2026-10-29 · 状态：已确认（用户选定「含大功能」收口线 + 方案 A 风险升序）
> 范围：`android/` 原生轨 · 真机回归本轮不做（用户确认），发布工程与数据一体化为静态可验证收口
> 关联：`docs/superpowers/specs/2026-10-21-android-full-reader-design.md`（M1-M3 已落地，本规格含其 M4）、`docs/superpowers/specs/2026-10-29-android-uiux-roadmap-design.md`（并行线）

---

## 1 · 目标与非目标

### 目标

把安卓端从「功能完整但未收口」推到「可发布」：

1. 清掉全部已知小修（注释漂移、协程残留、过期文档）
2. 产出**可分发的签名 release APK**（自签 keystore）
3. 完成 M4 本地数据一体化：规则/CSS/偏好随备份 zip 双向流动
4. 补齐两个大功能：MOBI HUFF/CDIC 解码、云字典下载

### 非目标

- **真机/模拟器回归**：本轮不跑（无 SDK/设备）；`docs/superpowers/plans/2026-10-21-m1-device-smoke-checklist.md` 与 m3 清单继续挂起，待装机后执行
- **PDF 注释**：维持 by design 不做（栅格宿主无文本层，见 10-21 规格 §1 非目标）
- **CBR 原生**：维持 ADR-006；批 2 只把「静默走岛」改为「显式不支持」
- **`-Ptarget` → product flavor（P8-F3）**：降级为 backlog（动 CI/build 脚本面大、收益低）
- **云同步**：不做（既有 ADR 不变；本规格的「一体化」仅指备份 zip）

---

## 2 · 批次总览与并行关系

```
批1 小修+测试兜底 ──┐
                    ├──（与 UI/UX P0 并行开工，见 uiux-roadmap 规格）
批2 发布工程 ───────┘
        │
        ▼
批3 M4 备份一体化
        │
        ▼
批4 大功能（HUFF/CDIC ∥ 云字典，两项互不依赖可并行）
```

批 2 结束即形成「可发布 APK」底线；批 3/4 失败不回退该底线。

---

## 3 · 批 1 — 小修与测试兜底

| 项 | 文件 | 改法 |
|---|---|---|
| 注释漂移 | `app/shell/StatsRoute.kt:29-32` | 注释改为描述已接入的 `progressProvider = { ReaderProgressPrefs(app).allFractions() }`（代码已接线，注释是旧的） |
| MainScope 残留 | `app/shell/TtsSettingsRoute.kt:115,123` | 两处 `MainScope().launch` 迁到 `rememberCoroutineScope()`；文件头 :60-66 的犹豫注释一并删除 |
| 过期文档 | `docs/p8-rollout-checklist.md`、`docs/android-end-to-end-availability-2026-10-09.md` | P0（include 注释）/P1（crash 接线）/P2（TTS 音色）/P4（TranslationPopup）标记已关闭并附 commit 号；「运行时回归」清单保留 |
| CI 兜底 | `.github/workflows/release-android.yml` | 增加「根 `./gradlew test`（全模块）」步骤，堵「单模块绿、跨模块挂」盲区 |

验收：`:app:testDebugUnitTest` 相关类绿；CI 全模块 test 步骤绿（本机无 SDK，以 CI 为准）。

---

## 4 · 批 2 — 发布工程

### 4.1 自签 keystore

- `keytool -genkeypair` 生成 RSA 2048 / 25 年有效期，别名 `koodo-android`
- 文件放 `android/keystore/koodo-release.jks`，**目录加入 `.gitignore`**；密码与别名写 `android/gradle.properties` 的本地覆盖（该文件不进 git；CI 走 secrets 注入同名属性）
- 交付时向用户说明：自签证书即发布身份，**丢失 = 无法更新应用**，需自行离线备份

### 4.2 签名链路

- 工程已有 `-Pkeystore` opt-in 参数链（`app/build.gradle`），从未实际使用
- 跑通 `node scripts/build-android.js --release --keystore ...`，产出签名 APK + 校验 `apksigner verify` 通过
- 版本号沿用现有 `versionName`/`versionCode` 来源，不改策略

### 4.3 stripIsland 默认化（release）

- release 构建默认 `-PstripIsland=true`（webview 兜底岛不进发布包，省约 7.8MB）
- **前置**：CBR 路由改造——`IntentRoutePolicy.ISLAND_ONLY` 分支从「queueBook 走岛」改为 `ComicViewerActivity`/库层显式提示「CBR 暂不支持」（对齐 ADR-006），strip 后 CBR 不再有无声死路
- debug 构建保留岛（webview 轨 `-Ptarget=webview` 开发路径不变）

验收：release APK 可安装签名校验通过；含 CBR 的 intent 打开时得到明确提示而非白屏。

---

## 5 · 批 3 — M4 本地数据一体化

### 5.1 数据流设计

桌面侧事实（已核实 `src/utils/file/restore.ts:83-104`）：
- restore 遍历 `config/` 全部条目：`config.json` 逐 key `ConfigService.setItem`；`sync.json` 走绑定；`*.db` 走表恢复；**其余条目静默跳过**

安卓侧改动：

| 方向 | 机制 |
|---|---|
| 桌面→安卓导入 | 解析 `config/config.json` 的 `textRuleList` / `customBookCSS` / `isCustomBookCSS` → 映射进 `ReaderFeatureState` 对应字段（`textRulesJson` / `customBookCss` / `isCustomBookCssEnabled`，键名见 10-21 规格 §5），经 `ReaderFeaturePrefs.save` 落盘 |
| 安卓→桌面导出 | `BackupBundle.write` 的 `configJson` 写入同样三个 key（桌面 restore 逐 key 恢复，桌面端直接生效） |
| 安卓私有偏好 | 手势、perfTier、fontScale 等桌面无对应物的键，序列化为 `config/koodo-android.json` 写入 zip（桌面静默跳过，已验证安全）；安卓导入自家备份时读取恢复 |

### 5.2 边界

- 规则映射语义以桌面 `TextRuleEngine` 为准，安卓已有 `textRulesJson` 解析层，只补 zip 通道
- 导入时字段缺失/非法：跳过该字段并 log warn，不 silent 清空用户现有设置
- 版本不匹配（未来字段）：`koodo-android.json` 带 `version` 字段，未知版本拒绝导入并提示

验收：安卓导出备份 → 桌面 restore → 净化规则/自定义 CSS 在桌面生效；桌面导出 → 安卓 importBackup → 同样生效；往返一轮无字段丢失。`docs/android-user-guide.md` 补「备份包含规则/CSS/偏好」章节。

---

## 6 · 批 4 — 大功能

### 6.1 MOBI HUFF/CDIC 解码（`engine/mobi`，纯 JVM）

- 新增 `HuffCdicDecoder.kt`：
  - 解析 HUFF record（record 0 头 24 字节 + cache/table 偏移，256-entry 主表 + 64-entry 辅助表，符号解码）
  - 解析 CDIC records（字典切片，LZR 前缀解压，`dataLength`/`extraFlags` 决定 slice 布局）
- `MobiParser.kt:160` 现状是「declared but not implemented」的 typed error 分支，改为真实解码；`MobiCompression.HUFF_CDIC` 从「显式拒绝」变为支持
- 算法对照物：仓库内 `android/app/src/main/assets/foliate/mobi.js`（AGPL 同源许可）
- 测试：合成最小 HUFF/CDIC fixture 单测（现有 `MobiParserTest.kt:348` 的拒绝用例改为解码成功用例）；抽一本真实 HUFF/CDIC 样书，Node 侧用 foliate 抽取全文与 Kotlin 侧 diff 为空

验收：`gradle :engine:mobi:test` 绿（跨模块，批 1 CI 兜底）；对照 diff 为空。

### 6.2 云字典下载（`:feature:dictionary` + `app/shell/DictionaryRoute`）

- `CloudDictCatalog.kt`：11 条硬编码（对齐桌面 `KookitConfig.CloudDictList`：basic-english-irish / english-italian / cc-cedict / et-tedict / jmdict / moby / wordnet / opted / english-arabic / tcm_textbook / ecdict，字段 id/name/translation/source）
- `CloudDictDownloader.kt`：接口在 `:feature:dictionary`，实现用 `HttpURLConnection` 放 `:app`（跟随 `:feature:translate` HttpTransport 接口 + `:app` TranslateHost 实现的既有分层惯例）；GET `https://storage.koodoreader.com/dicts/{id}.mdx`（桌面 `dictUtil.ts:145-151` 同源，无鉴权头）；流式写临时文件 + 进度回调（0-1），完成后原子 rename
- 落盘与注册：复用 `DictionaryImport` 现有存储（`filesDir/dict/{id}.mdx` + dictList/meta），下载完成即出现在已装字典列表
- UI：`DictionaryRoute.kt` 增加「云字典」分区，每行 = 字典名（中文环境用 translation）+ 来源小字 + 状态（下载按钮 / 进度条 / 已装标记）；失败 toast + 可重试；已装项不重复下载
- 隐私与体积：下载走用户显式点击，不在启动时预取目录

验收：JVM 单测（catalog、进度计算、重定向处理）；装机后下载 cc-cedict 并查词成功（列入真机冒烟清单，本轮不执行）。

---

## 7 · 风险

| 风险 | 缓解 |
|---|---|
| HUFF/CDIC 算法细节坑（边界符号、多引用 slice） | 以 foliate mobi.js 为对照做 diff 验收；卡住时降级为「保持 typed error + 文档声明 Foliate 轨已覆盖」，不阻塞发布 |
| 桌面 config.json 字段名与预期不符 | 实施计划阶段先以真实桌面导出 zip 核实字段；映射层单独成函数便于修正 |
| 自签 keystore 丢失导致无法更新 | 交付说明明确备份责任在用户 |
| stripIsland 后 VIEW intent 残留岛路由假设 | 批 2 一并核查 `AndroidManifest.xml` intent-filter 与 `IntentRoutePolicy` 分支一致性 |
| 本机无 SDK 无法本地验证 gradle 改动 | 全部 gradle/构建改动以 CI 绿为验收；本机只做静态检查 |

---

## 8 · 实施计划索引

（由 writing-plans 产出后回填：`docs/superpowers/plans/2026-10-29-android-closeout-*.md`）

*Spec self-review（2026-10-29）：无 TBD；批 2 stripIsland 的 CBR 前置已写明；桌面 config.json 字段名标注「实施时以真实导出核实」为显式验证步骤而非缺口；两条大功能互不依赖可并行；非目标与 10-21 规格一致。*
