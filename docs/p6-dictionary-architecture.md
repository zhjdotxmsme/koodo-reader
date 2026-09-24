# P6 — 原生词典（MDX / MDD + 内嵌词典源）架构与移植说明

> 任务卡：`t-muexn680-91b3qu`（P6：词典（原生 MDX/MDD + 内嵌词典源））
> 交付位置：`android/feature/dictionary/`（新模块）+ 本文件 + `docs/patches/p6-dictionary.patch`
> 移植基线：**`node_modules/js-mdict@6.0.8`**（MIT），逐行对应关系见 §2。

---

## 1. 范围与结论

桌面端词典能力 = `src/utils/file/dictUtil.ts`（存储/下载）+ `js-mdict`（解析）+ `plugins/renderer/dictionary/*`（内嵌词典源）。

本卡在当前 workspace 的实际情况：

| 桌面参考 | 本 workspace 状态 | 本卡做法 |
|---|---|---|
| `src/utils/file/dictUtil.ts` | ✅ 存在（212 行） | 作为存储/下载/命名的**行为基准**，逐条对齐（§3.3） |
| `node_modules/js-mdict` | ✅ 存在（6.0.8，MIT） | **逐行移植**为纯 JVM Kotlin（§2），不引用 npm 包 |
| `plugins/renderer/dictionary/*`（25 个内嵌词典源） | ❌ **缺失** | 不伪造词典数据；改为**清单驱动**（`assets/dicts/catalog.json`）+ 内置/云下载双通道（§6） |

**结论**：MDX/MDD 解析（含 RIPEMD-128 密钥派生、zlib 记录块、加密记录块）已原生实现并有 63 个 JVM 单测全绿；
词典管理 UI、划词弹窗、HTML 释义渲染、下载/内置方案均已落地（§4/§5/§6）。
唯一无法闭环的是**词典数据本身**（25 个源文件不在仓库里），已用清单机制解耦（§7 风险）。

---

## 2. 移植对应表（js-mdict 6.0.8 → Kotlin）

所有 Kotlin 文件均在 KDoc 中标注了对应的 `文件:行号`。核心映射：

| MDict 结构 / 算法 | js-mdict 出处 | Kotlin |
|---|---|---|
| 大端整数读取（u8/u16/u32/u64）、`b2n` | `dist/esm/utils.js:117/125/139/154/216` | `mdx/Bytes.kt` |
| RIPEMD-128（密钥派生） | `dist/esm/ripemd128.js:1-144` | `mdx/Ripemd128.kt` |
| `fast_decrypt` / `mdxDecrypt` / `salsa_decrypt` | `utils.js:235/258/246` | `mdx/MdictCrypto.kt` |
| 头 XML 解析、`unescapeEntities`、`isTrue`、`REGEXP_STRIPKEY`、`substituteStylesheet` | `utils.js:91/326/289/2/333` | `mdx/MdictHeader.kt` |
| 随机读文件（`FileScanner`） | `scanner.js:1-34` | `mdx/MdictByteSource.kt` |
| STEP1 头 / STEP2 key header / STEP3 key-info / STEP4 key block / STEP5 record header / STEP6 record-info | `mdict-base.js:334/427/495/657/693/718` | `mdx/MdictCore.kt` |
| key block 切分（1/2 字节终止符） | `mdict-base.js:288-323` | `MdictCore.splitKeyBlock` |
| 记录块解压（含加密分支） | `mdict.js:124-161` | `MdictCore.decompressBuff` |
| 二分查找 key / 记录块定位 / 记录切片 | `mdict.js:30-61/166-180/73-81` | `MdictCore.lookupKeyBlockByWord / reduceRecordBlockInfo / lookupRecordByKeyBlock` |
| `MDX.lookup/fetch/prefix/associate/suggest/fuzzy_search` | `mdx.js:10/31/50/62/78/115` | `mdx/MdxParser.kt` |
| `MDD.locate`（base64） | `mdd.js:11-30` | `MddParser.kt` |
| 编辑距离 | `utils.js:58` | `mdx/Levenshtein.kt` |
| 词典文件夹 / 元数据 / 云下载 | `src/utils/file/dictUtil.ts:16/111/145/154/201` | `DictRepository.kt` / `DictDownloadManager.kt` |

