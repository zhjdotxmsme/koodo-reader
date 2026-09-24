# P6：划词翻译 / AI 助手（`android/feature/translate`）

| 项 | 值 |
|---|---|
| 任务卡 | `t-muexn696-xzgcrk`（P6：划词翻译 / AI（feature/translate）） |
| 隔离 | git worktree `task/p6-translate-xzgcrk` |
| 模块 | 新增 `android/feature/translate/`（Android library，`com.android.library` + Kotlin + KSP/Room + Compose） |
| 规模 | 主源码 14 个 `.kt`（2127 行）；单测 9 个 `.kt`（1301 行，78 个 `@Test`） |
| 状态 | 实现完成；`settings.gradle` 注册与 `:app` 接线留给主线程（本卡禁止改这三个 gradle 文件） |

---

## 1. 范围

桌面端参考：`src/utils/plugins/renderer/translation/`（25 个翻译插件）、
`src/utils/request/aiBridge.ts`。

本卡按描述**只把 3 个核心翻译源内置为 feature**，其余 22 个插件源（百度/腾讯/火山/有道/ollama/…）
与 15 个 voice 插件保持桌面独占：**不实现插件注册表**、不做 JS 桥、不做插件市场。

交付四项（对应验收清单 4 条）：

| 验收项 | 实现位置 |
|---|---|
| ① 划词翻译弹窗 + 多源切换 | `TranslationPopup.kt`（状态机/控制器）+ `TranslationPopupUi.kt`（Compose 表面）+ `TranslationProvider.kt` 的 `ProviderSelector` |
| ② 翻译源配置与凭据安全存储（不落 info 日志） | `CredentialsStore.kt` + `RedactedLogger.kt` + `EncryptedSecretStore.kt` |
| ③ AI 助手接入路径（摘要/问答） | `AiAssistant.kt` |
| ④ 翻译历史持久化与检索 | `HistoryRepository.kt`（Room Entity/DAO/Repository）+ `TranslationHistoryDatabase.kt` |

## 2. 模块结构

```
android/feature/translate/
├── build.gradle
└── src/
    ├── main/kotlin/com/koodoreader/feature/translate/
    │   ├── MiniJson.kt                     无依赖 JSON 读写 + HTML 实体解码
    │   ├── HttpTransport.kt                请求/响应数据类、SensitiveHeaders、脱敏描述
    │   ├── RedactedLogger.kt               Logger 接口 + TokenRedaction + RedactedLogger
    │   ├── TranslationProvider.kt          Provider 接口、ProviderCredentials、语种工具、ProviderSelector
    │   ├── GoogleTranslateProvider.kt      Google Cloud Translation v2
    │   ├── MicrosoftTranslateProvider.kt   Azure/Microsoft Translator v3
    │   ├── DeepLTranslateProvider.kt       DeepL v2（free/pro）
    │   ├── CredentialsStore.kt             凭据读写 + 命名空间 key + 只读脱敏视图
    │   ├── EncryptedSecretStore.kt         EncryptedSharedPreferences 实现 SecretStore（Android）
    │   ├── TranslationPopup.kt             弹窗状态 + TranslationPopupController（StateFlow）
    │   ├── TranslationPopupUi.kt           Compose 弹窗（Android）
    │   ├── HistoryRepository.kt            Room Entity/DAO + TranslationHistoryRepository
    │   ├── TranslationHistoryDatabase.kt   Room 数据库（Android）
    │   └── AiAssistant.kt                  AiModelConfig + 提示词 + 摘要/问答
    └── test/kotlin/com/koodoreader/feature/translate/   ← 8 个测试类 + 测试替身
```

设计基线：**除 3 个 Android 专属文件**（`EncryptedSecretStore.kt`、`TranslationPopupUi.kt`、
`TranslationHistoryDatabase.kt`）**外，全部为纯 Kotlin**（不 import `android.*`）。
网络、存储、DAO、时钟、ID 生成、日志全部走接口注入，因此业务逻辑可在无 SDK、无设备、
无 Room 编译器的 JVM 上验证（§7）。

## 3. 三个内置翻译源（桌面字段级对齐）

