# Android 收口批 4a（MOBI HUFF/CDIC 解码）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `engine/mobi` 从「HUFF/CDIC 显式 typed error 拒绝」升级为真实解码：解析 HUFF 码表 + CDIC 词典切片 + 位流解压，MobiParser 的 HUFF_CDIC 分支产出正文。

**Architecture:** 纯 JVM 新增 `HuffCdicDecoder.kt`（算法对照 `android/app/src/main/assets/foliate/mobi.js:85-96,265-326`），`MobiHeader` 补 huffcdic/numHuffcdic 两个头字段（foliate mobi.js:42-43 偏移 112/116），`MobiParser.readText` 在 isHuffCdic 时用解码器替换 PalmDOC 路径。

**Tech Stack:** Kotlin/JVM + kotlin.test（`engine/mobi` 现有 74 测试基线）；Node 侧 foliate mobi.js 用于差分验证。

## Global Constraints

- 算法**以 foliate mobi.js 为唯一对照**，不得自行发明变体；引用行号见各任务。
- 不新增第三方依赖（自实现位读取/Huffman 逻辑）。
- 失败语义保留类型化：HUFF/CDIC 记录损坏抛 `MalformedMobiException`（不再抛 `UnsupportedCompressionException`）；`UnsupportedCompressionException` 保留给未来未知压缩类型。
- 本机无 JDK17：`./gradlew :engine:mobi:test` 归 CI；Node 差分脚本本机可跑。
- 测试 fixture 用**合成 HUFF/CDIC 记录**（测试内构造，不依赖真实版权书籍）。

---

### Task 1: MobiHeader 补 huffcdic 字段

**Files:**
- Modify: `android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/MobiHeader.kt`
- Modify: `android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/MobiModel.kt`（若字段集中在 model）
- Test: `android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/MobiHeaderTest.kt`（无则加到 MobiParserTest.kt 合适类）

**Interfaces:**
- Consumes: 现有 MOBI 头解析（textRecordRange 等）
- Produces: `MobiHeader.huffcdicIndex: Int`（偏移 112）、`MobiHeader.numHuffcdicRecords: Int`（偏移 116）

- [ ] **Step 1: 失败测试**

```kotlin
    @Test
    fun `huffcdic offsets 112 and 116 are parsed`() {
        val headerBytes = ByteArray(132)
        // 用测试既有的 MOBI 头构造器（参考同文件 textRecordRange 测试的构造方式），
        // 在 112 写入 0x0000_0010、116 写入 0x0000_0003
        putU32(headerBytes, 112, 16)
        putU32(headerBytes, 116, 3)
        val header = parseTestHeader(headerBytes) // 复用测试文件已有 helper；无则新增私有 helper
        assertEquals(16, header.huffcdicIndex)
        assertEquals(3, header.numHuffcdicRecords)
    }
```
（`putU32` 大端写入；若测试已有等价 helper 用既有的。）

- [ ] **Step 2: 实现**

在 MOBI 头解析处（MobiHeader.kt 与各 uint 字段同一读取模式）增加：
```kotlin
    /** HUFF 记录索引（MOBI 头偏移 112，foliate mobi.js:42）。非 HUFF/CDIC 书为 0。 */
    val huffcdicIndex: Int = u32(112),
    /** HUFF+CDIC 记录总数（偏移 116，foliate mobi.js:43）。 */
    val numHuffcdicRecords: Int = u32(116),
```
`u32(offset)` 指该文件既有的无符号 32 位读取函数名——若叫 `readUInt`/`uintAt` 等，按实际名替换，报告中注明。

- [ ] **Step 3: Commit**

```bash
git add android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/ android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/
git commit -m "feat(mobi): 解析 HUFF/CDIC 头字段（112/116）"
```

---

### Task 2: HuffCdicDecoder——码表与词典切片

**Files:**
- Create: `android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoder.kt`
- Test: `android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoderTest.kt`