### 2.1 MDX 文件布局（六段，本实现按此读取）

```
[0:4]        headerByteSize            BE u32
[4:4+n]      头 XML                    UTF-16LE，NUL 结尾
[4+n:+8]     头 adler32                BE u32
             keyHeader                 5 × numWidth（v2.0+ 后再跟 4 字节 adler32）
             keyBlockInfo              zlib 信封 [02 00 00 00][adler32][deflate]
             keyBlocks                 每个 key block 一个信封，顺序排列
             recordHeader              4 × numWidth
             recordInfo                每记录块 (packSize, unpackSize)
             recordBlocks              每个记录块一个信封，顺序排列
```

- `numWidth` = **8**（`GeneratedByEngineVersion >= 2.0`）否则 **4**（`mdict-base.js:384-391`）。
- 块压缩类型是**小端 u32**：`0` 不压缩、`1` LZO、`2` zlib（`mdict-base.js:632-649`、`mdict.js:134-159`）。
  js-mdict 用 `bytes.toString('hex') == '02000000'` 表达，等价于 LE u32 = 2。
- key-info 的 zlib 信封判定，js-mdict 写成 `subarray(0,4).join('') == '2000'`（`mdict-base.js:514`）——
  这是把 4 个**十进制**字节值拼接，只有 `[02 00 00 00]`（LE u32 = 2）才等于字符串 `"2000"`；
  本移植直接比较 LE u32（`MdictCore.decodeKeyInfo`）。
- 记录偏移是**全局解压域**偏移（跨记录块累加），切片时减去所在块的 `unpackAccumulatorOffset`
  （`mdict.js:78-79`）。
- key block 内最后一条词的 `recordEndOffset` 由**下一块第一条**补全；全库最后一条由
  `recordInfoList.last()` 补全（`mdict-base.js:670` 与 `753-755`）。

### 2.2 加密（RIPEMD-128 + XOR 流）

```
key   = RIPEMD128( block[4:8] || 95 36 00 00 )        # utils.js:258-271
payload = block[8:]                                    # 前 8 字节信封（类型+adler32）不加密
fast_decrypt: previous = 0x36
              t = swapNibble(b[i]) ^ previous ^ (i & 0xff) ^ key[i % 16]
              previous = b[i]（读改写前的密文字节）
```
- `encrypt == 1`（`Encrypted="Yes"`）→ **记录块**加密；`encrypt == 2` → **key-info 块**加密。
- LZO（类型 1）与 Salsa20（`RegisterBy` 注册词典）**明确抛错**，不返回垃圾数据（§5）。

---

## 3. 与参考实现的有意差异（全部在代码处标注）

1. **adler32 真正校验**（可选，`MdictCore.Options.verifyChecksums`）。
   js-mdict 把 4 处校验写成 TODO（`mdict-base.js:341/525/645/786`）。开启后能把"下载被截断"变成明确报错。
   校验对象是**解压后**的数据（`mdict-analysis`：adler32 of decompressed record block）——
   `GoldenInteropTest` 用 JS 写出的 golden 文件在 `verifyChecksums = true` 下解析通过，
   等于用跨语言证据固定了该字段语义。**注意**：解析期间会多一次 Adler32 计算，默认关闭。
2. **key-info 解压不局限于 `version == 2.0`**：`mdict-base.js:513` 只处理 2.0，
   2.1+/3.0 会拿到未解压的 key-info 并解析失败。本实现按信封前缀判断（对 2.0 行为完全一致，对 3.0 是净改进）。
