# Android 收口批 1+2（小修与发布工程）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清掉全部已知小修（注释漂移/协程残留/过期文档）并形成「可分发的签名 release APK」底线（自签 keystore + stripIsland 默认 + CBR 显式不支持）。

**Architecture:** 批 1 是纯注释/文档/局部协程迁移；批 2 在既有 `-Pkeystore` / `-PstripIsland` opt-in 链路上做三件事——生成真实 keystore、把 stripIsland 变为 release 默认（带逃生门）、给 strip 后的 CBR/未知格式一条显式提示路径（BuildConfig 标志 + routeIntent 早退）。

**Tech Stack:** Kotlin/Compose（`:app`）、Gradle -P 属性、`scripts/build-android.js`（Node 编排）、GitHub Actions（`release-android.yml`，runner 自带 Android SDK）。

## Global Constraints

- 本机**无 Android SDK、JDK 为 11**（AGP 需 17）——所有 `./gradlew` 验证只能在 CI 跑：`gh workflow run "Build/release-android"` 后 `gh run watch`；无法触发时该项标注「推迟到下次打 tag 验证」，不得谎报本地已验证。
- 本机可跑的验证只有 Node 侧：`node scripts/*.js`、`npx tsc --noEmit`、`npx react-scripts test --watchAll=false --testPathPattern "androidBuild"`。
- keystore 与密码**绝不进 git**；密码只在本地 shell 环境/CI secrets 中出现。
- MainActivity 的 toast 沿用该文件现状：硬编码英文字符串（`MainActivity.kt:334,346-348` 同款），不引 i18n。
- 不改 `IntentRoutePolicy`（路由判定不变，只改投递行为）。
- CI 已含「EVERY module」根 `./gradlew test`（`release-android.yml:140-152`）——本计划**不新增** CI 步骤，规格批 1 的「CI 兜底」项以核实代替。

---

### Task 1: StatsRoute 过期注释修正

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/StatsRoute.kt:29-32`

**Interfaces:**
- Consumes: 无（纯注释）
- Produces: 无

- [ ] **Step 1: 读取现状**

Run: `Read android/app/src/main/java/com/koodoreader/reader/shell/StatsRoute.kt`（行 20-70）
确认 :29-32 注释仍为「Known gap, deliberately not faked: `progressProvider` is empty...」，而 :60 已是 `progressProvider = { ReaderProgressPrefs(app).allFractions() }`。

- [ ] **Step 2: 替换注释**

把 :29-32 的 4 行注释：

```kotlin
 * Known gap, deliberately not faked: `progressProvider` is empty because the
 * native readers do not persist reading progress yet (P2 / gap 1). `:core:data`'s
 * `books` table has no progress column, so there is nothing truthful to pass;
 * the screen renders the same zeroes the desktop shows for a fresh database.
```

改为：

```kotlin
 * `progressProvider` reads `ReaderProgressPrefs.allFractions()` — the per-book
 * fractions the native readers persist on every progress write — so the
 * "continue reading" rows track real device-side progress. Desktop has no
 * equivalent prefs file; this remains an Android-only source by design.
```

- [ ] **Step 3: 确认无残留矛盾**

Run: `grep -n "progressProvider" android/app/src/main/java/com/koodoreader/reader/shell/StatsRoute.kt`
Expected: 两处（:60 注入 + 注释内一处引用），注释不再出现 "is empty"。

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/StatsRoute.kt
git commit -m "docs(android): 修 StatsRoute progressProvider 过期注释"
```

---

### Task 2: TtsSettingsRoute 两处 MainScope 迁移

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/TtsSettingsRoute.kt:64-66,112-131`

**Interfaces:**
- Consumes: `val scope = rememberCoroutineScope()`（该文件 :75 已存在，`selectEngine` :140 已在用）
- Produces: 无（行为等价重构）

- [ ] **Step 1: 迁移 persistSliders**

把 :112-120：

```kotlin
    fun persistSliders() {
        // Three-sliders only; voice / engine / locale keep their current value.
        // Left at `MainScope` for the dedicated refactor card (see file header).
        kotlinx.coroutines.MainScope().launch {
            repository.update { current ->
                current.copy(voiceSpeed = rate, pitch = pitch, volume = volume)
            }
        }
    }