**Interfaces:**
- Consumes: Task 1 字段；`PalmDatabase.record(index): ByteArray`（读原始记录的既有 API——按实际函数名）
- Produces:
  - `class HuffCdicDecoder(records: List<ByteArray>) { fun decompress(data: ByteArray): ByteArray }`
    其中 `records[0]` = HUFF 记录，`records[1..]` = CDIC 记录（foliate mobi.js:265-296 同序）

- [ ] **Step 1: 失败测试——合成最小 HUFF/CDIC 样本**

```kotlin
class HuffCdicDecoderTest {

    /** 构造一个只有两个符号的码表：符号 0→"ab"，符号 1→"c"（均已解压）。 */
    private fun syntheticRecords(): List<ByteArray> {
        // HUFF 记录：magic HUFF + offset1=24 + offset2=24+256*4
        // table1[0]=found|len=1|value=1；table1[1..255] 同样指向 value=1 之外的默认（见实现）
        // table2[1]=(minCode=0, maxCode=1)
        // CDIC 记录：magic CDIC + numEntries=2 + codeLength=1，切片 ["ab", "c"]
        // 具体字节布局按实现步骤 2 的读取端逐字段构造；测试与实现共享一个 builder。
        return HuffCdicTestFixtures.twoSymbolBook()
    }

    @Test
    fun `huff and cdic tables parse`() {
        val decoder = HuffCdicDecoder(syntheticRecords())
        // 位流 "0"=符号0="ab"，"1"=符号1="c" → 单字节 0b0100_0000 解出 "ab"
        assertEquals("ab", decoder.decompress(byteArrayOf(0b0000_0000.toByte())).decodeToString())
        assertEquals("c", decoder.decompress(byteArrayOf(0b1000_0000.toByte())).decodeToString())
        assertEquals("abc", decoder.decompress(byteArrayOf(0b0100_0000.toByte())).decodeToString())
    }

    @Test
    fun `bad magic is a typed malformed error`() {
        val records = syntheticRecords().toMutableList()
        records[0] = records[0].copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<MalformedMobiException> { HuffCdicDecoder(records) }
    }
}
```
`HuffCdicTestFixtures.twoSymbolBook()` 在测试文件内实现（HUFF 记录 24 字节头 + 256×4 表1 + 32×8 表2；CDIC 头 16 字节 + 2 个 uint16 偏移 + 两条切片）。构造细节必须与实现的读取端逐字段对齐——先写实现草稿再定 fixture 字节，但**先跑红**。

- [ ] **Step 2: 实现——表解析（foliate mobi.js:266-296）**

```kotlin
package com.koodoreader.engine.mobi

class HuffCdicDecoder(records: List<ByteArray>) {

    private val table1: Array<Triple<Boolean, Int, Int>> // found, codeLength, value
    private val table2: Array<Pair<Long, Long>>          // index 1..32: minCode, maxCode(=value)
    private val dictionary: MutableList<Pair<ByteArray, Boolean>> // slice, decompressedFlag

    init {
        require(records.isNotEmpty()) { "MobiCapture: no HUFF record" }
        val huff = records[0]
        if (huff.size < 24 || huff.text(0, 4) != "HUFF") {
            throw MalformedMobiException("MobiCapture: invalid HUFF record")
        }
        val offset1 = huff.u32(8).toInt()
        val offset2 = huff.u32(12).toInt()
        // table1: 256 × uint32 → [found=bit31, codeLength=bits0-4, value=x>>>8]（mobi.js:272-274）
        table1 = Array(256) { i ->
            val x = huff.u32(offset1 + i * 4)
            Triple(x and 0x8000_0000L != 0L, (x and 0x1fL).toInt(), (x ushr 8).toInt())
        }
        // table2: 32 × (uint32 minCode, uint32 maxCode)，索引 1..32（mobi.js:276-279）
        table2 = arrayOf(Pair(0L, 0L)) + Array(32) { i ->
            Pair(huff.u32(offset2 + i * 8), huff.u32(offset2 + i * 8 + 4))
        }
        // CDIC 词典切片（mobi.js:282-296）
        dictionary = mutableListOf()
        for (r in 1 until records.size) {
            val cdic = records[r]
            if (cdic.size < 16 || cdic.text(0, 4) != "CDIC") {
                throw MalformedMobiException("MobiCapture: invalid CDIC record")
            }
            val headerLen = cdic.u32(4).toInt()
            val numEntries = cdic.u32(8).toInt()
            val codeLength = cdic.u32(12).toInt()
            val n = minOf(1 shl codeLength, numEntries - dictionary.size)
            for (i in 0 until n) {
                val offset = cdic.u16(headerLen + i * 2)
                val x = cdic.u16(headerLen + offset)
                val length = x and 0x7fff
                val decompressed = x and 0x8000 != 0
                val start = headerLen + offset + 2
                dictionary += cdic.copyOfRange(start, start + length) to decompressed
            }
        }
    }

    fun decompress(data: ByteArray): ByteArray = decompressInto(data, OutputBuffer()).result()

    // 位流解压见 Task 3 Step 1（本任务先留 TODO 让表解析测试独立转绿）
}
```
私有 helper：`ByteArray.u32(off): Long`（大端无符号）、`u16(off): Int`、`text(off,len): String`——放同文件 private 扩展。`OutputBuffer` 可先用 `java.io.ByteArrayOutputStream` 替代。