3. **`Encrypted="Yes"` 可读**：js-mdict 在 `mdict-base.js:444-457` 对 `encrypt & 1` 直接抛错，
   导致它**自己的**记录块解密分支（`mdict.js:141-146`）永远不可达。本实现只在 `RegisterBy` 存在时拒绝，
   其余情况正常解密记录块 —— 这正是本卡"zlib + record block 加密块最小读取"的要求。
4. **关键词排序用 `java.text.Collator`**（+ 码点 tie-break）替代 `localeCompare`。
   两者对拉丁字母一致；对 CJK，Collator(ENGLISH) 等价码点序，而 `localeCompare` 在 zh 下是拼音序。
   因为是**排序与二分查找共用同一比较器**，查找结果不受影响，只有关键词列表的展示顺序不同
   （`MdxParserTest.utf16 encoded keys are decoded` 记录了这一差异）。
5. **LZO / Salsa20 显式失败**而非静默返回（§5）。
6. 记录块的加密**写入侧**：MDict 的 `_fast_encrypt`（`previous = b[i] ^ t`）与读取侧 `_fast_decrypt`
   （`previous = b[i]`）**并不互逆**（异或链里的半字节交换无法抵消）。因此测试夹具写入器
   `MdictFixtureWriter.fastEncrypt` 不是"猜 MDict 的加密函数"，而是**由本移植的解密式代数求解**
   （`c[i] = swap(p[i] ^ P(i) ^ i ^ key[i])`，`P(i)=0x36` 或 `c[i-1]`），
   由 `MdictCryptoTest.fastEncrypt is the inverse of fastDecrypt` 保证往返正确，
   从而让 `MdxParserTest.encrypted record blocks are decrypted` 真正覆盖解密分支。

---

## 4. 模块结构

```
android/feature/dictionary/
├── build.gradle                       Android library + Compose；纯 JVM 部分零依赖
└── src/
    ├── main/kotlin/com/koodoreader/feature/dictionary/
    │   ├── mdx/                         ← 纯 JVM（可脱离 Android SDK 单测）
    │   │   ├── Bytes.kt                 大端/小端整数读取
    │   │   ├── Ripemd128.kt             RIPEMD-128
    │   │   ├── MdictCrypto.kt           fast_decrypt / mdxDecrypt
    │   │   ├── MdictHeader.kt           头 XML + Encoding/Encrypted/version 推导 + StyleSheet
    │   │   ├── MdictByteSource.kt       随机读（内存 / 文件）
    │   │   ├── MdictCore.kt             ★ 六段布局读取 + 查找 + 解压 + 解密
    │   │   ├── MdxParser.kt             lookup / fetch / prefix / associate / suggest / fuzzySearch
    │   │   └── Levenshtein.kt           编辑距离
    │   ├── MddParser.kt                 MDD 资源定位（bytes + base64 + MIME 嗅探，含 locateFlexible）
    │   ├── DictRepository.kt            安装/启用/排序/默认/删除/持久化 + 跨词典 lookup
    │   ├── DictDownloadManager.kt       云目录 + 下载安装 + 内置(APK assets)安装
    │   ├── OnDemandDownloader.kt        ★ 按需下载接口 + 原子落盘 HTTP 实现（与 P6 OCR 共用）
    │   ├── HtmlDefinitionRenderer.kt    释义 HTML：@@@LINK 解析 / 消毒 / dict-res 资源改写 / 纯文本
    │   ├── MiniJson.kt                  零依赖 JSON（词典索引读写）
    │   └── ui/
    │       ├── DictManagementScreen.kt  词典管理（启用/排序/默认/导入/删除/下载进度）
    │       └── PopupWordDialog.kt       划词弹窗 + TextView HTML 渲染 + mdd 图片 ImageGetter
    └── test/kotlin/...                  8 个测试文件 / 63 个用例（§7）
```

### 4.1 UI 与桌面的差异（验收项 ②③）