| 源 | 插件 key（桌面同值） | 端点默认值 | 凭据位置 | 请求体 | 响应路径 |
|---|---|---|---|---|---|
| Google | `google-translate-plugin` | `https://translation.googleapis.com/language/translate/v2` | 查询参数 `key` | `{"q":…,"target":…,"format":"text"[,"source":…]}` | `data.translations[0].translatedText`（HTML 实体解码）+ `detectedSourceLanguage` |
| Microsoft | `azure-translate-plugin` | `https://api.cognitive.microsofttranslator.com` | 头 `Ocp-Apim-Subscription-Key`（+ 可选 `Ocp-Apim-Subscription-Region`） | `[{"text":…}]` | `[0].translations[0].text` + `detectedLanguage.language` |
| DeepL | `deepl-translate-plugin` | free：`https://api-free.deepl.com/v2/translate`；pro：`https://api.deepl.com/v2/translate` | 头 `Authorization: DeepL-Auth-Key …` | `{"text":[…],"target_lang":…[,"source_lang":…]}`（大写标签） | `translations[0].text` + `detected_source_language` |

与桌面差异（有意为之）：

* 桌面 Google 插件用 `<textarea>` 解 HTML 实体；这里用 `HtmlEntities.unescape`（纯 JVM，`&amp;`/`&#39;`/`&#x4F60;`）。
* 桌面 `azureTranslate.ts` 的 `to`/`from` 直接透传；这里 `from=Automatic/auto` 时**不发送** `from` 参数（与插件等价，且避免 Azure 400）。
* DeepL 语种标签：桌面透传，这里按 API 要求转大写并做 `zh` → `ZH` / `ZH-HANT` 映射。
* 语种相同（`from == to` 且非 auto）时不发请求直接回原文（对齐桌面 Google 插件短路行为）。
* 错误映射统一为 `TranslationOutcome.Failure(reason=EMPTY_INPUT | MISSING_CREDENTIALS | NETWORK | HTTP_STATUS | MALFORMED_RESPONSE)`，UI 只渲染 `detail`。

`ProviderSelector` 负责“多源切换”：注册顺序、当前源、`next()` 轮换、
`resolve(configured)`（当前源无凭据 → 退到第一个有凭据的源 → 都没有则保持当前源以便提示配置）。

## 4. 凭据存储与日志安全（CLAUDE.md 强规则）

存储链路：`CredentialsStore` → `SecretStore` 接口 → `EncryptedSecretStore`
（`androidx.security:security-crypto` 的 **EncryptedSharedPreferences**：键 AES-256-SIV、
值 AES-256-GCM、主密钥在 Android Keystore；`minSdk 24` 满足要求）。
存储 key 带命名空间：`p6.translate.cred.<pluginKey>.{apiKey,endpoint,region,plan}` —
桌面插件 id 即迁移键。

**token 不落 info 级日志由构造保证，而不是靠约定：**

1. `CredentialsStore` 自己把调用方给的 logger 包成 `RedactedLogger`，并把**自己的**
   `knownTokens()`（save/load/扫描到的全部密钥）作为脱敏词表；对外只暴露 `store.logger`，
   生产代码想记录配置信息必须走它。
2. `TokenRedaction` 双重防线：**值匹配**（逐字替换已见密钥）+ **形状匹配**
   （`apiKey=…`、`Authorization: Bearer …`、`sk-…`、`AIza…`、长 base64 块）。
   即使某个密钥本进程从未见过，形状规则也会把它抹掉。
3. UI/日志只暴露 `maskedApiKey()`（`***` + 末 4 位）与 `describe()`；
   `HttpRequest.redactedDescription()` 屏蔽敏感头与 `?key=…` 查询参数，且**不打印 body**。
4. 单测硬断言（`RedactedLoggerTest`，生产路径，非测试替身）：
   * `store.save(...)` 后，模拟“粗心调用点”把 token 插进 `info/warn/error` 三行 — 断言日志文本
     **不含完整 token**，且断言日志确实写出了（避免空断言假通过）；
   * 进程重启场景（密钥已在存储里、本实例未 save）同样不泄漏；
   * 未见过的密钥靠形状规则抹掉；
   * `mask()` 只留末 4 位；历史/弹窗/AI 的 info 行同样断言无 token；
   * `describe()`、`redactedDescription()` 无 token、无正文。

