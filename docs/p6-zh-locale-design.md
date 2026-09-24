# P6 设计：简繁转换（OpenCC 移植）+ 其余 39 locale 按需加载

- 日期：2026-09-24
- 状态：实现完成，待接线与验收（见 §8）
- 关联：**ADR-004**（安卓 i18n 子集策略，en+zh-CN 打包、回退链 所选→en→key、其余 39 locale 推迟到 P6+）、ADR-003（兜底岛）、任务卡 `t-muexn6d1-rg3pyr`
- 隔离：git worktree `.worktrees/p6-zh-locale`，分支 `task/p6-zh-locale-p6`
- 桌面参考：`kookit zh-convert.ts`（`docs/android-loc-baseline.json`：8161 行 / 8143 代码行）——**源码不可读**（`src/assets/lib/kookit-extra.min.mjs` 是压缩产物，且本机无 `D:\Project\kookit`），因此按任务描述**原生移植 OpenCC 模型**（词表驱动 + MaxMatch 分词），并以桌面**配置键与取值**对齐语义。

## 1. 交付物与验收对照

| 验收项 | 交付物 | 位置 |
|---|---|---|
| ① 简繁转换引擎（自动/繁/简三态） | `ZhConvertEngine`、`OpenCcDictionary`（trie MaxMatch）、`OpenCcSeed`（517 字 + 69 短语 + 67 台湾词 + 7 繁→简补充） | `android/core/locale/src/main/kotlin/.../` |
| ② 转换字典持久化与桌面互通 | `ZhConvertSettings`、`ZhConvertPrefsCodec`（键 `convertChinese` 与桌面一致）、`ZhConvertSettingsStore` 端口 + `ZhConvertSettingsRepository` | 同上（§4） |
| ③ 运行时 locale 按需加载 | `LocaleRuntimeLoader`（39 locale）、`LocaleCatalogRegistry`、`LocaleAssetSource`/`LocalePackStore`/`LocalePackDownloader` 端口 + `FilesLocalePackStore` | 同上（§5） |
| ④ 语言切换持久化 + 回退链不变 | `LocaleFallbackChain`、`FallbackKey`、`LocaleCodes`（键 `language` 沿用 `LibraryPrefs`） | 同上（§6） |
| ⑤ 守卫覆盖新方案 | `scripts/check-locales.mjs`（A 段与 `sync-locales-android.js --check` 等价；B–E 段为 P6 新增守卫） | `scripts/`（§7） |
| 单测 | 5 个 JVM 测试类 + 1 个无框架 self-check（`:core:locale:selfCheck`） | `src/test/kotlin/.../` |

## 2. 模块结构

`android/core/locale` 是**纯 Kotlin JVM 模块**（同 `:core:common` / `:core:designsystem` 的理由：可离线单测、无 Android 依赖）：

```
android/core/locale/
├── build.gradle                     # kotlin-jvm + :core:common + junit5 + selfCheck
└── src/
    ├── main/
    │   ├── assets/locales/.gitkeep   # 预留：全量 OpenCC 词表/额外 locale 打包位
    │   └── kotlin/com/koodoreader/core/locale/
    │       ├── OpenCcDictionary.kt    # OpenCC 文件格式解析 + trie + MaxMatch + 链式应用
    │       ├── OpenCcSeed.kt          # 精选词表（OpenCC 上游格式，可整体替换）
    │       ├── ZhConvertEngine.kt     # 三态引擎 / 方向链 / 脚本判定 / 用户词典
    │       ├── ZhConvertSettings.kt   # 持久化模型 + Preferences 编解码 + 端口 + 仓库
    │       ├── LocalePackStore.kt     # 端口 + 内存/文件系统实现 + SHA-256
    │       ├── LocaleRuntimeLoader.kt # 39 locale 按需加载 + 注册表 + 校验器
    │       ├── LocaleFallbackChain.kt # 所选→en→key（可观测）
    │       └── FallbackKey.kt         # 翻译键类型 + locale 常量
    └── test/kotlin/com/koodoreader/core/locale/
        ├── ZhConvertEngineTest.kt        # 三态/方向/判定/用户词典
        ├── OpenCcDictionaryTest.kt       # 格式/MaxMatch/反向/种子完整性
        ├── LocaleRuntimeLoaderTest.kt    # 打包/下载/校验/持久化/LRU
        ├── LocaleFallbackChainTest.kt    # 回退链 + 与 Localization/真实桌面 JSON 一致
        ├── ZhConvertSettingsTest.kt      # 编解码/桌面互通/仓库
        └── LocaleSelfCheck.kt            # 无框架自检（gradle :core:locale:selfCheck）
```

