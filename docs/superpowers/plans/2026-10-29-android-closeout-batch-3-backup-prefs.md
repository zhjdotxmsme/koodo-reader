# Android 收口批 3（M4 本地数据一体化）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 净化规则（textRules）、自定义 CSS（customBookCSS/isCustomBookCSS）与阅读偏好随备份 zip 双向流动：桌面导出→安卓导入生效，安卓导出→桌面 restore 生效，安卓私有偏好经 `config/koodo-android.json` 往返。

**Architecture:** 映射层放 `:engine:feature`（纯 JVM，复用 TextRuleJson 的桌面封套语义）；zip 通道在 `:core:dbio` 的 BackupBundle 增加 `config/koodo-android.json` 捕获/写入；接线在 `:app` 的 DesktopBridge（export 注入 configJson、import 回调应用偏好）。桌面 config.json 是**扁平 key→JSON 字符串**映射（restore.ts:96 逐 key ConfigService.setItem）。

**Tech Stack:** Kotlin/JVM（:engine:feature、:core:dbio）、Android SharedPreferences（ReaderFeaturePrefs/ReaderGesturePrefs）、已有 TextRuleJson.exportConfig/importRules。

## Global Constraints

- 桌面格式已核实（勿再猜测）：
  - `textRuleList`：顶层键，值为规则 id 的 **JSON 数组字符串**
  - `textRules`：顶层键，值为 `{id: rule}` 的 **JSON 对象字符串**（TextRule 字段：id/type/pattern/replacement/matchType/scope/bookKey/bookName/enabled）
  - `readerConfig`：顶层键，值为对象 JSON 字符串；`isCustomBookCSS` 取 `"yes"`/`"no"`，`customBookCSS` 为 CSS 文本
  - 桌面 restore 对 `config/` 下非 config.json/sync.json/*.db 条目**静默跳过** → `config/koodo-android.json` 对桌面不可见，安全
- 本机无 Android SDK / JDK 17：`:engine:feature`、`:core:dbio` 的 JVM 测试也归 CI；本机可跑 `node scripts/smoke-zip-restore.js` 与 `node scripts/check-dbio-ddl.js`。
- 导入语义：**字段缺失/非法跳过该字段并 log warn，不得清空用户现有设置**；`koodo-android.json` 带 `version`，未知版本拒绝导入并提示。
- 不动 `BackupBundle` 既有条目的解析语义（config/cover/book/fonts 行为不变）。

---

### Task 1: `:engine:feature` DesktopConfigPrefs 映射层

**Files:**
- Create: `android/engine/feature/src/main/kotlin/com/koodoreader/engine/feature/DesktopConfigPrefs.kt`
- Test: `android/engine/feature/src/test/kotlin/com/koodoreader/engine/feature/DesktopConfigPrefsTest.kt`

**Interfaces:**
- Consumes: `TextRule`、`TextRuleJson.exportConfig/importRules`（同模块既有）
- Produces:
  - `data class DesktopPrefs(val rules: List<TextRule>, val customBookCss: String, val customBookCssEnabled: Boolean, val warnings: List<String>)`
  - `object DesktopConfigPrefs { fun desktopConfigEntries(rules: List<TextRule>, customBookCss: String, customBookCssEnabled: Boolean): Map<String, String>; fun parseDesktopConfig(configJson: String?): DesktopPrefs }`

- [ ] **Step 1: 写失败测试**

`DesktopConfigPrefsTest.kt`：
```kotlin
package com.koodoreader.engine.feature

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopConfigPrefsTest {

    private fun rule(id: String) = TextRule(
        id = id, type = "replace", pattern = "foo", matchType = "plain",
        replacement = "bar", scope = "all", enabled = true,
    )

    @Test
    fun `export produces desktop flat string map`() {
        val entries = DesktopConfigPrefs.desktopConfigEntries(listOf(rule("1")), "h1{color:red}", true)
        assertEquals("[\"1\"]", entries["textRuleList"])
        assertTrue(entries["textRules"]!!.contains("\"pattern\":\"foo\""))
        val rc = entries["readerConfig"]!!
        assertTrue(rc.contains("\"customBookCSS\":\"h1{color:red}\""))
        assertTrue(rc.contains("\"isCustomBookCSS\":\"yes\""))
    }

    @Test
    fun `round trip preserves rules and css`() {
        val entries = DesktopConfigPrefs.desktopConfigEntries(listOf(rule("1")), "p{margin:0}", false)
        val flat = entries.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${TextRuleJson.render(JsonValue.Str(v))}"
        }
        val parsed = DesktopConfigPrefs.parseDesktopConfig(flat)
        assertEquals(1, parsed.rules.size)
        assertEquals("foo", parsed.rules[0].pattern)
        assertEquals("p{margin:0}", parsed.customBookCss)
        assertEquals(false, parsed.customBookCssEnabled)
    }

    @Test
    fun `missing fields are skipped not cleared`() {
        val parsed = DesktopConfigPrefs.parseDesktopConfig("{\"unrelated\":\"{}\"}")
        assertTrue(parsed.rules.isEmpty())
        assertEquals("", parsed.customBookCss)
        assertEquals(false, parsed.customBookCssEnabled)
    }

    @Test
    fun `null or blank config yields empty prefs`() {
        assertTrue(DesktopConfigPrefs.parseDesktopConfig(null).rules.isEmpty())
        assertTrue(DesktopConfigPrefs.parseDesktopConfig("").rules.isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败（本机无 JDK17 时在报告注明，CI 验红→绿）**

Run: `cd android && ./gradlew :engine:feature:test --tests "*DesktopConfigPrefsTest*"`
Expected（有 JDK17 时）: 编译错误 DesktopConfigPrefs 不存在；本机无 JDK17 → 记录「CI 验红」。

- [ ] **Step 3: 实现 DesktopConfigPrefs.kt**

```kotlin
package com.koodoreader.engine.feature

/** Flat key→JSON-string entries the desktop restore replays via ConfigService.setItem. */
data class DesktopPrefs(
    val rules: List<TextRule>,
    val customBookCss: String,
    val customBookCssEnabled: Boolean,
    val warnings: List<String> = emptyList(),
)