桌面设置页**没有**启用/排序/默认三个控件（`dictSetting/component.tsx:28-122` 只有导入/删除/下载），
阅读器用插件配置 `dictService → config.dictId` 指定唯一词典（`popupDict/component.tsx:189`）。
验收清单要求的"启用/排序/默认词典"因此是 **Android 侧扩展**，落在 `DictEntry` 的
`enabled / isDefault / order` 三个字段上；持久化仍写成桌面的 `dictList` + `customDicts` 结构
（`dictUtil.ts:111-136`），桌面读到时忽略未知字段，P7 备份往返安全。

`PopupWordDialog` 对齐桌面弹窗行为：`@@@LINK=` 别名（渲染器解析）、`entry://` 内部链接（点击换词）、
`<audio class="audio-player">`（`state.hasAudio` + `onPlayAudio`，对应桌面渲染后重新 `load()` 的行为，
`popupDict/component.tsx:229-234`）、`dict-res://` 图片经 `MddImageGetter` 从 `.mdd` 取字节。

---

## 5. 已知缺口（明确不支持，均抛明确异常或留接口）

| 缺口 | 原因 | 表现 |
|---|---|---|
| LZO 压缩块（类型 1） | 只用于 MDX v1.x，本卡范围为 zlib 路径 | `MdictFormatException`，提示见本文件 §5 |
| Salsa20 加密（`Encrypted="2"` + `RegisterBy`） | js-mdict 自身也是 `return data` 空实现（`utils.js:246-252`） | `salsaDecryptUnsupported()` 抛错 |
| 注册词典的 key 段加密 | 需要用户注册码（passcode），桌面亦无此能力 | 构造时按 `RegisterBy` 抛错 |
| GB18030/Big5 编码 | **已支持**（JVM 内置 charset），但无真实样本单测 | `MdictEncoding.GB18030/BIG5`，未覆盖 |
| 25 个内嵌词典源数据 | 仓库中 `plugins/renderer/dictionary/*` 缺失 | §6 清单机制；数据到位即可用 |

---

## 6. 词典来源：内置 + 按需下载（验收项 ④）

### 6.1 与 P6 OCR 共用的按需下载机制

```kotlin
interface OnDemandDownloader {                       // OnDemandDownloader.kt
    data class Request(val id: String, val url: String, val targetFile: File,
                       val expectedBytes: Long? = null,
                       val headers: Map<String, String> = emptyMap(),
                       val isCancelled: () -> Boolean = { false })
    data class Progress(val bytesRead: Long, val totalBytes: Long) { val fraction: Float }
    sealed interface Outcome { Success; Failure; Cancelled }
    fun download(request: Request, onProgress: (Progress) -> Unit = {}): Outcome
    fun isComplete(request: Request): Boolean
}
```

接口是**资产无关**的（id + url + 目标文件），OCR 模型包用同一契约即可复用同一实现与同一套测试。
`HttpOnDemandDownloader` 用 `java.net`（无第三方依赖，Android 24+ / 纯 JVM 均可跑），要点：

- 写 `.part` 后 `renameTo` 原子落盘 —— 中断的下载**永远不会**被 MDX 解析器当成词典读到；
- `Range` 续传（206 时**追加**写入，`File.outputStream()` 会截断，必须 `FileOutputStream(part, true)`）；
- `expectedBytes` 不符即判失败并删除 `.part`（与 §3.1 的 adler32 校验形成双重保险）；
- `isCancelled()` 支持中途取消；
- HTTP 面收敛为 `ConnectionFactory`，测试注入假连接，无需网络。

### 6.2 云词典（桌面同款）

`https://storage.koodoreader.(cn|com)/dicts/<id>.mdx` —— `.cn` 仅当"中国区 + 已登录"
（`dictUtil.ts:145-151`）；请求头 `Cache-Control: no-transform` + `Accept-Encoding: identity`
（`:160-165`，防止代理重压缩破坏字节精确性）；显示名 `zh*` 用 `translation`（`:139-142`）。

