# Android 收口批 4b（云字典下载）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 字典设置屏的「云字典」分区从空变为可用：11 本开放字典可下载、有进度、可重试，落盘注册复用现有 `DictRepository`，下载即出现在已装列表。

**Architecture:** 全部基础设施已在 `:feature:dictionary` 就位——`DictDownloadManager`（catalog/download/install 全流程）、`HttpOnDemandDownloader`（java.net 原子下载 + 断点续传）、`DictManagementScreen`（下载分区 UI + 进度条）。本计划只做三件事：提供 `assets/dicts/catalog.json` 清单、在 `DictionaryRoute` 接线（实例化 + state 填充 + onDownloadClick）、加清单与桌面的漂移守卫。

**Tech Stack:** Kotlin/Compose（:app）、:feature:dictionary 既有 API、Node 守卫脚本。

## Global Constraints

- 清单 id/name/translation/source 以桌面 `KookitConfig.CloudDictList`（src/assets/lib/kookit-extra-browser.min.js）为唯一事实源；`bytes` 未知填 0（下载器会跳过截断校验，属允许行为）。
- 下载 URL 由 `DictDownloadManager.cloudUrl` 生成（`https://storage.koodoreader.com/dicts/<id>.mdx`），不得手写。
- 不新增第三方依赖；不重复实现下载逻辑（必须用 HttpOnDemandDownloader）。
- 本机无 SDK：:app 编译归 CI；feature 模块 JVM 测试归 CI；Node 守卫本机可跑。
- UI 文案复用 DictStrings 既有键（downloadSection/download/alreadyInstalled/Download failed），新键才补 locale。

---

### Task 1: `assets/dicts/catalog.json` 清单 + 桌面漂移守卫

**Files:**
- Create: `android/app/src/main/assets/dicts/catalog.json`
- Create: `scripts/check-dict-catalog.js`
- Modify: `.github/workflows/release-android.yml`（在既有 guard 步骤区追加一步）
- Test: `android/feature/dictionary/src/test/kotlin/com/koodoreader/feature/dictionary/DictDownloadManagerTest.kt`（若已有该测试文件则追加用例）

**Interfaces:**
- Consumes: `DictDownloadManager.parseManifest(text)`（internal，:feature:dictionary 内可测）
- Produces: catalog.json（dicts: 11 条、bundled: []）；CI 守卫 `node scripts/check-dict-catalog.js`

- [ ] **Step 1: 写 catalog.json**

```json
{
  "dicts": [
    { "id": "basic-english-irish", "name": "Basic English-Irish Dictionary", "translation": "简明英爱词典", "source": "Pat Griffin", "bytes": 0 },
    { "id": "english-italian", "name": "English-Italian Dictionary", "translation": "简明英意词典", "source": "Pat Griffin", "bytes": 0 },
    { "id": "cc-cedict", "name": "CC-CEDICT", "translation": "简明中英词典", "source": "www.mdbg.net", "bytes": 0 },
    { "id": "et-tedict", "name": "ET-TEDICT", "translation": "简明中英英中词典", "source": "cdict-1.0-1.i386.rpm", "bytes": 0 },
    { "id": "jmdict", "name": "Japanese English Dictionary", "translation": "简明日英词典", "source": "JMDict", "bytes": 0 },
    { "id": "moby", "name": "Moby Thesaurus", "translation": "Moby Thesaurus", "source": "Chua Welic", "bytes": 0 },
    { "id": "wordnet", "name": "Wordnet", "translation": "Wordnet", "source": "Chua Welic", "bytes": 0 },
    { "id": "opted", "name": "Online Plain Text English Dictionary", "translation": "简明英英词典", "source": "Chua Welic", "bytes": 0 },
    { "id": "english-arabic", "name": "English-Arabic Dictionary", "translation": "简明英阿词典", "source": "Khaldoun Mahmoud", "bytes": 0 },
    { "id": "tcm_textbook", "name": "中医教材仓库", "translation": "中医教材仓库", "source": "Fredrik Jornebo", "bytes": 0 },
    { "id": "ecdict", "name": "ECDICT", "translation": "简明英汉字典增强版", "source": "GitHub-skywind3000", "bytes": 0 }
  ],
  "bundled": []
}
```

- [ ] **Step 2: parseManifest 测试**