依赖只有 `:core:common`（复用 `FlatJson` 与 `Localization.normalize/FALLBACK_LANGUAGE`，保证回退链与 P1 实现不可能漂移）；`null` 之外的第三方依赖为 0，且**不使用协程**（API 同步，调用方放 `Dispatchers.IO`）。

## 3. ① 简繁转换引擎

### 3.1 桌面语义对齐（三态）

桌面 `src/constants/dropdownList.tsx`：「Conversion of Chinese」→ 配置键 `convertChinese`，取值 `""`（Default）/ `"Simplified To Traditional"` / `"Traditional To Simplified"`，持久化在 reader config（`viewer/component.tsx`、`readerSettings/dropdownList/component.tsx` 读同一键）。

| 原生三态 | 桌面 wire 值 | 行为 |
|---|---|---|
| `ZhConvertMode.AUTO`（自动） | `""` | 文本与阅读语言**同脚本**→ 原样；阅读语言非中文（如 `en`/`de`）→ **逐字节 no-op**（与桌面 Default 完全一致）；中文且脚本明确相反 → 转换到阅读语言脚本 |
| `ZhConvertMode.TRADITIONAL`（繁） | `Simplified To Traditional` | 强制 简→繁；`zh-TW/zh-HK/zh-MO` 阅读语言下再走台湾用词链 |
| `ZhConvertMode.SIMPLIFIED`（简） | `Traditional To Simplified` | 强制 繁→简；台湾文本按 `zh-*` 决定是否先做 `TWPhrases` 反查 |

`ZhConvertPrefsCodec.KEY_MODE == "convertChinese"`，`ZhConvertMode.fromWire()` 兼容桌面取值（未知值退化为 AUTO，即桌面默认）。

### 3.2 OpenCC 链条与算法

与上游 OpenCC 配置等价（`MaxMatchSegmentation` + 顺序转换）：

| 方向 | 链 |
|---|---|
| `s2t` 简→繁 | `STPhrases` → `STCharacters` |
| `s2tw` 简→繁（台湾） | `STPhrases` → `STCharacters` → `TWPhrases` |
| `t2s` 繁→简 | `STPhrasesRev` → `STCharactersRev` |
| `tw2s` 台湾→简 | `TWPhrasesRev` → `STPhrasesRev` → `STCharactersRev` |
| 用户词典（可选） | 置于链首，**locked**：仅其改写片段被后续阶段跳过 |

实现要点：

1. **文件格式与上游一致**：`key<TAB>value1 value2 …`，`#` 注释；多值取首值（OpenCC 默认），其余值保留供再排序。因此**上游全量词表可原样加载**（`STCharacters.txt` 约 30k 条、`STPhrases.txt` 等），无需改代码。
2. **trie + 最长匹配**：按 code point 前进，代理对（如 `U+20000`）整段通过；非命中片段逐字节保留，故拉丁文本/数字/emoji/标点永不被修改（有测试）。
3. **分段（segments）与锁定**：转换结果按 `(片段, 是否被改写)` 返回。非 locked 阶段（内置词表）与 OpenCC 一致地“整段继续参与后续阶段”；locked 阶段（用户词典）只保护**自己改写的片段**——否则一旦启用用户词典，整段都会“冻结”，内置转换全部失效（测试覆盖）。
4. **歧义字不入字表**：`干/后/里/只/台/面/划/复/准/脏/历/于/周` 由**短语阶段**消歧（`干净→乾淨`、`干活→幹活`、`干扰→干擾` 不动、`后来→後來`、`皇后` 不动、`一只→一隻`、`面条→麵條`、`计划→計劃`、`复杂→複雜`、`准备→準備`、`心脏→心臟`、`日历→日曆`、`由于→由於`）。这正是 OpenCC 的 `STPhrases` 角色。
5. **T→S 由 S→T 反演**：`reverse()` 值→键、丢弃恒等项；再加 `TS_EXTRA_CHARACTERS`（`臺/檯/颱/裏/裡/隻/髮`）覆盖“繁体独有、无简→繁对应”的字。守卫脚本强制 `ST_CHARACTERS` 保持 **1:1 且值不作为键**（否则反演丢数据/链式误转）。