object DesktopConfigPrefs {

    const val KEY_READER_CONFIG = "readerConfig"
    const val KEY_CUSTOM_CSS = "customBookCSS"
    const val KEY_CUSTOM_CSS_ENABLED = "isCustomBookCSS"

    /** Values are JSON STRINGS (desktop ConfigService stores strings, not nested objects). */
    fun desktopConfigEntries(
        rules: List<TextRule>,
        customBookCss: String,
        customBookCssEnabled: Boolean,
    ): Map<String, String> {
        val envelope = TextRuleJson.exportConfig(rules)
        val parsed = TextRuleJson.parse(envelope) as JsonValue.Obj
        val fields = LinkedHashMap<String, String>()
        fields[TextRuleJson.KEY_RULE_LIST] =
            TextRuleJson.render(parsed.fields.getValue(TextRuleJson.KEY_RULE_LIST))
        fields[TextRuleJson.KEY_RULES] =
            TextRuleJson.render(parsed.fields.getValue(TextRuleJson.KEY_RULES))
        fields[KEY_READER_CONFIG] = TextRuleJson.render(
            JsonValue.Obj(linkedMapOf(
                KEY_CUSTOM_CSS to JsonValue.Str(customBookCss),
                KEY_CUSTOM_CSS_ENABLED to JsonValue.Str(if (customBookCssEnabled) "yes" else "no"),
            )),
        )
        return fields
    }

