# 原生壳设置页（readest 式分区）设计

> 日期：2026-09-29 · 状态：待评审
> 背景：`docs/android-completeness-2026-09-29.md` §3 缺口 #1 的后续——TranslationPopup
> 已接线但无凭据录入入口；用户要求设置页对齐 readest 的独立分区设置页形态。
> 另：当前源码已有设置 tab（ShellTab.SETTINGS），用户设备上看不到是因为运行的是
> W4 之前的旧 APK，重新构建安装即可——本设计不改变 tab 结构。

---

## 1 · 目标与非目标

**目标**：把设置 tab 从「4 行稀疏列表」扩成 readest 式分区设置页——分区分组、
每项真实可用（遵守本仓库既有原则："a settings row that does nothing is a lie"，
SettingsScreen.kt W4 注释），并为翻译凭据、App 暗色、阅读页配色、TTS 参数
补齐它们唯一缺失的东西：设置 UI。

**非目标**：
- 不做云同步设置（P7 明确本地优先，无云同步）
- 不做翻页动画/点击区等阅读器交互重构（阅读分区只暴露已有可控制项）
- 不动 tab 栏结构与 reader 路由
- AI 助手（AiAssistant）参数设置不在本期——凭据与翻译源共用，但 AI prompt/
  模型选择留后续卡

## 2 · 架构：分区列表页 + 子页 leaf（方案 A）

与现有 `BACKUP / TRASH / DICTIONARY` leaf 完全同构。设置首页是分区分组的
列表页；需要编辑的分区点进去是独立 nav leaf，底部 tab 栏保留（leaf 不隐藏
底栏，`ShellNav.hidesBottomBar` 只对 reader 生效——既有行为不变）。

```
settings (SettingsScreen, 分区列表)
 ├── 通用      → 语言（就地行，现有）
 ├── 外观      → appearance (AppearanceRoute)        [新]
 ├── 阅读      → reading   (ReadingSettingsRoute)    [新]
 ├── 内容源    → dictionary (DictionaryRoute，现有)
 ├── 翻译与 AI → translate (TranslateSettingsRoute)  [新]
 ├── 语音朗读  → tts       (TtsSettingsRoute)        [新]
 ├── 数据      → 备份 / 回收站（就地行，现有 leaf）
 └── 关于      → about     (AboutRoute)              [新]
```

**新增路由**（`ShellNav` + `ShellDestinations.kt`）：`appearance`、`reading`、
`translate`、`tts`、`about`，全部注册进 `LEAF_OWNERS` → `ShellTab.SETTINGS`
（钻入时底栏高亮正确）与 `allRoutes()`（唯一性/前缀自由测试自动覆盖）。

**新增文件**（均在 `app/.../shell/` 或对应子包，一个子页一个文件，与
DictionaryRoute 同形：Android 侧胶水 + 状态，不做业务逻辑）：

| 文件 | 职责 |
|---|---|
| `shell/AppearanceRoute.kt` | App 主题三态 + 阅读页 ThemeKind 选择 |
| `shell/ReadingSettingsRoute.kt` | 默认字号缩放滑杆 |
| `translate/TranslateSettingsRoute.kt` | 三源凭据表单（放 `reader/translate/` 子包，与 TranslateHost 同包） |
| `shell/TtsSettingsRoute.kt` | 语速/音调/音量滑杆 |
| `shell/AboutRoute.kt` | 版本/许可 |
| `shell/ShellAppearancePrefs.kt` | App 主题模式 + 阅读页 ThemeKind 的持久化 |

`SettingsScreen.kt` 重写为分区列表（保留现有 SettingRow/SectionHeader
私有组件与"通用/内容源/数据"分区行）。

## 3 · 各分区详设

### 3.1 通用（现有，保留）
语言循环切换行（`I18nState.CHOICES`），行为不变。