### 3.3 词表规模、来源与已知缺口（诚实清单）

- 规模：**517 简→繁单字 + 69 消歧短语 + 67 台湾用词 + 7 繁→简单字**（台湾用词示例：`軟件→軟體`、`網絡→網路`、`計劃→計畫`、`打印→列印`）。全部为上游 OpenCC 词典（[BYVoid/OpenCC](https://github.com/BYVoid/OpenCC)，Apache-2.0；`STCharacters`/`STPhrases`/`TWPhrasesIT` 语义）中的**高频子集**，人工编写后由守卫脚本与单测双重校验（无重复键、无恒等规则、无链式风险、规模下限）。
- **全量词表加载路径**：`OpenCcDictionary.parse()` 直接吃上游文件；把 `STCharacters.txt`、`STPhrases.txt`、`TWPhrases.txt`、`TSCharacters.txt` 放进 `android/core/locale/src/main/assets/locales/`（或运行时随 locale 包一起下发）后，仅需把 `ZhConvertDictionaries.fromSeedTexts()` 换成读文件版本即可，引擎/接口不变。
- 已知缺口（测试**显式固定**当前行为，不隐藏）：
  1. 单字歧义（`干`、`里`、`后`、`只`、`台`、`面`、`划` 单独出现）不转换；需全量 `STPhrases` 才能覆盖（如「公里」「台风」这类词才不会误转）。
  2. `著` 不做 繁→简（否则 `著名/著作` 会被毁）；代价是 `看著` 不变 `看着`。
  3. 多值条目只取首值，不做上下文重排序（`干→乾 幹` 的首值是 `乾`；本实现把 `干` 从字表移除，改由短语处理）。
  4. 台湾链是 IT/设置语域子集（同 `TWPhrasesIT`），3 个台湾词由两个大陆词共享（`接口/界面→介面`、`缺省/默認→預設`、`屏幕/顯示器→螢幕`），反查取首见，单测固定该数量。

### 3.4 自动模式判定

`ZhConvertEngine.signal(text)` 统计“只在简体出现的字”（字表键）与“只在繁体出现的字”（字表值 ∪ 繁→简字表键），`ScriptSignal.script()` 判据：**信号 ≥ 4 且领先 ≥ 2 倍**，否则 `UNKNOWN`（不转换）。阈值常量 `MIN_SIGNAL = 4`、`DECISIVE_RATIO = 2.0`，有边界测试（`后来` → UNKNOWN；整段简体/繁体 → 正确判定）。

### 3.5 引擎依赖选择：原生移植 vs `opencc4j`

- 默认：**原生移植**（零运行时依赖，离线可构建/可测）。
- 备选：`com.github.houbb:opencc4j:1.8.1`（Maven Central，JVM 版 OpenCC 移植）。`build.gradle` 中记录在**惰性配置** `openccAlternative` 里（不参与默认编译/运行，故离线构建永不联网）：
  - 打印坐标：`./gradlew :core:locale:resolveAlternativeEngine`
  - 切换到库实现：注释掉 `openccAlternative` 行，改为 `implementation 'com.github.houbb:opencc4j:1.8.1'`（`build.gradle` 内已写明），并删除 `OpenCcDictionary`/`OpenCcSeed`（`ZhConvertEngine` 接口不变）。
- 选原生移植的理由：①不引入第三方版本/传输风险（CI 与离线环境）；②桌面侧是“简繁 + 台湾用词”这一固定链路，词典数据可审计；③便于按需打包（§5）与后续全量词表替换。

## 4. ② 转换字典持久化（DataStore）与桌面互通

### 4.1 端口与键

```kotlin
interface ZhConvertSettingsStore {          // 纯 JVM 端口
    fun read(): ZhConvertSettings
    fun write(settings: ZhConvertSettings): ZhConvertSettings
    fun clear()
}
```

| 键 | 类型 | 说明 |
|---|---|---|
| `convertChinese` | String | **桌面同名键、同取值**（`""` / `Simplified To Traditional` / `Traditional To Simplified`） |
| `zhConvertUserDict` | String | 用户转换词典，OpenCC 行格式（`key<TAB>value`）——即“转换字典”本体 |
| `zhConvertUserDictEnabled` | String `"true"/"false"` | 是否启用用户词典（关掉时仍保留在磁盘） |

`ZhConvertPrefsCodec` 是唯一知道键名的地方：DataStore、SharedPreferences、测试内存 Map 三者编解码一致（有 round-trip 与容错测试：缺键→桌面默认、未知模式串→AUTO、`" TRUE "`→启用）。

### 4.2 app 侧接线（5 行，属 §8 未接线项）

```kotlin
// android/app：androidx.datastore:datastore-preferences
val Context.zhConvertPrefs by preferencesDataStore("zh_convert")   // 独立文件，便于随备份导出

class DataStoreZhConvertSettingsStore(private val ds: DataStore<Preferences>) : ZhConvertSettingsStore {
    override fun read(): ZhConvertSettings = runBlocking {          // 调用方在 Dispatchers.IO
        ZhConvertPrefsCodec.decode(ds.data.first().asMap().mapKeys { it.key.name }.mapValues { it.value as String })
    }
    override fun write(settings: ZhConvertSettings): ZhConvertSettings { /* edit { it.clear(); it.putAll(codec) } */ return settings }
    override fun clear() { /* edit { for (k in ZhConvertPrefsCodec.KEYS) it.remove(stringPreferencesKey(k)) } */ }
}
```

`ZhConvertSettingsRepository` 负责“当前值 + 监听通知 + 出引擎”（Compose 用 `mutableStateOf` 桥接），转换模式切换即时生效；`refresh()` 从 DataStore 重读。

### 4.3 为什么 core:locale 保持纯 JVM

`androidx.datastore` 是 Android/AAR 依赖，纯 JVM 模块无法消费；且本任务**禁止修改** `android/app/build.gradle`（无法把 DataStore 依赖加到 app 侧）。因此采用“端口 + 编解码器 + 文档化接线片段”的方式交付，引擎与持久化逻辑本身已被 JVM 测试完整覆盖（§9）。这是本次最明确的**未完成接线项**，见 §8。

## 5. ③ 运行时 locale 按需加载（39 个）

### 5.1 状态机

```
ensure(code)
 ├─ 已在缓存/注册表 ─────────────────────────▶ Ready(kind=已装来源)
 ├─ APK assets（en/zh-CN）──────────────────▶ Ready(BUNDLED)
 ├─ 已安装 pack（filesDir/locales/packs）───▶ Ready(DOWNLOADED)   ← 重启后免下载
 ├─ 已知远端 + 有下载器 ───────────────────▶ Downloaded（校验 sha256 → 安装 → 注册）
 ├─ 已知远端 + 无下载器/离线 ──────────────▶ NeedsDownload(code, 期望 sha256)
 ├─ 损坏（JSON 非法/非字符串值/哈希不符）──▶ Failed（并删除损坏 pack）
 └─ 非 41 个桌面 locale ───────────────────▶ Unsupported
```

`load()` 是纯查表（绝不联网），`ensure()` 才可能下载；两者结果类型都是 sealed interface，调用方穷举处理。

### 5.2 打包 / 下载 / 校验 / 缓存

- **ADF-004 子集不变**：`BUNDLED_LOCALE_CODES = ["en","zh-CN"]`（与 `sync-locales-android.js` 的 `ANDROID_LOCALES` 由守卫脚本强制相等）。
- **39 个按需 locale**：`REMOTE_LOCALE_CODES`（守卫脚本解析并断言 == 桌面 41 − 打包 2）。
- **校验与构建期同规则**（`CatalogValidator`，等价于 `sync-locales-android.js --check`）：必须是 JSON 对象、**每个值都是字符串**（非字符串值报错，尽管桌面文件不会出现）、至少一条；此外下载包还校验 sha256（索引 vs 文件、期望 vs 实际）。
- **持久化**：`FilesLocalePackStore` 写 `<root>/<code>.json` + `index.json`（扁平 JSON `{"am":"<sha256>:<bytes>"}`），临时文件 + 原子改名（不支持 `ATOMIC_MOVE` 时回退 `REPLACE_EXISTING`）。
- **缓存**：按访问序 LRU，上限 `DEFAULT_MAX_CACHED_PACKS = 4`；打包语言、`en`、以及 `pin()` 的当前所选语言**永不淘汰**；淘汰只释放内存，安装包仍在磁盘（重新选择该语言是“读文件”而非“重新下载”，有测试）。
- **下载器**：`LocalePackDownloader`（阻塞接口）由 app 实现（OkHttp / `DownloadManager`），模块自身不开 socket，因此可离线单测；`expectedPacks` 可用下面的 §5.3 表生成。

### 5.3 locale 包清单（39 个，机器校验 sha256）

下表由 `node scripts/check-locales.mjs --print-pack-table` 生成，**与 `src/assets/locales/*.json` 逐字节绑定**：`check-locales.mjs` 会核对每个 code 的 sha256，漂移即红——这就是运行时下载包的完整性基线（`expectedPacks`）。

| locale | key 数 | 源文件 sha256 |
|---|---|---|
<!-- PACK-TABLE:BEGIN -->
| `am` | 1321 | `0f89fa89362710b76faf567fa2f4287d8a1bd88c330852d90a234443239c40cf` |
| `ar` | 1324 | `3cff51191664f633c006d46d788272b3e6b5b29c64c5cd35fecacf2d44181bc7` |
| `bg` | 1324 | `9bb0be10383f8acecba2a79168b2323949488aa8561f21f912b189eb68d27f41` |
| `bn` | 1324 | `4ea00baab1119cf4087f08e83f304fa21a52a76dc977d73e3f2f1776d00bca67` |
| `bo` | 1323 | `078cfb28030a6368fbf138c9d24d95e1666dc2faa8f6ec76c35bc95dc2bf1824` |
| `cs` | 1323 | `14d8481d058e4a25adc1bc121bd8f90941c31ce1b615e4ec1135d4f8bb3d0ee1` |
| `da` | 1326 | `9ab8dae9bfeccb1f1b43bcd2ae601b9505f54f749f24ea0e3e754f7c0d1a0cf5` |
| `de` | 1323 | `b57f0bad00609f9762172f31cc8997a4ce89d9dee86e5db5dbbcf8875fd3b476` |
| `el` | 1324 | `c5bf062ebda8860a11169cf27c6ea33c585dbd139a32ea4872c45ac883ee733a` |
| `es` | 1323 | `8fe502f1406282cefd9f484a30cf62ba530d13729497ccb957ee81623940f91d` |
| `fa` | 1323 | `373fe37759c25122326002861c66dfcf2ce4d7aff8eb4795cdcfd269f21d941f` |
| `fi` | 1330 | `3819879ea603633bf24936f6c5830270f2db639a6355580a3373f97e395fd415` |
| `fr` | 1322 | `735a8c8caa13b76167a869579cdc8733a768343c936d2f110ed8118a046e1cd5` |
| `ga` | 1321 | `4ff62c2c81a96e9955366714281623e4534aeaeb64f2d31691cdba3c4466055d` |
| `hi` | 1321 | `e5a58f46165bf32bce2a4c18fb87634dbdd4a5f6de2f35bb1ef6254bc45be399` |
| `hu` | 1321 | `6580435104abb84296cd366d7cdac2f0a4da56979d07ae5fbbfb15281c4792e3` |
| `hy` | 1324 | `2675ec600831a422a634c20b4bde00c25a67a18a05cc4149f659aef160c69db0` |
| `id` | 1338 | `e0dc10325e48f950a05a85d696a8af0fd75862fe26e043d9b57d10cdd92aeaa3` |
| `ie` | 1325 | `ebac7458c189c1a091318b48704f59789a793bebf38df23b9b2d2695bc35b177` |
| `it` | 1324 | `1834220f20f2f1f6c4b20455fd6ae283d8d7688b36016c22a74b02b8148fee09` |
| `ja` | 1334 | `d65be5563e9cb67c9df3e9a5e25726b0254b9ecdeaac459f3b1139bfe88c9003` |
| `ko` | 1323 | `80293c432c9f5f88cd578297361d58ee9c1c457c7e55966a3c1399437e42cad3` |
| `nl` | 1324 | `5ed23362e2077c4c54da8c7e4d387f732ef7bc719305225e8a8e1eacd4d818df` |
| `pl` | 1323 | `68a297c20f069e8612eee44475f0083bf368174b0fcc27f481e3c8fd96fa3371` |
| `pt` | 1330 | `51a9c3947a4c528ce8d26b0b419cfdd0c20a5b55c8fa0fd6cc453967023d43e9` |
| `pt-BR` | 1322 | `1c2270a1051683653415cf4d10eebaabb6382a359ee24dfb2c1f6a1ba2a8f776` |
| `ro` | 1323 | `657712687e4764d136c0c537be93c98fac5e9b975292bf0a73ff92285219c770` |
| `ru` | 1324 | `7c60b91515010d4c70e8966ed3c05e4ace47dd219dc7cd83a6b22b18a4817325` |
| `sl` | 1321 | `24a3c00a3d0fd572e725fec73855364615d814cdbad116b009935468d00cc68a` |
| `sr` | 1322 | `3269894a7dcdd21fde519393cf75303cade9de47217694bf3ad9f4861b709334` |
| `sv` | 1328 | `5f5b1458df88eb5911adc8cdf58cb467f9de5ad44bf17e2bd90e07a7fcd8ee6f` |
| `ta` | 1322 | `af5487d3a472c7a3f61bfdfef5cf55b8b698295f422e930bb1e4278af43bf2a4` |
| `th` | 1323 | `6132f4fb30bb91516aaa6f41f8713d4038a4806c24ef97916756e18d0c8d0456` |
| `tl` | 1321 | `2b1239f2f88a653591c06776ea196ccb15fe579d5c9998c89106593e2cdd20e3` |
| `tr` | 1324 | `f0d2c8733177a7f32bd8fbf5ad8fb29d8421b2dfecacd2fe4de529d1227ea0c6` |
| `uk` | 1334 | `84d41ab3f38d584fdc7bf6559ed2746a61d378ed158cadaf578c7a0c8696165f` |
| `vi` | 1327 | `7998c31c0fee128472ddcb988026865d07d75d09729d8de4a491cedd22ca69ff` |
| `zh-MO` | 1329 | `2048af5794a1bc9d371b6d84a1a518b122c387216aea9c5b5a3c413a3725f280` |
| `zh-TW` | 1328 | `c7a0c5ae81d9c07d6154a51e2b36e3e57f45a1c93b328e2ecd64f1e2d8daf27b` |
<!-- PACK-TABLE:END -->

## 6. ④ 回退链不变：所选 → en → key

`LocaleFallbackChain` 把 ADR-004 的链显式化并可观测（`FallbackHit.step` = `SELECTED` / `FALLBACK_LANGUAGE` / `KEY`）：

- 目录通过 provider 每次查表时读取，`LocaleCatalogRegistry.snapshot()` 是**不可变快照（O(1)）**：刚下载完成的 pack 立即对 `t()` 可见，无需重建链、无需改动 `core:common`。
- `language` 可变并归一化（`zh_cn` → `zh-CN`，复用 `Localization.normalize`），切换即时生效（`LibraryPrefs.language` 语义不变：`system` | 代码）。
- **一致性证明**：单测对 5 种语言 × 全部 key 断言 `chain.resolve(key) == Localization(catalogs, lang).t(key)`；并对**真实桌面 `en.json`（1386 键）/`zh-CN.json`（1396 键）**做同样断言，还断言“en 有、zh-CN 无”的键必须走 `FALLBACK_LANGUAGE` 步骤。回退链语义因此**不可能**与 P1 实现分叉。
- 语言切换持久化：继续使用 `LibraryPrefs.language`（键 `language`，本次不改动 `android/app`）；`ZhConvertSettings` 与语言各自独立持久化（转换模式属 reader 配置，语言属库偏好，与桌面一致）。

## 7. ⑤ 守卫：`scripts/check-locales.mjs`

```
node scripts/check-locales.mjs [--verbose] [--print-pack-table] [--skip-bundled]
```

| 段 | 检查 | 与 `sync-locales-android.js --check` 的关系 |
|---|---|---|
| A | 41 个源 JSON：合法、扁平、值全为字符串、非空；打包副本逐字节一致；`manifest.json` 的 locale 列表 + sha256 + 键数对齐；打包白名单**从 `sync-locales-android.js` 解析**（单一事实源） | **等价**（同语义、同失败条件、退出码 2）；多出的“键数对齐”是超集 |
| B | `android/core/locale` 模块文件齐全、≥4 个 JVM 单测；`REMOTE_LOCALE_CODES` == 桌面 − 打包（39）；`BUNDLED_LOCALE_CODES` == `ANDROID_LOCALES` | P6 新增 |
| C | `docs/p6-zh-locale-design.md` 必须引用 ADR-004；§5.3 表里每个远端 locale 的 sha256 必须与当前源文件一致 | P6 新增（下载清单防漂移） |
| D | `OpenCcSeed` 四段词表：无重复键、无格式错误、无恒等规则、规模不低于下限；`ST_CHARACTERS` 1:1 且值不当键 | P6 新增（保护 T→S 反演） |
| E | 接线状态：`settings.gradle`/`app/build.gradle` 未接线 → **WARN**（不失败） |  |

- `--skip-bundled`：只跳过 A 段的“打包副本/manifest 比对”（源校验仍执行），用于在**既有漂移未修**时确认 P6 新增守卫全绿；默认开启全部检查，行为与 `sync --check` 一致。
- **CI 接线（未改，属禁改文件）**：在 `.github/workflows/release-android.yml` 的 `sync-locales-android.js --check` 之后加一行
  `run: node scripts/check-locales.mjs`。

### 7.1 当前仓库状态（重要，非本卡引入）

在本 worktree 的干净检出上（HEAD `9abe7904`），**`node scripts/sync-locales-android.js --check` 本身就已经失败**（退出码 2）：

| locale | 源 keys | 打包 assets keys | manifest keys | 结论 |
|---|---|---|---|---|
| `en` | 1386 | 1387 | 1377 | 打包副本与 manifest 均过期 |
| `zh-CN` | 1396 | 1406 | 1396 | 打包副本过期（manifest sha256 亦不符） |

因此 `check-locales.mjs` 默认（与 sync 一致）也会报这 5 条 A 段失败 —— 这是**既有红状态**，与本卡改动无关。修复只需在允许改动这些生成物的任务里执行 `node scripts/sync-locales-android.js`（会重写 `android/app/src/main/assets/locales/{en,zh-CN,manifest}.json`）。本卡**未修改**这些文件（超出行范围）：`check-locales.mjs --skip-bundled` 可单独确认 P6 部分全绿。

## 8. 未接线 / 后续清单（本卡文件范围之外）

1. `android/settings.gradle`：`include ':core:locale'`（**禁改**）。
2. `android/app/build.gradle`：`implementation project(':core:locale')`（**禁改**）。
3. `android/app`：`preferencesDataStore("zh_convert")` + `DataStoreZhConvertSettingsStore`（§4.2）。
4. 阅读管线：在渲染前对文本节点调用 `repository.engine().convert(text, mode, language)`（同一文本节点粒度，与桌面 `convertChinese` 注入点一致）。
5. `I18nState`（`android/app/.../I18n.kt`）：启动时 `preloadBundled()`，语言切换时 `loader.pin(code)` + `loader.ensure(code)`（远端缺失 → 提示下载）。
6. `LocalePackDownloader` 实现（OkHttp / DownloadManager）+ 下载 UI；`expectedPacks` 用 §5.3 表生成。
7. CI 注册 `node scripts/check-locales.mjs`（§7）。

## 9. 验证方式

**在本 worktree 执行的**（Node 可用）：

```bash
node scripts/check-locales.mjs --verbose            # 默认：与 sync --check 等价 + P6 守卫
node scripts/check-locales.mjs --skip-bundled       # 仅 P6 守卫（绕开既有漂移）
node scripts/check-locales.mjs --print-pack-table   # 重新生成 §5.3 表
node scripts/sync-locales-android.js --check        # 复现既有 A 段红状态（对照）
```

**本次实际观察到的结果**（2026-09-24，worktree 内）：

- `check-locales.mjs --skip-bundled --verbose` → `check OK`（41 locale / 39 按需 / 种子与文档表全部校验通过，退出码 0）。
- `check-locales.mjs`（默认）→ 与 `sync-locales-android.js --check` 一致地报 5 条 A 段既有漂移（退出码 2，非本卡引入，见 §7.1）。
- **算法/数据交叉验证**（临时脚本，验证后已删除，不属交付物）：用 Node 复刻 `segments/applyChain/reverse/merge` 后，直接读取本模块的 `OpenCcSeed.kt` 真实词表，**跑通 54 条向量**——含本文档与单测中出现的全部期望串（s2t/s2tw/t2s/tw2s 段落、22 条消歧与缺口用例、用户词典优先级与片段锁定、AUTO 判定与信号计数 `[7,0]`/`[0,8]`、MaxMatch/segments、反向表规模 `524/64/69`、代理对保真）。该步骤保证“词表数据 + 单测期望值”自洽（编译期问题仍需 CI）。
- **结构扫描**（临时脚本，已删除）：14 个 Kotlin 文件 / 3398 行，字符串与注释感知的括号配平、包声明、行尾、冲突标记全部通过；30 个公开符号均有声明。

**需要 Android/CI 环境执行的**（本机无 `gradlew`/`kotlinc`/JDK17，故未能运行，见 §10 风险 1）：

```bash
./gradlew :core:locale:test        # 5 个测试类
./gradlew :core:locale:selfCheck   # 无框架自检，打印 OK/FAIL
```

测试覆盖矩阵（对应 5 个验收项）：三态与桌面对齐、四条方向链、AUTO 判定阈值与边界、非中文 no-op、非汉字/代理对保真、用户词典优先级与片段锁定、词表格式/MaxMatch/反向/种子完整性、按需加载状态机/哈希校验/损坏处理/LRU/磁盘持久化、回退链三级 + 与 `Localization` 及真实桌面 JSON 的一致性、持久化编解码与桌面互通。

## 10. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 1. 本环境无法编译 Kotlin（无 gradle wrapper / kotlinc / JDK17） | 单测未实跑，只做了静态审查 + Node 守卫 | 代码刻意保持保守写法（无协程、无 Android、无高级语法）；CI `:core:locale:test` 为第一道验证；守卫脚本已机器校验词表与清单 |
| 2. 词表是子集 | 生僻词/歧义单字转换不完整（§3.3 已列） | 全量上游词表可原样加载；缺口有测试固定，便于替换后回归 |
| 3. 与桌面压缩产物的转换结果可能有细微差异 | 极少数词条结果不同 | 语义锚定在“配置键 + 词表语义 + 链路”三处；如需逐字节对齐，待 `D:\Project\kookit` 源码可得后做黄金向量回归 |
| 4. 未接线（§8） | 功能在设备上尚不可用 | 接线点已文档化且为小改动；`check-locales.mjs` 会以 WARN 持续提示 |
| 5. 下载包供应链 | 恶意/损坏 pack | sha256 白名单（§5.3）+ 结构校验 + 失败即删；可选后续加签名 |
| 6. 既有 A 段漂移 | CI 守卫红 | 非本卡引入；修法是跑一次 `sync-locales-android.js`（§7.1） |

**回滚**：本卡全部为**新增文件**，删除 `android/core/locale/`、`scripts/check-locales.mjs`、`docs/p6-zh-locale-design.md`、`docs/patches/p6-zh-locale.patch` 即完全回滚；由于未修改 `settings.gradle`/`app/build.gradle`/其他既有文件，回滚不影响任何现有构建。