    /** Missing/invalid fields are skipped with a warning — never cleared silently. */
    fun parseDesktopConfig(configJson: String?): DesktopPrefs {
        if (configJson.isNullOrBlank()) return DesktopPrefs(emptyList(), "", false)
        val root = runCatching { TextRuleJson.parse(configJson) as? JsonValue.Obj }.getOrNull()
            ?: return DesktopPrefs(emptyList(), "", false, listOf("config.json is not an object"))
        val warnings = mutableListOf<String>()
        fun stringField(key: String): String? = (root.fields[key] as? JsonValue.Str)?.value
        val rulesPart = buildString {
            val ids = stringField(TextRuleJson.KEY_RULE_LIST) ?: return@buildString
            val map = stringField(TextRuleJson.KEY_RULES) ?: return@buildString
            append("{\"").append(TextRuleJson.KEY_RULE_LIST).append("\":").append(ids)
            append(",\"").append(TextRuleJson.KEY_RULES).append("\":").append(map).append('}')
        }
        val imported = if (rulesPart.isEmpty()) {
            TextRuleJson.ImportResult(emptyList(), emptyList())
        } else {
            runCatching { TextRuleJson.importRules(rulesPart) }.getOrElse {
                warnings += "textRules parse failed: ${it.message}"
                TextRuleJson.ImportResult(emptyList(), emptyList())
            }
        }
        warnings += imported.warnings
        var css = ""
        var enabled = false
        val rcText = stringField(KEY_READER_CONFIG)
        if (rcText != null) {
            runCatching {
                val rc = TextRuleJson.parse(rcText) as JsonValue.Obj
                css = (rc.fields[KEY_CUSTOM_CSS] as? JsonValue.Str)?.value.orEmpty()
                enabled = (rc.fields[KEY_CUSTOM_CSS_ENABLED] as? JsonValue.Str)?.value == "yes"
            }.onFailure { warnings += "readerConfig parse failed: ${it.message}" }
        }
        return DesktopPrefs(imported.rules, css, enabled, warnings)
    }
}
```

注意：`TextRuleJson` 的 `parse`/`render`/`JsonValue` 可见性若不是 public，先在同模块内把它们提为 `internal`（同模块可访问），并在报告中注明；不要另写 JSON 解析器。

- [ ] **Step 4: 测试转绿（CI）+ Commit**

```bash
git add android/engine/feature/src/main/kotlin/com/koodoreader/engine/feature/DesktopConfigPrefs.kt \
        android/engine/feature/src/test/kotlin/com/koodoreader/engine/feature/DesktopConfigPrefsTest.kt \
        android/engine/feature/src/main/kotlin/com/koodoreader/engine/feature/TextRuleJson.kt
git commit -m "feat(android): DesktopConfigPrefs 桌面 config.json 双向映射"
```

---

### Task 2: `:core:dbio` BackupBundle 捕获/写入 koodo-android.json

**Files:**
- Modify: `android/core/dbio/src/main/kotlin/com/koodoreader/core/dbio/BackupBundle.kt`（readZip :193-258、readDir :260-290、write :334-392）
- Test: `android/core/dbio/src/test/kotlin/com/koodoreader/core/dbio/BackupBundleTest.kt`

**Interfaces:**
- Consumes: 既有 `BundleOpen`、`BackupBundle.write`
- Produces: `BundleOpen.androidPrefsJson: String?`；`BackupBundle.write(..., androidPrefsJson: String = "")`（新默认参数，旧调用不变）

- [ ] **Step 1: 失败测试**

在 BackupBundleTest.kt 追加：
```kotlin
    @Test
    fun `android prefs entry round trips and desktop config is untouched`() {
        val out = tempDir.resolve("with-android-prefs.zip")
        BackupBundle.write(
            out,
            mapOf("books" to listOf<Row>()),
            configJson = "{\"textRuleList\":\"[]\"}",
            androidPrefsJson = "{\"version\":1,\"feature\":{\"fontScale\":\"1.2\"}}",
        )
        BackupBundle.open(out).use { bundle ->
            assertEquals("{\"textRuleList\":\"[]\"}", bundle.configJson)
            assertEquals("{\"version\":1,\"feature\":{\"fontScale\":\"1.2\"}}", bundle.androidPrefsJson)
        }
        val plain = tempDir.resolve("plain.zip")
        BackupBundle.write(plain, mapOf("books" to listOf<Row>()))
        BackupBundle.open(plain).use { assertNull(it.androidPrefsJson) }
    }