在 feature 模块测试中内联同一份 JSON（或读 android/app/src/main/assets/dicts/catalog.json 的相对路径——选内联，避免路径脆弱）：
```kotlin
    @Test
    fun `shipped catalog parses with 11 cloud dicts`() {
        val manager = DictDownloadManager(fakeRepository, fakeDownloader) { CATALOG_JSON }
        val catalog = manager.catalog()
        assertEquals(11, catalog.size)
        assertTrue(catalog.any { it.id == "cc-cedict" })
        assertEquals("简明中英词典", manager.displayName(catalog.first { it.id == "cc-cedict" }, "zh-CN"))
        assertEquals("CC-CEDICT", manager.displayName(catalog.first { it.id == "cc-cedict" }, "en"))
        assertEquals("https://storage.koodoreader.com/dicts/cc-cedict.mdx",
            manager.cloudUrl("cc-cedict", isAuthed = false, region = ServerRegion.GLOBAL))
    }
```

- [ ] **Step 3: 漂移守卫脚本**

`scripts/check-dict-catalog.js`：
```js
// 读 android/app/src/main/assets/dicts/catalog.json 与
// src/assets/lib/kookit-extra-browser.min.js 中 CloudDictList 的 id 集合，
// 断言两边完全一致（桌面增删字典时此守卫变红，提示同步）。
const fs = require("fs");
const catalog = JSON.parse(fs.readFileSync("android/app/src/main/assets/dicts/catalog.json", "utf8"));
const lib = fs.readFileSync("src/assets/lib/kookit-extra-browser.min.js", "utf8");
const desktopIds = [...lib.matchAll(/\{id:"([^"]+)",name:/g)].map((m) => m[1]);
const androidIds = catalog.dicts.map((d) => d.id);
const missing = desktopIds.filter((id) => !androidIds.includes(id));
const extra = androidIds.filter((id) => !desktopIds.includes(id));
if (missing.length || extra.length) {
  console.error(`[check-dict-catalog] FAIL — missing: ${missing} extra: ${extra}`);
  process.exit(1);
}
console.log(`[check-dict-catalog] OK — ${androidIds.length} cloud dicts match desktop`);
```
（若 min lib 的正则形态不同，先 `grep -o '{id:"[^"]*",name:' src/assets/lib/kookit-extra-browser.min.js | head -3` 校准再定稿。）

- [ ] **Step 4: CI 接入 + 本机验证 + Commit**

release-android.yml 在 `check-locales.mjs` 步骤后加：
```yaml
      - name: Check Android cloud-dict catalog matches desktop list
        run: node scripts/check-dict-catalog.js
```
Run: `node scripts/check-dict-catalog.js` → OK 11 dicts。

```bash
git add android/app/src/main/assets/dicts/catalog.json scripts/check-dict-catalog.js .github/workflows/release-android.yml android/feature/dictionary/src/test/
git commit -m "feat(android): 云字典目录清单（11 本）+ 桌面漂移守卫"
```

---

### Task 2: DictionaryRoute 接线（实例化 + state + 下载回调）

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/DictionaryRoute.kt`
- Test: 无新 JVM 测试（Compose 路由层）；行为验证列入真机冒烟清单追加项

**Interfaces:**
- Consumes: `DictDownloadManager(repository, HttpOnDemandDownloader(), catalogJsonProvider)`；`DictManagementState(cloudDicts, downloadingId, downloadProgress)`；`OnDemandDownloader.Outcome`
- Produces: 云字典分区真实可用

- [ ] **Step 1: 实例化管理器与目录加载**

DictionaryRoute.kt 顶部 import 增：
```kotlin
import com.koodoreader.feature.dictionary.DictDownloadManager
import com.koodoreader.feature.dictionary.HttpOnDemandDownloader
import com.koodoreader.feature.dictionary.OnDemandDownloader
import com.koodoreader.feature.dictionary.ServerRegion
import com.koodoreader.feature.dictionary.ui.DictManagementState
```
在 `val repository = remember(app) { DictRepository(app.filesDir) }` 后加：
```kotlin
    val downloadManager = remember(app) {
        DictDownloadManager(
            repository = repository,
            downloader = HttpOnDemandDownloader(),
            catalogJsonProvider = {
                runCatching {
                    app.assets.open("dicts/catalog.json").bufferedReader().readText()
                }.getOrNull()
            },
        )
    }