- [ ] **Step 3: 表解析测试转绿 + Commit**

```bash
git add android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoder.kt android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoderTest.kt
git commit -m "feat(mobi): HUFF/CDIC 码表与词典切片解析"
```

---

### Task 3: 位流解压（含递归切片缓存）

**Files:**
- Modify: `android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoder.kt`
- Test: `android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoderTest.kt`

**Interfaces:**
- Consumes: Task 2 的 table1/table2/dictionary
- Produces: 完整 `decompress(data): ByteArray`

- [ ] **Step 1: 实现（foliate mobi.js:298-324 逐行对照）**

```kotlin
    fun decompress(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val bitLength = data.size * 8
        var i = 0
        while (i < bitLength) {
            val bits = read32Bits(data, i) // 从位偏移 i 读 32 位（可越过末尾，右侧补 0）
            var (found, codeLength, value) = table1[(bits ushr 24).toInt()]
            if (!found) {
                // 码长递增直到窗口值 >= minCode（mobi.js:303-306）
                while ((bits ushr (32 - codeLength)) < table2[codeLength].first) codeLength += 1
                value = table2[codeLength].second.toInt()
            }
            i += codeLength
            if (i > bitLength) break
            val code = (value - (bits ushr (32 - codeLength))).toInt()
            var (result, decompressed) = dictionary[code]
            if (!decompressed) {
                // 切片自身仍压缩：递归解压并缓存（mobi.js:317-321）
                result = decompress(result)
                dictionary[code] = result to true
            }
            out.write(result)
        }
        return out.toByteArray()
    }

    private fun read32Bits(data: ByteArray, bitOffset: Int): Long {
        var result = 0L
        for (k in 0 until 32) {
            val pos = bitOffset + k
            val bit = if (pos < data.size * 8) {
                (data[pos / 8].toInt() ushr (7 - pos % 8)) and 1
            } else 0
            result = (result shl 1) or bit.toLong()
        }
        return result
    }
```

- [ ] **Step 2: 增加嵌套切片测试**

fixture 增第三切片：符号 2 的值指向符号 0 的压缩形态（decompressed=false），验证递归解压与缓存命中（同一符号出现两次，第二次走缓存——用输出断言，不断言内部状态）。
以及 `bits ushr (32 - codeLength)` 的边界：codeLength=32 时 `ushr 0` 合法；构造最长码长用例。

- [ ] **Step 3: 测试转绿 + Commit**

```bash
git add android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoder.kt android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/HuffCdicDecoderTest.kt
git commit -m "feat(mobi): HUFF/CDIC 位流解压与嵌套切片缓存"
```

---

### Task 4: MobiParser 接线 + 差分验证

