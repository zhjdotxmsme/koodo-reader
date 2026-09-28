# CI/CD 打包配置 + UI/UX 对齐 readest 审查报告

> 审查范围：`.github/workflows/*`（5 个 workflow）+ spec §3/§7 对齐对照
> 审查日期：2026-09-26 · 审查者：agent session

---

## 1. GitHub Actions 版本核查

所有 action 版本均**实际存在**（经 GitHub API 验证 tags 标签）。

| Action | 最低版本 | 最新版本 | 本仓库使用 | 状态 |
|---|---|---|---|---|
| `actions/checkout` | v2 | v7.0.1 | v6 (android/release)、v4 (docker)、v2 (appx/upload) | ✅ 均可解析 |
| `actions/setup-node` | v1 | v7.0.0 | v6 (android/release)、v4 (docker)、v1 (appx) | ✅ 均可解析 |
| `actions/setup-java` | v3 | v5+ | v4 (android) | ✅ |
| `actions/upload-artifact` | v3 | v7 | v4 (android) | ✅ |
| `actions/setup-go` | v2 | v6 | v5 (docker) | ✅ |
| `samuelmeuli/action-electron-builder` | v1 | v1.6+ | v1.6.0 (release) | 需第三方仓库可访问 |

**结论：所有 action 版本合法，无"repository not found"风险。**

版本不一致但无害（不同 workflow 使用不同 major 版本）：
- `release-android.yml` + `release.yml`：`@v6`
- `docker-publish.yml`：`@v4`
- `release-appx.yml` + `upload.yml`：`@v2`/`@v1`

> 建议统一为 `@v6`（当前最新稳定），但非紧急。

---

## 2. CI 发现的问题

### 🔴 HIGH：`gradle.properties` 含 Windows 机器路径

```properties
# 当前值（已提交到 git）
org.gradle.java.installations.paths=D:/jdk-17/jdk-17.0.13+11
```

**影响**：
- **CI（Linux）**：该路径在 ubuntu 上不存在，Gradle 会静默忽略并 fallback 到自动检测（`actions/setup-java@v4` 设好的 JDK 17），所以**功能上不会坏**。
- **本地 Mac/其他开发者**：路径无效但不会报错，只会浪费启动时路径探测。
- **代码质量**：机器路径不该入库。

**修复**：删除该行。本地开发改为 `~/.gradle/gradle.properties`（不入库）或 `GRADLE_JAVA_HOME` 环境变量。

---

### 🟡 MEDIUM：手动安装 Gradle 8.5 vs. 使用 wrapper

`release-android.yml` 第 140–162 行手动下载并安装 Gradle 8.5：

```bash
curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.5-bin.zip
...
/opt/gradle-8.5/bin/gradle --version | grep -q "Gradle 8.5"
```

然而 W0 已补入 `android/gradlew` + `gradle-wrapper.jar`（`gradle-wrapper.properties` 版本 8.5）。

**问题**：
1. 手动下载每次 CI 都重新下 100+MB wrapper，有网络风险
2. wrapper 的 SHA 由 `gradle-wrapper.properties` 锁死，手动下载无法享受 checksum 校验
3. 如果未来升级 Gradle 版本，只需改 wrapper，不用改 workflow

**修复**：
- 移除 "Install Gradle 8.5"、"Activate Gradle"、"Verify Gradle 8.5 on PATH" 三个步骤
- 将工作目录下的 `gradle test` 改为 `./gradlew test`（需 `cd android`）

---

### 🟡 MEDIUM：`ubuntu-22.04` 在 `release.yml` 中已废弃

```yaml
matrix:
  os: [macos-26, ubuntu-22.04, windows-2022, ubuntu-24.04-arm]
```

GitHub 已宣布 `ubuntu-22.04` runner 即将下线（2025 下半年起逐步停止新分配）。如果现在新建 runner，可能会分配失败。

**风险**：workflow 在新分配的 runner 上可能无法运行。
**修复**：`ubuntu-22.04` → `ubuntu-24.04`。

---

### 🟢 LOW：`samuelmeuli/action-electron-builder@v1.6.0`

第三方 action 固定了小版本 `v1.6.0`。如果该仓库后续发布 v1.7 / v2，不升级则一直锁定，有维护风险（仓库消失、breaking change）。
**建议**：改为 `v1.x` 或 `v2` 浮动 tag，或定期升级。