### 3.2 外观（appearance）
- **App 界面主题**：跟随系统 / 亮色 / 暗色 三选一（`Strings` 用桌面目录里已有的
  theme 相关 key，缺的新增到 en.json 并跑 `sync-locales-android.js`）。
  - 持久化：`ShellAppearancePrefs`（新 SharedPreferences 文件 `shell_appearance`，
    key `appThemeMode` ∈ `system|light|dark`）。
  - 生效点：两个 `KoodoTheme(...)` 调用点——`NativeShellActivity.kt:39` 与
    `ComicViewerActivity.kt:63`。KoodoTheme 已有 `darkTheme: Boolean =
    isSystemInDarkTheme()` 参数，按 prefs 值覆盖：`system → isSystemInDarkTheme()`、
    `light → false`、`dark → true`。NativeShellActivity 用
    `collectAsStateWithLifecycle` 观察 prefs 流，切换即时生效无需重启。
- **阅读页配色**：默认 / 护眼 / 夜间 三卡片（`ThemeKind.DEFAULT /
  PROTECT_EYE / NIGHT`，`ThemeSpec` 已有预设色值；CUSTOM 本期不出现在选择器）。
  - 持久化：同一 prefs，key `readerThemeKind`。
  - 生效点：`NativeEpubScreen` 当前页色取 `MaterialTheme.colorScheme`（bgColor/
    fgColor，约 line 435-436）。改为优先读 `ShellAppearancePrefs.readerThemeKind`
    对应的 ThemeSpec 预设色；未设置时维持现状（跟随 MaterialTheme）。PDF 屏与
    漫画屏本期不接（pdf.js 自有渲染；漫画是图片）。

### 3.3 阅读（reading）
- **默认字号缩放**：Slider 0.7–2.5（与阅读器内 `setFontScale` 同一范围），
  读写同一个 `reader` prefs 的 `fontScale` key——阅读器顶栏 A−/A+ 与设置页
  互为同一事实源，不存在双写分叉。
- 分区说明文案注明：其余阅读行为（翻页模式等）随阅读器功能卡落地后再入此区。

### 3.4 内容源 / 数据（现有，保留）
字典管理、备份、回收站行不变，仅归入分区分组。

### 3.5 翻译与 AI（translate）
**核心交付**——让 TranslationPopup 的 NEEDS_CREDENTIALS 分支有处可去。

- 三个源各一个凭据卡片：Google / Microsoft / DeepL。
- **表单完全数据驱动**：遍历 `provider.credentialFields`（
  `CredentialField(key, label, required, secret, hint)` 的设计注释明言
  "used to render the settings form"），`secret=true` 的字段用密码输入框。
- 载入：`CredentialsStore.load(id)` 回填；API key 栏显示 `maskedApiKey(id)`
  占位符（`***wxyz`），留空 = 不修改。
- 保存：`CredentialsStore.save(id, ProviderCredentials(...))`（加密存储，
  日志自动脱敏）。必填校验：任一 `required` 字段为空则禁用保存按钮。
- 清除：`CredentialsStore.clear(id)`。
- **弹窗回链**：NativeEpubScreen 的翻译弹窗在 NEEDS_CREDENTIALS 状态下，
  「关闭」旁加「去设置」按钮 → 导航到 `translate` leaf（需要 reader 能触发
  导航：给 NativeEpubScreen 加可选回调 `onOpenTranslateSettings: (() -> Unit)?`，
  ShellNavHost 两个 reader 路由处注入 `navController.navigate(ShellNav.TRANSLATE)`）。
- 翻译历史：本期只记录不展示（历史浏览页留后续卡）。

### 3.6 语音朗读（tts）
- 三个滑杆：语速 0.5–2.0（步进 0.1）、音调 0.5–2.0、音量 0–1，
  初值 = `TtsConfig` 默认值（TtsRate.DEFAULT 等）。
- 读写 `TtsConfigRepository`（其 `TtsConfigStore` seam 的 :app 绑定查
  `p6-tts-semantics-mapping.md §5` 的宿主绑定处复用，不新建第二份存储）。
- 引擎/嗓音选择器**不做**（引擎枚举依赖设备 TTS 服务，单独成卡）；
  分区说明文案注明。