已知边界：`Throwable` 自带的 message 无法改写（Java 异常不可变），因此网络层把 IO 异常
统一转成 `HttpTransportException(免密消息)`；这一限制写在 `RedactedLogger.error` 的注释里。

## 5. 翻译历史持久化与检索（Room）

`HistoryRepository.kt` 定义 `TranslationHistoryEntity` / `TranslationHistoryDao` /
`TranslationHistoryRepository`：

* 表 `translation_history`，列名沿用 `core/data` 风格（camelCase 引号列）：
  `key`(PK) / `sourceText` / `translatedText` / `sourceLang` / `targetLang` / `provider` /
  `bookKey` / `cfi` / `createdAt`，索引 `createdAt`、`bookKey`、`provider`。
  桌面把翻译结果放在插件缓存里，SQLite 无对应表 → 该表为 Android 自有 schema。
* `key` = `SHA-256("provider|target|text")` 前 32 hex（带插件前缀）：同一段落同一目标语言
  重复翻译是**更新**而非新增，且 key 本身不含正文，可安全记录/导出。
* 检索：`recent(limit)`（按 `createdAt DESC`）、`search(q)`（`LIKE '%q%'` 同时匹配原文与译文）、
  `forBook(bookKey)`；`matches()` 提供与 SQL 同语义的内存匹配器供测试。
* 保留策略：`maxEntries`（默认 500），`record()` 超限即 `prune()`；`prune(keep)` 用
  `ORDER BY createdAt DESC LIMIT -1 OFFSET :keep` 取超龄行再删除。
* 日志只记 `provider/target/字符数`，**不记选中的正文**（单测断言）。

数据库装配（`TranslationHistoryDatabase`）默认独立成库
（`koodo_translation_history.db`），这样本卡无需改 `core/data` 的 `KoodoDatabase`（P1 数据层，
本卡禁改）。合并路径已留好：`KoodoDatabase` 是 Room 数据库，把
`TranslationHistoryEntity` 加进它的 `entities` 并把版本 +1、补一条同样 DDL 的 `Migration`
即可，DAO 在两个库里都成立。

## 6. AI 助手接入路径

* `AiModelConfig(endpoint, providerId, apiKey, modelId)` 与桌面 `AiModelConfig` 字段一致；
  `endpoint` 去尾斜杠后拼 `/chat/completions`（OpenAI 兼容），Bearer 鉴权，
  `{"model":…,"stream":false,"messages":[{"role":"user","content":…}]}`。
* `AiConfigKeys` 保留桌面 reader-config 键与优先级（`aiTranslateModel` → `aiDictModel` →
  `aiAssistanceModel`），便于桌面配置导入。
* 两个入口：`summarize(text, language, maxSentences=5)`（章节摘要）与
  `ask(question, context)`（基于选中/章节上下文问答）；提示词模板 `AiPrompts` 与桌面
  `aiBridge.ts` 的措辞保持一致（“只用给定上下文，不知道就说不知道，纯文本输出”）。
* 返回 `AiOutcome.Success | NotConfigured | Failure(reason,detail)`；未配置模型时
  UI 显示“请先配置 AI 服务”，与桌面同一语义。
* `parseCompletion` 同时兼容 `choices[0].message.content` 与旧式 `choices[0].text`。
* 后续（不在本卡）：桌面 `chatStream` 的 SSE 逐字流式；届时在 `HttpTransport` 上增加
  流式接口，`AiAssistant` 的提示词与结果契约不变。
* 真实 HTTP 实现（OkHttp/HttpURLConnection）与 `android.util.Log` 版 Logger 由 `:app` 提供，
  本模块只声明 [HttpTransport]/[Logger] 两个 seam。

## 7. 验证

### 7.1 已执行的 JVM 验证（本环境）

本 sandbox 禁止写 `~/.gradle`（Gradle 无法解出 `native-platform.dll`），
且本卡禁止改 `android/settings.gradle`，因此**没有**、也不能用 `gradlew` 跑测试。
替代方案 = 一次性纯 JVM 验证（临时目录，不入库）：