**Files:**
- Modify: `android/engine/mobi/src/main/kotlin/com/koodoreader/engine/mobi/MobiParser.kt:158-163`（readText 的 isHuffCdic 分支）及逐记录解码处
- Modify: `android/engine/mobi/src/test/kotlin/com/koodoreader/engine/mobi/MobiParserTest.kt:348-362`（拒绝用例改为成功用例）
- Create: `scripts/test-huffcdic-diff.js`（Node 差分：同一合成 fixture，foliate mobi.js 解压输出 vs Kotlin 输出）

**Interfaces:**
- Consumes: `header.huffcdicIndex/numHuffcdicRecords`、`HuffCdicDecoder(records)`
- Produces: HUFF_CDIC 书的正文文本（与原 PalmDOC 路径同一输出管道）

- [ ] **Step 1: readText 集成**

把 MobiParser.kt:158-163 的 throw 分支替换为：
```kotlin
        val decompressor: ((ByteArray) -> ByteArray)? = if (header.isHuffCdic) {
            val records = (0 until header.numHuffcdicRecords).map { db.record(header.huffcdicIndex + it) }
            val decoder = HuffCdicDecoder(records)
            ({ data -> decoder.decompress(data) })
        } else null
```
后续每个正文记录：HUFF/CDIC 时 `decompressor!!(recordBytes)` 代替 PalmDOC 解码（其余 trailing/多字节边界处理复用现有逻辑——读代码后按现有结构嵌入，报告中说明接点）。

- [ ] **Step 2: 更新 MobiParserTest**

:348 的 `HUFF CDIC compression is refused with a typed unsupported error` 改为 `HUFF CDIC compression decodes through HuffCdicDecoder`：用 Task 2 的 fixture 组装一本最小 MOBI（头部 compression=17480 + huffcdic 字段 + 一个正文记录），断言 readText 输出 "abc" 而非抛错。另保留一个损坏 HUFF 记录 → `MalformedMobiException` 用例。

- [ ] **Step 3: Node 差分脚本**

`scripts/test-huffcdic-diff.js`：
```js
// 1. 读取 android/engine/mobi/src/test/resources/huffcdic-fixture.bin（Task 2 fixture 落盘版）
// 2. 用 android/app/src/main/assets/foliate/mobi.js 的 huffcdic()（ESM import）解压
// 3. 用 scripts 下小 gradle 任务或 kotlin 脚本输出的期望文本（由 JVM 测试 dump 到 build/huffcdic-expected.txt）
// 4. assert 两者字节相等，打印 "huffcdic diff OK"
```
若 ESM import 路径不可行（mobi.js 非模块导出），改为在 Node 内 `new Function` 抽取 `huffcdic` 函数体执行——实现者选最简路径并记录。Kotlin 侧期望值由 Task 4 Step 2 的测试在 `engine/mobi/build/huffcdic-expected.txt` 落盘。

- [ ] **Step 4: 验证 + Commit**

Run（CI）: `./gradlew :engine:mobi:test` 全绿（74 基线 + 新增）
Run（本机）: `node scripts/test-huffcdic-diff.js` → "huffcdic diff OK"

```bash
git add android/engine/mobi/ scripts/test-huffcdic-diff.js
git commit -m "feat(mobi): HUFF/CDIC 正文解码接线 + foliate 差分验证"
```

---

## Self-Review 记录

- 规格覆盖：HUFF 码表（T2）、CDIC 切片（T2）、位流+递归缓存（T3）、MobiParser 接线（T4）、差分验证（T4 Step 3）、旧拒绝用例改写（T4 Step 2）。
- 降级路径（规格 §7）：若 T3/T4 卡住，保留 typed error 并在报告标注「Foliate 轨已覆盖」，不阻塞发布线。
- 类型一致性：`HuffCdicDecoder(records: List<ByteArray>)` 在 T2 定义、T4 消费；`MalformedMobiException` 替换点明确。
- 风险：fixture 与实现「共享 builder」有自我印证风险——差分脚本（foliate 独立实现）是外部裁决。