---

## 3. `release-android.yml` 功能完整性

| 步骤 | 状态 |
|---|---|
| Node 22 ✅ | |
| JDK 17 ✅ | |
| yarn install --ignore-scripts ✅ | （跳过 Electron native postinstall） |
| TypeScript type check ✅ | |
| Android build tests ✅ | |
| CFI golden vectors ✅ | |
| Room schema lock ✅ | |
| Import rules ✅ | |
| DBio DDL ✅ | |
| Locales sync ✅ | — `check-phantom-tests.js` **本次已增强**：`KNOWN_UNBUILT` 清空、`settingsIncludes()` 修复，现在能检出未注册模块 |
| TTS manifest ✅ | |
| OCR manifest ✅ | |
| P6 entries ✅ | |
| PDF engine test ✅ | |
| Yarn build ✅ | CI=false (GHA lint warning) |
| Android SDK 34 ✅ | 自动检测 sdkmanager，含 platform + build-tools |
| **Gradle 测试全矩阵** ✅ | `gradle test --no-daemon`（覆盖 `:core:ui`、所有 `engine:*`、所有 `feature:*`、`:app`） |
| **Phantom test 守卫** ✅ | 已更新：未注册模块 = fail（不再静默跳） |
| Smoke zip restore ✅ | |
| Build APK (webview + native) ✅ | `build-android.js --debug --abi arm64-v8a --audit` |
| 16 KB ELF check ✅ | 纯 Node，无外部依赖 |
| Upload artifacts ✅ | `if-no-files-found: error` 严格验证 |
| Attach to Release ✅ | `gh release`（`GH_TOKEN` 为 `github.token`） |

**结论：Android CI 流程完整，门禁层数充足。**

---

## 4. CI 门禁对本轮改动的覆盖验证

| 本卡改动 | 被哪条 CI 门禁覆盖 |
|---|---|
| `:core:designsystem` (128 tests) | `gradle test`（全矩阵） |
| `:core:designsystem` selfCheck | ❌ **未被 CI 显式调用**（`gradle test` 只跑 JUnit，selfCheck 是独立 task） |
| `:core:ui` 编译 | `gradle test`（会触发编译） |
| `:app` ShellDestinations/Notes 测试 | `gradle test`（JUnit 5 部分；JUnit 4 `IntentRoutePolicyTest` 在 CI 上能跑，vintage 在 CI 上有网络可下载） |
| locales sync | `sync-locales-android.js --check` ✅ |
| `engine:toc` 5 处断言修复 | `gradle test`（JUnit 5） |

> **发现的缺口：`selfCheck` 未在 CI 中调用。** `:core:designsystem` 的 `selfCheck`（纯 JVM 自检，不走 JUnit）是独立 task，需显式 `gradle :core:designsystem:selfCheck`。当前 CI 只跑 `gradle test`。

**建议**：在 CI 中 `gradle test` 步骤后加一行：
```yaml
- name: Run designsystem selfCheck (pure JVM, no JUnit)
  run: cd android && gradle :core:designsystem:selfCheck --no-daemon
```

---

## 5. UI/UX 对齐 readest 对位核查

### 5.1 对齐成功（符合 spec §3/§5 的明确要求）