```

- [ ] **Step 2: 实现三处**

1. `BundleOpen` 构造参数加 `val androidPrefsJson: String? = null`（放 configJson 旁）。
2. `readZip` 循环的 `when` 增分支（放在 `name == "config/config.json"` 之后）：
```kotlin
                    name == "config/koodo-android.json" -> {
                        androidPrefsJson = archive.readBytes(name).decodeToString()
                    }
```
循环前声明 `var androidPrefsJson: String? = null`，构造 `BundleOpen(...)` 传入。`readDir`：读 `File(realRoot, "koodo-android.json")` 同样传入。
3. `write` 签名加 `androidPrefsJson: String = ""`，在 syncJson 写入块之后加：
```kotlin
                    if (androidPrefsJson.isNotEmpty()) {
                        zos.putNextEntry(ZipEntry("config/koodo-android.json"))
                        zos.write(androidPrefsJson.toByteArray())
                        zos.closeEntry()
                    }
```

- [ ] **Step 3: 本机 Node 守卫 + Commit**

Run: `node scripts/check-dbio-ddl.js && node scripts/smoke-zip-restore.js`
Expected: 全绿（dbio 桌面兼容面无变化）。JVM 测试归 CI。

```bash
git add android/core/dbio/src/main/kotlin/com/koodoreader/core/dbio/BackupBundle.kt android/core/dbio/src/test/kotlin/com/koodoreader/core/dbio/BackupBundleTest.kt
git commit -m "feat(android): 备份 zip 增加 config/koodo-android.json 通道"
```

---

### Task 3: `:app` 导出接线（ReaderFeaturePrefs → config.json）

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/DesktopBridge.kt:125-156`（exportBackup）
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/DesktopPrefsBridge.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BackupViewModel.kt`（exportBackup 调用点）
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/DesktopPrefsBridgeTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `DesktopConfigPrefs.desktopConfigEntries`；`ReaderFeaturePrefs.load(): ReaderFeatureState`；`ReaderFeatureCodec.toMap(state): Map<String,String>`；`ReaderGesturePrefs`（PREFS_NAME="reader_gestures"，其 Codec——先读该文件确认编解码函数名）
- Produces:
  - `object DesktopPrefsBridge { fun exportConfigJson(state: ReaderFeatureState): String; fun exportAndroidPrefsJson(context-free maps: Map<String,String>, gestures: Map<String,String>): String }`
  - `DesktopBridge.exportBackup(..., configJson: String = "{}", androidPrefsJson: String = "")`

- [ ] **Step 1: 失败测试（:app JVM）**

```kotlin
class DesktopPrefsBridgeTest {
    @Test
    fun `export embeds rules and readerConfig as flat strings`() {
        val state = ReaderFeatureState(
            textRulesJson = "[{\"id\":\"1\",\"type\":\"replace\",\"pattern\":\"foo\",\"matchType\":\"plain\",\"replacement\":\"bar\",\"enabled\":true}]",
            customBookCss = "h1{color:red}",
            customBookCssEnabled = true,
        )
        val json = DesktopPrefsBridge.exportConfigJson(state)
        assertTrue(json.contains("\"textRuleList\""))
        assertTrue(json.contains("readerConfig"))
        assertTrue(json.contains("isCustomBookCSS"))
    }
}
```

- [ ] **Step 2: DesktopPrefsBridge.kt**

```kotlin
package com.koodoreader.reader.shell

import com.koodoreader.engine.feature.DesktopConfigPrefs
import com.koodoreader.engine.feature.TextRuleJson

/** ReaderFeatureState → desktop backup config.json（扁平 key→JSON 字符串）。 */
object DesktopPrefsBridge {