### 6.3 内置词典（清单驱动）

`DictDownloadManager` 从 App 提供的 `assets/dicts/catalog.json` 读取：

```json
{
  "dicts":   [{"id":"oxford","name":"Oxford Advanced","translation":"牛津高阶","bytes":123}],
  "bundled": [{"id":"cambridge","name":"Cambridge","asset":"dicts/cambridge.mdx",
               "resourceAsset":"dicts/cambridge.mdd"}]
}
```

`installBundled(dict, bytes, resourceBytes)` 首次启动把 assets 解包到 `<dict>/bundled/`
并注册为 `BUNDLED` 来源；`pendingBundled()` 给出待安装清单。**词典数据缺失不影响代码闭环**。

### 6.4 存储布局（与桌面一致）

```
<filesDir>/dict/<id>.mdx          # dictUtil.ts:16,26-35 同款：<storage>/dict/<id>.<ext>
<filesDir>/dict/<id>.mdd
<filesDir>/dict/dict-index.json   # dictList + customDicts（桌面 config.json 的等价子树）
<filesDir>/dict/bundled/          # APK 内置词典
<filesDir>/dict/tmp/              # 下载中转（成功后 rename 落盘）
```

---

## 7. 验证（可复现）

### 7.1 证据汇总

```
63 tests, 0 failures  （8 个测试文件，纯 JVM，无 Android SDK、无网络）
  DictDownloadManagerTest     11   （下载/续传/截断/取消/内置安装/目录/URL 对齐）
  DictRepositoryTest          10   （启用/排序/默认/删除不变量/持久化/桌面索引兼容）
  HtmlDefinitionRendererTest  10   （@@@LINK / 消毒 / dict-res 改写与回环 / 样式表 / 纯文本）
  MdxParserTest               13   （v2.0/1.2、多 key block、不压缩、加密记录块、UTF-16、adler32）
  mdx.MdictCryptoTest          6   （js-mdict 黄金向量 + 加密往返）
  mdx.Ripemd128Test            3   （RIPEMD-128 标准测试向量）
  GoldenInteropTest            5   （★ 跨语言互操作，见 7.3）
  MddParserTest                5   （UTF-16 键、base64、MIME 嗅探、模糊键匹配）
```

### 7.2 运行方式

模块测试（需要 Android SDK / AGP，`:feature:dictionary` 注册之后）：

```bash
gradle :feature:dictionary:testDebugUnitTest
```

**不依赖 Android SDK 的等价运行方式**（本卡实际采用；不触碰 `android/settings.gradle`）：
一个 throwaway 纯 JVM 工程，用 `srcDir` 指向模块的真实源码（排除 `ui/`），跑模块的**真实测试源码**：

```groovy
// .scratch/verify/build.gradle（一次性，验证后已删除；结构见本次执行评论）
plugins { id 'org.jetbrains.kotlin.jvm' version '1.9.24' }
sourceSets {
    main { kotlin { srcDir new File(moduleDir, 'src/main/kotlin'); exclude '**/ui/**' } }
    test { kotlin.srcDir new File(moduleDir, 'src/test/kotlin') }
}
dependencies { testImplementation 'junit:junit:4.13.2' }
```

```bash
gradle -p .scratch/verify test --offline
```

> 本机受限环境备注（复现时需要）：sandbox 只允许 workspace 内写入，因此
> `GRADLE_USER_HOME` 指向 workspace 内的临时目录，并用 `GRADLE_RO_DEP_CACHE` 复用
> 已有依赖缓存；toolchain JDK 用 `org.gradle.java.installations.paths` 显式指定
> （`D:\jdk-17\jdk-17.0.13+11`）。

### 7.3 跨语言互操作证据（最强的一条）

`GoldenInteropTest` 的两个 base64 夹具是这样产生的：