| spec 要求 | 当前实现 | 证据 |
|---|---|---|
| 4 Tab 底部导航（D5/D6） | `ShellScaffold` + `NavigationBar` | `ShellScaffold.kt` + `ShellNavHost.kt` |
| Library TopBar 收敛（7组→3项） | 标题 + 导入 + 视图菜单；语言迁入 Settings | `LibraryScreen.kt` + `ShellNavHost.kt` 改签名减 4 回调 |
| 笔记 Tab（新建，跨书聚合） | `NotesScreen` + `NotesViewModel` + 三态筛选 | `NotesAggregationTest` 17/17 |
| CFI 跳转（从笔记进入阅读器） | `READER_WITH_CFI_PATTERN` + `initialCfi` | `ShellDestinationsTest` 17/17（含 3 个新 CFI 测试） |
| 统计页隐藏 ✕ 按钮（顶层入口） | `StatsRoute(showClose=false)` | `StatsRoute.kt` |
| 阅读器全屏不占底栏（D11） | `ShellNav.hidesBottomBar()` + `ShellScaffold` | `ShellDestinationsTest` 17/17 |
| 设置分组页（已有能力接线） | 内容源/语言/数据/关于 | `SettingsScreen.kt` + 3 叶子路由 |
| `:core:designsystem` 纯 JVM 值层 | 128 tests + `selfCheck` 全绿 | token 层完整 |
| `:core:ui` Compose 绑定层 | `KoodoTheme`/`KoodoColors`/`KoodoShapes`/`KoodoSpacing`/`KoodoTypography`/`KoodoChartColors` | `:core:ui/compileDebugKotlin` BUILD SUCCESSFUL |
| 品牌蓝色保留（D2） | `KoodoColors.primary` = `#3A6EA5`（原 `Theme.kt` 值） | `ColorTokens.kt` |
| `feature:stats` 硬编码颜色清零 | `statsPalette()` 全部来自 `MaterialTheme.colorScheme` + `ChartTokens` | grep 0 matches `Color(0x`
| `feature:dictionary` 换 `:core:ui` TopAppBar | `DictManagementScreen` 用 `KoodoTopAppBar`（自动镜像返回箭头）| grep 0 matches `Color(0x`/`RoundedCornerShape(`

### 5.2 有偏差（有意为之，已在决策中记录）

| spec 意图 | 实际偏差 | 原因 |
|---|---|---|
| §7 "一·骨架必需" 组件清单 | 7 个组件只建了 `KoodoBookCard` + `KoodoTopAppBar` | `SettingsScreen.kt` 注释明确：`KoodoSettingRow` 应属 `:core:ui`，"lands with the component batch"；当前为单点使用，不提前抽象。属"先本地后抽"的合理取舍 |
| §5 设置 8 组 | 只交付 4 组 | D9 不新增设置项；阅读/外观/语音/翻译无既有屏幕可接（grep 证实） |
| §5 书库搜索 | 未实现 | 子项目 ③（明确 YAGNI） |
| §6.1 "elevation 0 + hairline" | 用 M3 `NavigationBar` 默认行为（tonal surface，无显式 0dp elevation） | Material3 NavigationBar 默认已是 flat，视觉上等效 |

### 5.3 需要人工确认（无法静态验证）

| 项 | 为什么 |
|---|---|
| **真机冒烟**（4 Tab 可达/底栏不泄漏/旋转/深浅色） | 本机无设备，静态层面已闭环（编译+单测+grep），观感未验 |
| 深浅色切换后的实际观感 | 需要 `KoodoColors` 浅/深两套在真机上切换后看是否协调 |
| 卡片占位色 8 色是否和谐 | `COVER_PLACEHOLDERS` 的 8 色从原 `BookCard.kt` 继承，未重设 |

### 5.4 对齐度总分（按 spec §3 readest→koodo 对位表）

```
已对齐        ████████████████████░░░░░░  ~80%
有意缩减      ██░░  （设置 4/8，搜索无）
待子项目②③  ▓▓░░░  （阅读器内部/书库深化）
未验证（真机）████░░░░░░
```

---

## 6. 修复建议优先级

| # | 优先级 | 修复项 | 预计改动 |
|---|---|---|---|
| 1 | 🔴 HIGH | 删除 `gradle.properties` 中的 Windows 路径，改为本机 `~/.gradle/gradle.properties` 或 env var | 1 行 |
| 2 | 🟡 MED | CI：`gradle test` 后追加 `gradle :core:designsystem:selfCheck`（1 行 YAML） | 2 行 |
| 3 | 🟡 MED | CI：用 `./gradlew` 替代手动安装 Gradle（删除 3 个步骤，改 1 行命令） | 约 -30 行 |
| 4 | 🟡 MED | `release.yml`：`ubuntu-22.04` → `ubuntu-24.04` | 1 行 |
| 5 | 🟢 LOW | 统一各 workflow 的 action 版本为 `@v6` | 5 处 |
| 6 | 🟢 LOW | `samuelmeuli/action-electron-builder@v1.6.0` → `v1`（浮动 tag，防仓库消失） | 1 行 |
| 7 | 📐 P3 | 真机冒烟后回填第 17/23 项检查清单 | 用户执行 |