    fun exportConfigJson(state: ReaderFeatureState): String {
        val rules = if (state.textRulesJson.isBlank()) emptyList()
        else TextRuleJson.importRules(state.textRulesJson).rules
        val entries = DesktopConfigPrefs.desktopConfigEntries(
            rules, state.customBookCss, state.customBookCssEnabled,
        )
        return entries.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${TextRuleJson.render(com.koodoreader.engine.feature.JsonValue.Str(v))}"
        }
    }

    /** 安卓私有偏好：带版本，桌面 restore 静默跳过此条目。 */
    fun exportAndroidPrefsJson(feature: Map<String, String>, gestures: Map<String, String>): String =
        buildString {
            append("{\"version\":1,\"feature\":")
            append(TextRuleJson.render(com.koodoreader.engine.feature.JsonValue.Obj(
                feature.mapValues { com.koodoreader.engine.feature.JsonValue.Str(it.value) }.toMap(LinkedHashMap()),
            )))
            append(",\"gestures\":")
            append(TextRuleJson.render(com.koodoreader.engine.feature.JsonValue.Obj(
                gestures.mapValues { com.koodoreader.engine.feature.JsonValue.Str(it.value) }.toMap(LinkedHashMap()),
            )))
            append('}')
        }
}
```

- [ ] **Step 3: DesktopBridge.exportBackup 参数化**

签名加 `configJson: String = "{}", androidPrefsJson: String = ""`，:154 改为：
```kotlin
        BackupBundle.write(out, tables, configJson, covers, bookFiles, syncJson = "", androidPrefsJson = androidPrefsJson)
```
（注意 write 的参数顺序：`out, tables, configJson, covers, bookFiles, fontFiles, syncJson, androidPrefsJson`——用命名参数避免错位。）

- [ ] **Step 4: BackupViewModel 调用点注入**

在 exportBackup 的调用处：
```kotlin
    val featureState = ReaderFeaturePrefs(context).load()
    // gestures 的 map：读 ReaderGesturePrefs 对应 Codec 的 toMap（先读文件确认函数名，
    // 例如 ReaderGestureConfig.Codec.toMap(prefs.load())；若无 toMap 则 gestures 传 emptyMap()
    // 并在报告中注明留给后续补齐）
    DesktopBridge.exportBackup(
        outDir = ..., db = ..., coverDir = ..., timestamp = ..., booksDir = ...,
        configJson = DesktopPrefsBridge.exportConfigJson(featureState),
        androidPrefsJson = DesktopPrefsBridge.exportAndroidPrefsJson(
            ReaderFeatureCodec.toMap(featureState), gestureMap,
        ),
    )
```

- [ ] **Step 5: Commit（CI 验编译+测试）**

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/DesktopPrefsBridge.kt \
        android/app/src/main/java/com/koodoreader/reader/shell/DesktopBridge.kt \
        android/app/src/main/java/com/koodoreader/reader/shell/BackupViewModel.kt \
        android/app/src/test/java/com/koodoreader/reader/shell/DesktopPrefsBridgeTest.kt
git commit -m "feat(android): 导出备份携带规则/CSS/阅读偏好（M4 出口）"
```

---

### Task 4: `:app` 导入接线 + 回归文档