### 3.7 关于（about）
- 版本：`BuildConfig.VERSION_NAME (VERSION_CODE)`。
- 许可：一句话 AGPL-3.0 说明（链到仓库 LICENSE，不开新浏览器页也可——
  用 `LocalUriHandler` 打开 GitHub 仓库）。
- 崩溃日志位置说明（配合已有全局崩溃日志器，告诉用户去哪拿日志）。

## 4 · 数据流与持久化总表

| 设置 | 存储 | 读取方 |
|---|---|---|
| appThemeMode | `shell_appearance` prefs（新） | NativeShellActivity / ComicViewerActivity |
| readerThemeKind | 同上 | NativeEpubScreen |
| fontScale | `reader` prefs（现有 key 不变） | NativeEpubScreen / 阅读设置页 |
| 翻译凭据 | EncryptedSharedPreferences（现有） | TranslationPopupController |
| TTS 参数 | TtsConfigStore 绑定（现有） | TTS 服务 / TTS 设置页 |
| 语言 | 现有 i18n prefs | LocalI18n |

全部为键值存储，不碰 Room schema（schema.lock 五表保持干净）。

## 5 · i18n

新 UI 文案一律走 `i18n.localization.t(key)`；优先复用桌面
`src/assets/locales/en.json` 已有 key（"Appearance"、"Text-to-speech"、
"API Key" 等大概率已存在）。确需新增的 key 加进 en.json，然后运行
`node scripts/sync-locales-android.js` 同步到 Android 资产并跑
`--check`（CI 守卫）。

## 6 · 错误处理

- 凭据保存失败（Keystore 不可用等）：Toast 提示 + `credentials.logger.error`，
  不崩页面。
- 外观 prefs 损坏/缺失：回退 `system` / `DEFAULT`，不因设置页崩溃影响阅读。
- TTS 存储读取失败：回退 TtsConfig 默认值。

## 7 · 测试

- `ShellDestinations` 既有 JVM 测试自动覆盖新路由（allRoutes 唯一性、
  leaf owner、底栏可见性）——新增路由后跑 `:app:testDebugUnitTest` 验证。
- `ShellAppearancePrefs`：纯键值读写 + 枚举回退，JVM 单测（用
  `InMemorySharedPreferences` 风格 fake 或 Robolectric-free 的接口抽离——
  按现有 LibraryPrefs 的测试模式）。
- 翻译表单的数据驱动渲染逻辑（fields → 表单状态）抽到纯 Kotlin 类
  `CredentialFormState`，JVM 单测必填校验/留空不修改语义。
- 更新 `scripts/check-p6-entries.js`？不需要——TranslationPopup 已 wired；
  但需在 CI 可跑的前提下验证 `:app:compileDebugKotlin` +
  `:app:testDebugUnitTest` 全绿。

## 8 · 验收

1. 设置 tab 首页显示 8 个分区，每行可点且通向真实功能。
2. 暗色模式切换后整个壳（含书库/笔记/统计/设置）即时变暗，重启后保持。
3. 阅读页配色选「夜间」后，打开 EPUB 页底色变黑（不受 App 主题影响）。
4. 翻译设置页填入 Google key 保存后，阅读器选词翻译一次成功；
   key 栏显示 `***wxyz` 掩码。
5. 翻译弹窗无凭据时可一键跳「去设置」。
6. TTS 语速调到 1.5 后，阅读器朗读明显变快。
7. `node scripts/check-p6-entries.js`、`sync-locales-android.js --check`、
   `:app` 编译与测试全绿。

## 9 · 构建注意（给实现者）

本机沙箱构建命令（wrapper 默认写 `~/.gradle` 会被沙箱拒；JDK 路径已按 CI
审查移出 gradle.properties，需显式给）：

```
C:\Users\54389\AppData\Local\Gradle\gradle-8.5\bin\gradle.bat \
  -g .gradle-home \
  -p android \
  -Porg.gradle.java.installations.paths=D:/jdk-17/jdk-17.0.13+11 \
  :app:compileDebugKotlin :app:testDebugUnitTest
```