```
（`repository` 的 remember 需保持同一实例——把 downloadManager 的 remember key 写成 `remember(app, repository)`。）

- [ ] **Step 2: state 填充云目录**

`reload()` 改为：
```kotlin
    fun reload() {
        state = state.copy(
            dicts = repository.dicts(),
            cloudDicts = downloadManager.catalog().filter { !downloadManager.isInstalled(it.id) },
        )
    }
```
（已装项从云目录消失，已装列表由 `dicts` 呈现——与桌面「已装不再显示下载按钮」语义一致。）

- [ ] **Step 3: onDownloadClick 真实下载**

把现有占位实现（:115-117 的 Toast）替换为：
```kotlin
        onDownloadClick = { item ->
            scope.launch {
                state = state.copy(downloadingId = item.id, downloadProgress = -1f)
                val outcome = withContext(Dispatchers.IO) {
                    downloadManager.download(
                        item = item,
                        isAuthed = false,
                        region = ServerRegion.GLOBAL,
                        language = i18n.language.value,
                        onProgress = { progress ->
                            state = state.copy(downloadProgress = progress.fraction)
                        },
                    )
                }
                state = state.copy(downloadingId = null, downloadProgress = -1f)
                when (outcome) {
                    is OnDemandDownloader.Outcome.Success -> reload()
                    is OnDemandDownloader.Outcome.Failure ->
                        Toast.makeText(context, str("Download failed") + ": " + outcome.reason, Toast.LENGTH_LONG).show()
                    OnDemandDownloader.Outcome.Cancelled -> Unit
                }
            }
        },
```
（`i18n.language.value` 的实际取值形式以该文件 `language` state 的现状为准——:46 已有 `by i18n.language.collectAsState()`，直接用 `language`。）

- [ ] **Step 4: 自检 + Commit**

自检清单：下载中 `downloadingId` 置 item.id（屏幕据此禁用其它下载按钮——读 DictManagementScreen.kt:218-242 确认此语义）；成功后 reload() 使该项从云目录消失并进已装列表；失败 Toast 后可重点同一项重试（state 已复位）；无新 i18n 键。

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/DictionaryRoute.kt
git commit -m "feat(android): 云字典下载接线（目录/进度/重试）"
```

---

### Task 3: 收尾验证与文档

**Files:**
- Modify: `docs/android-user-guide.md`（字典章节）
- Modify: `docs/p8-rollout-checklist.md`（云字典 gap 行——若有）

**Interfaces:**
- Consumes: Task 1/2 全部产物
- Produces: 文档与代码一致

- [ ] **Step 1: 文档**

用户指南字典节补：云字典 11 本开放词典可在线下载（storage.koodoreader.com），下载走显式点击、显示进度、失败可重试；与本地 MDX/MDD 导入共用已装列表与查词路径。

- [ ] **Step 2: 验证汇总**

本机：`node scripts/check-dict-catalog.js` 绿。
CI（workflow_dispatch 或下一 tag）：`:feature:dictionary` 63 基线 + 新增测试绿；`:app` 编译绿。
真机（列入 m1 冒烟追加项，不本轮执行）：下载 cc-cedict → 进度条 → 已装出现 → EPUB 双击选词查词出结果；飞行模式下点下载 → Download failed 可重试。

- [ ] **Step 3: Commit**

```bash
git add docs/android-user-guide.md docs/p8-rollout-checklist.md
git commit -m "docs(android): 云字典下载能力入用户指南"
```

---

## Self-Review 记录

- 规格覆盖：11 条清单（T1）、HttpURLConnection 惯例（复用 HttpOnDemandDownloader，未新造）、进度 UI（T2 Step 3 用屏幕既有 downloadingId/downloadProgress）、失败重试（state 复位）、已装不重复下载（isInstalled 过滤 + download() 内部 again-check）、落盘注册复用 DictRepository（downloadManager.download 内部 installFromFile）。
- 类型一致性：`DictDownloadManager(...)` 构造签名与 DictDownloadManager.kt:56-60 一致；`DictManagementState(cloudDicts/downloadingId/downloadProgress)` 字段名与 DictManagementScreen.kt:274-279 一致。
- 占位符：无；所有关键代码块完整。