**Files:**
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/DesktopBridge.kt:64-…`（importBackup 尾部）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BackupViewModel.kt`（importBackup 调用点）
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/DesktopPrefsBridge.kt`
- Modify: `docs/android-user-guide.md`

**Interfaces:**
- Consumes: Task 1 `DesktopConfigPrefs.parseDesktopConfig`；Task 2 `bundle.androidPrefsJson`；`ReaderFeaturePrefs.save(state)`
- Produces: `DesktopPrefsBridge.applyDesktopConfig(configJson: String?, current: ReaderFeatureState): ReaderFeatureState`；`parseAndroidPrefsVersion(json: String?): Int?`

- [ ] **Step 1: 导入语义函数**

DesktopPrefsBridge 追加：
```kotlin
    /** 桌面 config.json → 合并进当前状态；缺字段不动现状；非法规则跳过并保留其余。 */
    fun applyDesktopConfig(configJson: String?, current: ReaderFeatureState): ReaderFeatureState {
        val parsed = DesktopConfigPrefs.parseDesktopConfig(configJson)
        if (configJson.isNullOrBlank()) return current
        var next = current
        if (parsed.rules.isNotEmpty()) {
            next = next.copy(textRulesJson = TextRuleJson.exportRules(parsed.rules))
        }
        if (parsed.customBookCss.isNotEmpty() || parsed.customBookCssEnabled) {
            next = next.copy(
                customBookCss = parsed.customBookCss,
                customBookCssEnabled = parsed.customBookCssEnabled,
            )
        }
        return next
    }

    /** 返回版本号；不可解析/缺失返回 null（调用方据此拒绝导入并提示）。 */
    fun parseAndroidPrefsVersion(json: String?): Int? {
        if (json.isNullOrBlank()) return null
        val root = runCatching {
            TextRuleJson.parse(json) as com.koodoreader.engine.feature.JsonValue.Obj
        }.getOrNull() ?: return null
        return (root.fields["version"] as? com.koodoreader.engine.feature.JsonValue.Num)?.value?.toInt()
    }
```

- [ ] **Step 2: importBackup 回调**

`DesktopBridge.importBackup` 签名加 `onConfigJson: (String?) -> Unit = {}` 与 `onAndroidPrefsJson: (String?) -> Unit = {}`，在 `BackupBundle.open(source).use { ... }` 块末尾（表导入完成后）加：
```kotlin
            onConfigJson(bundle.configJson)
            onAndroidPrefsJson(bundle.androidPrefsJson)
```

- [ ] **Step 3: BackupViewModel 导入侧应用**

```kotlin
        onConfigJson = { json ->
            val current = ReaderFeaturePrefs(context).load()
            ReaderFeaturePrefs(context).save(DesktopPrefsBridge.applyDesktopConfig(json, current))
        },
        onAndroidPrefsJson = { json ->
            if (json != null) {
                when (DesktopPrefsBridge.parseAndroidPrefsVersion(json)) {
                    1 -> { /* 用 ReaderFeatureCodec.fromMap(feature) 与手势 Codec 恢复；未知键跳过 */ }
                    null -> AppLog.w(TAG, "koodo-android.json 不可解析，跳过私有偏好恢复")
                    else -> AppLog.w(TAG, "koodo-android.json 版本未知，拒绝导入私有偏好")
                }
            }
        },
```
（AppLog 的引用以该文件现有日志方式为准；恢复实现细节以 ReaderFeatureCodec.fromMap/手势 Codec 的实际签名为准，写测试覆盖 version 三分支。）

- [ ] **Step 4: 用户文档更新**

`docs/android-user-guide.md` 备份章节补：导出 zip 含净化规则（textRuleList/textRules）、自定义 CSS（readerConfig.customBookCSS/isCustomBookCSS）、安卓私有阅读偏好（config/koodo-android.json，桌面忽略）；导入桌面备份时同名字段自动恢复。

- [ ] **Step 5: 本机守卫 + Commit**

Run: `node scripts/smoke-zip-restore.js && node scripts/check-dbio-ddl.js`（期望绿）

```bash
git add android/app/src/main/java/com/koodoreader/reader/shell/ \
        docs/android-user-guide.md
git commit -m "feat(android): 导入备份恢复规则/CSS/私有偏好（M4 入口）"
```

---

## Self-Review 记录

- 规格覆盖：M4 双向流动（导出 Task 3 / 导入 Task 4）、koodo-android.json 通道（Task 2）、字段映射（Task 1）、user-guide（Task 4）；「缺字段不清空」「未知版本拒绝」均有函数与测试点。
- 类型一致性：Task 1 产出 `DesktopConfigPrefs.desktopConfigEntries/parseDesktopConfig` 被 Task 3/4 消费；`JsonValue.Str/Obj/Num` 的 render/parse 用法以 Task 1 的可见性处理为准。
- 已核实桌面格式：扁平 key→JSON 字符串（min lib setItem+JSON.stringify 与 restore.ts:96 交叉验证）。