```powershell
# 1) 用缓存里的 kotlinc 编译「纯 Kotlin 源码 + 全部单测」
#    排除 EncryptedSecretStore.kt / TranslationPopupUi.kt / TranslationHistoryDatabase.kt
java -cp "<kotlin-compiler-embeddable;stdlib;script-runtime;trove4j;reflect;annotations>" `
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
  -classpath "<kotlin-stdlib;room-common-2.6.1;kotlinx-coroutines-core-jvm-1.7.1;annotation;junit-4.13.2;hamcrest-core-1.3>" `
  -jvm-target 17 -d <out> <main sources…> <test sources…>
# 2) 运行
java -cp "<out;deps>" org.junit.runner.JUnitCore `
  MiniJsonTest RedactedLoggerTest CredentialsStoreTest TranslateProvidersTest `
  ProviderSelectorTest TranslationPopupControllerTest AiAssistantTest HistoryRepositoryTest
```

结果（本次）：`kotlinc exit=0`；`OK (78 tests)`。

补充检查：把**全部 14 个主源码**（含 3 个 Android 专属文件）对
`platforms/android-34/android.jar + room-common + coroutines` 编译 →
`kotlinc exit=1`，但 **115 条报错全部是 `unresolved reference`，且全部指向缺失的 Android 依赖**
（Compose 的 `Column/Row/Text/Surface/TextButton/MaterialTheme/…`、
`androidx.security.crypto.{EncryptedSharedPreferences,MasterKey}`、
`androidx.room.{Room,RoomDatabase}`）。**零语法错误、零类型错误、零“overrides nothing”**，
说明这三个文件在补齐依赖后可编译。

### 7.2 接入后应跑的（主线程）

```bash
# 注册模块后（settings.gradle 加 include ':feature:translate'）
./gradlew :feature:translate:testDebugUnitTest     # AGP 默认 JUnit4 runner，无需额外插件
./gradlew :feature:translate:assembleDebug
```

单测覆盖（78 个）：JSON 解析/写体/实体解码 6、脱敏日志 6、凭据存储 7、三源 wire 格式 17、
源选择 9、弹窗与历史联动 12、AI 助手 9、历史仓储（去重/检索/保留/清空）12。

## 8. 集成步骤（留给主线程，本卡禁改）

1. `android/settings.gradle`：`include ':feature:translate'`（与 `:engine:*` 同段注释风格）。
2. `android/app/build.gradle`：`implementation project(':feature:translate')`。
3. `:app` 提供两个 seam 实现：`HttpTransport`（OkHttp/HttpURLConnection，负责把 IO 异常
   转成 `HttpTransportException`）与 `Logger`（`android.util.Log`，按级别分流）。
4. 生产装配：
   `CredentialsStore(EncryptedSecretStore(context), appLogger)`；
   `TranslationPopupController(ProviderSelector(listOf(GoogleTranslateProvider(), MicrosoftTranslateProvider(), DeepLTranslateProvider())), credentialsStore, transport, logger, TranslationHistoryRepository(TranslationHistoryDatabase.get(context).historyDao()))`。
5. 需要网络下载的新依赖：`androidx.security:security-crypto:1.1.0-alpha06`
   （Compose BOM / Room 2.6.1 / coroutines 1.7.1 仓库里已用过）。
6. 可选：把 `TranslationHistoryEntity` 合并进 `KoodoDatabase`（§5），或保持独立库。
7. 设置页翻译源表单可直接用 `TranslationProvider.credentialFields` 渲染，
   回显用 `CredentialsStore.maskedApiKey()`。

## 9. 边界与未做

* 不做插件注册表、不做 22 个桌面独占插件源、不做 voice 插件（按卡面要求）。
* 不做设置页 UI 与历史列表页 UI（本卡交付弹窗表面 + 状态机；设置/历史列表属 `:app` 设置与库页）。
* 不做 SSE 流式 AI、不做整书翻译编排（`aiTranslateTexts` 的 8 段分块逻辑属后续卡片）。
* 用户可见文案未硬编码：`TranslationPopupLabels.from(localization::t)` 使用桌面同键
  （`Translate` / `Translation failed` / `Copy` / `Close` / `AI service`），
  由 `core:common` 的 `Localization` 解析。
* 未触碰禁改文件：`android/app/build.gradle`、`android/settings.gradle`、
  `android/build.gradle`、`package.json`、`main.js`（worktree 内 `git status` 可复核）。