1. 用**独立的 JavaScript 写入器**（`.scratch/mkfixture.mjs`，按格式描述逐字节构造 MDX v2.0 / MDD v2.0，
   zlib 用 Node `zlib.deflateSync`，adler32 自行计算）生成文件；
2. 用 **js-mdict 6.0.8 本体**读回，取出 `lookup` / `prefix` / `keyInfoList` / `recordInfoList` /
   `locate().definition`（base64）等期望值；
3. 把这些期望值**原样**写进 Kotlin 测试，用本移植读**同一份字节**做断言。

因此该测试同时证明了：JS 写入器、JS 读取器、Kotlin 移植三者字节级一致；
JS 写入的 adler32 与本移植计算的 adler32 相同（`verifyChecksums = true` 下通过）；
`.mdd` 的 UTF-16 键布局一致。夹具**没有**经过 `MdictFixtureWriter`，所以不存在"两边同错"的遮蔽。

另外 `mdxDecrypt` / `fastDecrypt` 的黄金向量也直接取自 js-mdict 的 `ripemd128` / `mdxDecrypt` 运行时输出
（`.scratch/mkfixture.mjs` 打印），`Ripemd128Test` 再用 RIPEMD-128 官方测试向量二次固定。

### 7.4 附录：黄金夹具生成器（节选，非完整可运行）

该脚本是一次性验证工具（`node .scratch/mkfixture.mjs`，Node + `node_modules/js-mdict` + 内置 `zlib`，无需安装依赖），
验证后随 `.scratch/` 一起删除。下面保留其**关键部分**（信封编码、adler32、头 XML、js-mdict 读回与向量打印）；
`buildMdx()` / `buildMdd()` 的完整字节布局与 §2.1 一一对应（key block = `numWidth` 字节 BE 记录偏移 + 键字节 + 终止符）。

```js
// mkfixture.mjs（节选）— 独立写入 MDX v2.0 / MDD v2.0，交给 js-mdict 读回，打印黄金向量
import { writeFileSync, mkdirSync } from 'fs';
import { pathToFileURL } from 'url';
import zlib from 'zlib';
const BASE = 'E:/open-source/koodo-reader';
const { MDX, MDD } = await import(pathToFileURL(`${BASE}/node_modules/js-mdict/dist/esm/index.js`).href);
const common = (await import(pathToFileURL(`${BASE}/node_modules/js-mdict/dist/esm/utils.js`).href)).default;

const adler32 = (b) => { let a = 1, s = 0; for (const x of b) { a = (a + x) % 65521; s = (s + a) % 65521; } return ((s << 16) | a) >>> 0; };
const u32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32BE(n >>> 0, 0); return b; };
const u16 = (n) => { const b = Buffer.alloc(2); b.writeUInt16BE(n, 0); return b; };
const u64 = (n) => Buffer.concat([u32(Math.floor(n / 2 ** 32)), u32(n >>> 0)]);
// v2 信封：[02 00 00 00][adler32(明文)][zlib(明文)]
const env = (plain) => Buffer.concat([Buffer.from([2, 0, 0, 0]), u32(adler32(plain)), zlib.deflateSync(plain)]);
const headerXml = (attrs) => { const x = Buffer.from(`<Dictionary ${Object.entries(attrs).map(([k, v]) => `${k}="${v}"`).join(' ')}/>`, 'utf16le'); return Buffer.concat([u32(x.length), x, u32(adler32(x))]); };

// buildMdx()/buildMdd()：见 §2.1 的六段布局；词条、记录块切分（2+3）、MDD 的 UTF-16 键（2 字节终止符）

mkdirSync(`${BASE}/.worktrees/p6-dict/.scratch`, { recursive: true });
writeFileSync(`${BASE}/.worktrees/p6-dict/.scratch/golden.mdx`, buildMdx());
writeFileSync(`${BASE}/.worktrees/p6-dict/.scratch/golden.mdd`, buildMdd());

const mdx = new MDX(`${BASE}/.worktrees/p6-dict/.scratch/golden.mdx`);
console.log(mdx.lookup('banana').definition, mdx.prefix('a').map((k) => k.keyText), mdx.keyInfoList, mdx.recordInfoList);
console.log(Buffer.from(common.ripemd128(Buffer.from('abc'))).toString('hex'));       // -> c14a12199c66e4ba84636b0f69144c77
console.log(Buffer.from(common.mdxDecrypt(Buffer.from('11223344556677880102030405060708090a', 'hex'))).toString('hex'));
writeFileSync(`${BASE}/.worktrees/p6-dict/.scratch/mdx.b64`, buildMdx().toString('base64'));
```