```

改为：

```kotlin
    fun persistSliders() {
        // Three-sliders only; voice / engine / locale keep their current value.
        // `scope` cancels with the route (same as `selectEngine` below).
        scope.launch {
            repository.update { current ->
                current.copy(voiceSpeed = rate, pitch = pitch, volume = volume)
            }
        }
    }
```

- [ ] **Step 2: 迁移 resetAll**

把 :122-131 的 `kotlinx.coroutines.MainScope().launch {`（含其上注释行 `// \`rememberCoroutineScope()\` rather than...` 若在 resetAll 内）改为 `scope.launch {`。该函数体其余行不变。

- [ ] **Step 3: 删除文件头犹豫注释**

把 :64-66：

```kotlin
 * The `MainScope().launch` callsites in `persist()` / `Reset` were intentionally
 * left untouched; the dedicated refactor (`t-mv0hf34m-3j1k07`) moves the whole
 * screen to `ReaderViewModel.viewModelScope`.
```

整段删除。

- [ ] **Step 4: 验证无 MainScope 残留**

Run: `grep -n "MainScope" android/app/src/main/java/com/koodoreader/reader/shell/TtsSettingsRoute.kt`
Expected: 无输出。再跑 `grep -rn "MainScope" android/app/src/main/java/ | grep -v readerScope`，确认全 app 无其它残留（NativeEpubScreen 已在 9 处迁移中清零）。

- [ ] **Step 5: Commit（编译验证随后由 CI 兜底）**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/TtsSettingsRoute.kt
git commit -m "refactor(android): TtsSettingsRoute 两处 MainScope 迁到 rememberCoroutineScope"
```

---

### Task 3: 过期文档同步（p8 checklist + 10-09 摸底）

**Files:**
- Modify: `docs/p8-rollout-checklist.md`
- Modify: `docs/android-end-to-end-availability-2026-10-09.md`

**Interfaces:**
- Consumes: git log（各修复的 commit 号）
- Produces: 文档状态与代码一致

- [ ] **Step 1: 收集 commit 号**

Run: `git log --oneline --grep="P0 阻塞\|音色\|简繁" -- android/ | head -5`
记录：include 反注释（e0194368）、TTS 音色（185d3fc9）等实际 hash。

- [ ] **Step 2: 更新 p8-rollout-checklist.md**

对 §1（include 启用）、§3（crash wiring）各条目：状态改为「已关闭」，附 commit 号与日期 2026-10-29。不改 §2（签名/ABI 本计划正在做，做完后由本计划 Task 6 一并更新）。

- [ ] **Step 3: 更新 10-09 摸底报告**

在「二、5 个真实未收口项」每小节标题后追加一行 `> **2026-10-29 已关闭**：见 <commit>。`（P0=e0194368、P1/KoodoReaderApp、P2/185d3fc9、P3/readerScope 迁移 commit、P4/TranslationPopup 接线 commit——用 Step 1 与 `git log --grep` 取实号）。「四、运行时回归建议」整节保留不动（真机回归仍未做）。

- [ ] **Step 4: Commit**

```bash
git add docs/p8-rollout-checklist.md docs/android-end-to-end-availability-2026-10-09.md
git commit -m "docs(android): 同步 p8 清单与 10-09 摸底中已关闭的 P0-P4 项"
```

---

### Task 4: 自签 keystore 生成与签名链路验证

**Files:**
- Create: `android/keystore/koodo-release.jks`（不进 git）
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `app/build.gradle:90-99` 既有 signingConfigs.release（读 `-Pkeystore/-PstorePassword/-PkeyAlias/-PkeyPassword`）；`scripts/build-android.js` 既有 `--keystore/--store-password` 等 flag
- Produces: 可签名的 release keystore；Task 6 的 release 构建依赖它

- [ ] **Step 1: 生成 keystore**

```bash
mkdir -p android/keystore
keytool -genkeypair -v \
  -keystore android/keystore/koodo-release.jks -alias koodo-android \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -storepass "$(openssl rand -base64 18)" -keypass "$(openssl rand -base64 18)" \
  -dname "CN=Koodo Reader Android, OU=Koodo, O=Koodo Reader, C=CN"
```

生成时把两个随机密码记录到**仓库外**的私有位置（例如用户密码管理器；执行时向用户展示一次并要求自行保存）。若本机 keytool 是 JDK 11 自带版，产物格式兼容 JDK 17 读取，无需另装。

- [ ] **Step 2: gitignore 排除**

在 `.gitignore` 追加一行：

```
android/keystore/
```

Run: `git check-ignore android/keystore/koodo-release.jks`
Expected: 输出该路径（已被忽略）。

- [ ] **Step 3: 签名链路验证（CI）**

```bash
gh workflow run "Build/release-android"
gh run watch
```

CI 现有步骤只出 debug 包；本任务**不要求 CI 出 release 包**（release 签名构建在 Task 6 改 build-android.js 后一并在本地/CI 验证）。本步只确认 workflow 仍绿。若 gh 未配置，标注「推迟到下次 tag」。

- [ ] **Step 4: Commit（仅 .gitignore）**

```bash
git add .gitignore
git commit -m "chore(android): keystore 目录不入库"
```

---

### Task 5: stripIsland 构建下 CBR/未知格式显式不支持

**Files:**
- Modify: `android/app/build.gradle`（defaultConfig 增 buildConfigField）
- Modify: `android/app/src/main/java/com/koodoreader/reader/MainActivity.kt:299-308`
- Test: `android/app/src/test/java/com/koodoreader/reader/`（既有 IntentRoutePolicy 测试不受影响，跑全量确认）

**Interfaces:**
- Consumes: `IntentRoutePolicy.Route.ISLAND`（路由判定不变）；`app/build.gradle:32` 的 `def stripIsland`
- Produces: `BuildConfig.ISLAND_PACKAGED: Boolean`（后续任何 strip 相关分支复用）

- [ ] **Step 1: 定位 defaultConfig**

Run: `grep -n "defaultConfig {" android/app/build.gradle`
在 defaultConfig 块内（`buildFeatures { buildConfig true }` 已在 :135-138，无需开关）追加：

```groovy
        // stripIsland 构建不打包兜底岛：CBR/未知格式的 intent 到达时
        // MainActivity 必须显式提示，而不是投递给一个不存在的 webapp。
        buildConfigField "boolean", "ISLAND_PACKAGED", stripIsland ? "false" : "true"
```

- [ ] **Step 2: routeIntent 早退**

把 `MainActivity.kt:306` 的：

```kotlin
            IntentRoutePolicy.Route.ISLAND -> queueBook(uri, mimeType)
```

改为：

```kotlin
            IntentRoutePolicy.Route.ISLAND -> {
                if (!BuildConfig.ISLAND_PACKAGED) {
                    // stripIsland 构建：兜底岛不存在，CBR（ADR-006）与无法识别的
                    // 载荷显式不支持，避免无声死路。
                    AppLog.i(TAG, "island not packaged; unsupported payload: $uri")
                    toast("CBR and unrecognized formats are not supported in this build.")
                    return
                }
                queueBook(uri, mimeType)
            }
```

（`toast(...)` 与 `AppLog`/`TAG` 均为该文件既有成员，:334 有先例；`BuildConfig` 按需补 import `com.koodoreader.reader.BuildConfig`。）

- [ ] **Step 3: 本地静态检查 + Commit**

Run: `grep -n "ISLAND_PACKAGED" android/app/build.gradle android/app/src/main/java/com/koodoreader/reader/MainActivity.kt`
Expected: 各 1-2 处。行为级验证（真机开 CBR 收到 toast）列入 m1 真机冒烟清单追加项，本轮不执行。

```bash
git add android/app/build.gradle android/app/src/main/java/com/koodoreader/reader/MainActivity.kt
git commit -m "feat(android): stripIsland 构建对 CBR/未知格式显式提示不支持"
```

---

### Task 6: release 默认 stripIsland + 出签名 release APK

**Files:**
- Modify: `scripts/build-android.js`（CLI 解析 + gradle 参数拼装区）
- Modify: `docs/p8-rollout-checklist.md` §2（L3 签名、L5 stripIsland 状态）

**Interfaces:**
- Consumes: Task 4 的 keystore；`app/build.gradle:32,150-155` 的 `-PstripIsland` 消费端
- Produces: `node scripts/build-android.js --release ...` 默认剥离岛；`--keep-island` 逃生门

- [ ] **Step 1: 读现有 flag 区**

Run: `Read scripts/build-android.js`（:60-170 附近 CLI 解析区，先看 `--stripIsland` 是否已有——grep 显示无）
在 parse 段新增（沿用现有 `case "--keystore":` :156-157 的写法）：

```js
        case "--keep-island":
          opts.keepIsland = true;
          break;
```

help 文本数组（:71-75 附近）追加一行：

```js
      "  --keep-island           release 构建保留兜底岛（默认 --release 即剥离）",
```

opts 初始值（:107 附近）加 `keepIsland: false,`。

- [ ] **Step 2: gradle 参数拼装处注入**

定位 release 构建拼 gradle args 的位置（`grep -n "stripIsland\|assembleRelease" scripts/build-android.js`；若无 stripIsland 注入则在拼 `-P` 透传的数组处）。在 release 分支追加：

```js
      // release 默认剥离兜底岛（P8 §1 收口，2026-10-29 规格）：省 ~7.8MB，
      // CBR/未知格式由 BuildConfig.ISLAND_PACKAGED=false 显式提示（Task 5）。
      ...(!opts.keepIsland ? ["-PstripIsland=true"] : []),
```

（按该文件实际展开写法合入，保持其既有风格；debug 分支不加。）

- [ ] **Step 3: 本机 Node 侧验证**

Run: `node scripts/build-android.js --help | grep keep-island`
Expected: 输出新 help 行。`npx react-scripts test --watchAll=false --testPathPattern "androidBuild"` 全绿（该目录有 androidBuild 测试，改 CLI 必须过）。

- [ ] **Step 4: 出包验证（CI 或推迟）**

需要 Android SDK：在 CI 临时跑 `node scripts/build-android.js --release --abi arm64-v8a --keystore <path> --store-password <pw> --key-alias koodo-android --key-password <pw>`——keystore 文件不在 runner 上，故实际出包验证方式为：**本地不验证，把 keystore/base64 注入 GitHub secrets 后在 release-android.yml 追加 release job 由下一计划完成**；本轮验收下探到「CLI 参数正确透传（Step 3）+ 签名配置代码路径 review 通过」。向用户明说：真正签名 APK 的产出排在「CI secrets 注入」这一后续任务。

- [ ] **Step 5: 更新 p8 §2 状态并 Commit**

p8-rollout-checklist §2：L3（签名）改「keystore 已生成，CI secrets 注入待办」；L5（stripIsland）改「release 默认剥离（--keep-island 逃生门）」。

```bash
git add scripts/build-android.js docs/p8-rollout-checklist.md
git commit -m "feat(android): release 默认 stripIsland（--keep-island 逃生门）"
```

---

## Self-Review 记录

- 规格覆盖：批 1 四项（注释漂移/协程残留/文档同步/CI 兜底→核实为已存在）；批 2 三项（keystore/stripIsland/CBR 提示）各有 Task；「F3 flavor 化不做」与规格非目标一致。
- 占位符：无 TBD；Task 6 Step 4 的「CI secrets 注入待办」是显式移交项，非含糊。
- 类型一致性：`BuildConfig.ISLAND_PACKAGED` 在 Task 5 定义、Task 6 注释引用；`--keep-island` 在 Task 6 定义并自洽。