两次实际运行产出的完整向量已固化在
`GoldenInteropTest` / `MdictCryptoTest` / `Ripemd128Test` 中
（`mdx.b64` = 1116 字符 / 835 字节，`mdd.b64` = 844 字符 / 631 字节）。

---

## 8. 集成步骤（需要主线程/上层执行，本卡不得改这些文件）

1. `android/settings.gradle` 增加 `include ':feature:dictionary'`；
2. `android/app/build.gradle` 的 `dependencies` 增加 `implementation project(':feature:dictionary')`；
3. App 侧接线：
   ```kotlin
   val repo = DictRepository(context.filesDir)
   val manager = DictDownloadManager(repo, HttpOnDemandDownloader()) {
       context.assets.open("dicts/catalog.json").bufferedReader().readText()
   }
   // 首启：manager.pendingBundled().forEach { manager.installBundled(it, bytes, mddBytes) }
   // 划词：val result = repo.lookup(word)
   //       val html = HtmlDefinitionRenderer.render(result.html.orEmpty(), parser.header.styleSheet,
   //                                                 Options(dictId = result.entry!!.id))
   //       PopupWordDialog(DictPopupState.of(word, html, result.entry!!.name, result.suggestions), ...)
   ```
   `resourceLoader` 建议接到 `repo.openResourceParser(id)?.locateFlexible(key)?.bytes`（注意缓存 parser）。
4. 词典数据：把 25 个内嵌词典源放到 `assets/dicts/` 并写 `catalog.json`（§6.3）。
5. 用户可见文案：`DictStrings` / `DictPopupStrings` 的值由 `:core:common` 的 i18n 提供
   （键名与桌面一致：`Import successful`、`Deletion successful`、`Download open dictionaries`、
   `Download successful`、`Download failed`、`Dictionary already downloaded`、`Word not found in dictionary`）。

---

## 9. 兼容性与风险

- **备份往返（P7）**：`dict-index.json` 使用桌面的 `dictList` / `customDicts` 键名，
  但包含 `enabled/isDefault/order/source/size/installedAt` 等桌面未知字段；桌面 `ConfigService`
  会忽略未知字段，反向（桌面 → Android）缺字段时按默认值补齐，`DictRepositoryTest.decodes a desktop shaped index` 已覆盖。
- **内存**：日韩汉英大词典（数万词条）会把全部关键词读入内存（与 js-mdict 相同策略，见 `mdict.js:188-196` 的注释）；
  `MdictByteSource` 已抽象出随机读，后续可换 mmap 或按 key block 懒加载（`lookupPartialKeyBlockListByKeyInfoId` / `findKeyInfoIndex` 已备好）。
- **加密词典**：本实现对 `Encrypted="Yes"` 比 js-mdict 更宽（§3.3），但**没有真实加密样本**做过端到端验证，
  现有覆盖是"由解密式反解出的夹具"。若拿到真实加密词典，优先补一个 golden 用例。
- **未验证项**：Android 侧 `ui/` 两个 Compose 文件**未编译验证**（需要 AGP + Compose 编译器；
  `:feature:dictionary` 尚未注册，且本卡不得改 `settings.gradle`）。逻辑层已全部通过 JVM 测试。
