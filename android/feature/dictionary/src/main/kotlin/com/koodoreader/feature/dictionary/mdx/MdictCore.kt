package com.koodoreader.feature.dictionary.mdx

import java.io.Closeable
import java.text.Collator
import java.util.Locale
import java.util.zip.Adler32
import java.util.zip.Inflater

/**
 * Native MDict container reader (`.mdx` / `.mdd`) — the shared core of
 * [com.koodoreader.feature.dictionary.MdxParser] and
 * [com.koodoreader.feature.dictionary.MddParser].
 *
 * PORT SOURCE — `node_modules/js-mdict@6.0.8/dist/esm/`:
 *
 * | step | reference |
 * |---|---|
 * | class + field layout | `mdict-base.js:60-159` |
 * | STEP 1 header | `mdict-base.js:334-422` (`_readHeader`) |
 * | STEP 2 key header | `mdict-base.js:427-489` (`_readKeyHeader`) |
 * | STEP 3 key-info | `mdict-base.js:495-618` (`_readKeyInfos`, `_decodeKeyInfo`) |
 * | STEP 4 key blocks | `mdict-base.js:625-683` (`unpackKeyBlock`, `_readKeyBlocks`) |
 * | STEP 4.2 key split | `mdict-base.js:288-323` (`splitKeyBlock`) |
 * | STEP 5 record header | `mdict-base.js:693-713` |
 * | STEP 6 record-info | `mdict-base.js:718-759` |
 * | lookup / record read | `mdict.js:30-180` |
 * | `strip` / `comp` | `mdict-base.js:160-175` |
 *
 * ON-DISK LAYOUT (dictionary file = these six sections back to back):
 *
 * ```
 * [0:4]     headerByteSize            (BE u32)
 * [4:4+n]   header XML                 (UTF-16LE, NUL terminated)
 * [4+n:+8]  header adler32            (BE u32, not verified by the reference)
 *           keyHeader                 5 x numWidth  (+ 4-byte adler32 when v2.0+)
 *           keyBlockInfo              zlib envelope, 1 descriptor per key block
 *           keyBlocks                 zlib envelopes, concatenated
 *           recordHeader              4 x numWidth
 *           recordInfo                recordBlocksNum x (packSize, unpackSize)
 *           recordBlocks              zlib envelopes, one per record block
 * ```
 *
 * DELIBERATE DEVIATIONS from the reference (each one documented at its site):
 *  1. `Options.verifyChecksums` actually validates the adler32 fields the JS port
 *     leaves as `TODO` (`mdict-base.js:341-343`, `525-527`, `645`, `786-788`).
 *  2. `mdict-base.js:513` inflates the key-info block only when
 *     `version == 2.0` exactly; engine 2.1+/3.0 files are handled here too.
 *  3. LZO blocks (compression type 1) throw [MdictFormatException] instead of
 *     silently producing garbage — only MDX v1.x uses LZO and the task scope is
 *     the zlib path (`docs/p6-dictionary-architecture.md` §5).
 */
class MdictCore(
    private val source: MdictByteSource,
    options: Options = Options(),
) : Closeable {

    /**
     * @param isStripKey `mdict-base.js:79-86` — strip punctuation while comparing.
     * @param isCaseSensitive `mdict-base.js:251-253` + header `KeyCaseSensitive`.
     * @param resort re-sort the keyword list after reading, like `readDict()`
     *   (`mdict-base.js:277-280`). The reference always sorts because MDX files
     *   disagree about case/locale ordering (`mdict.js:188-196`).
     * @param verifyChecksums deviation 1 above; off by default to keep parse
     *   throughput identical to the reference.
     */
    data class Options(
        val isStripKey: Boolean = true,
        val isCaseSensitive: Boolean = false,
        val resort: Boolean = true,
        val verifyChecksums: Boolean = false,
        val encryptType: Int = -1,
    )

    /** `mdict-base.js:115-121`. */
    class KeyHeader(
        val keywordBlocksNum: Long,
        val keywordNum: Long,
        val keyInfoUnpackSize: Long,
        val keyInfoPackedSize: Long,
        val keywordBlockPackedSize: Long,
    )

    /** `mdict-base.js:596-606` — one descriptor per key block. */
    class KeyBlockInfo(
        val firstKey: String,
        val lastKey: String,
        val keyBlockPackSize: Long,
        val keyBlockPackAccumulator: Long,
        val keyBlockUnpackSize: Long,
        val keyBlockUnpackAccumulator: Long,
        val keyBlockEntriesNum: Long,
        val keyBlockEntriesNumAccumulator: Long,
        val keyBlockInfoIndex: Int,
    )

    /** `mdict-base.js:740-745` — one descriptor per record block. */
    class RecordBlockInfo(
        val packSize: Long,
        val packAccumulateOffset: Long,
        val unpackSize: Long,
        val unpackAccumulatorOffset: Long,
    )

    /**
     * `mdict-base.js:314-319` — a keyword plus the location of its definition.
     *
     * `recordStartOffset` / `recordEndOffset` are absolute offsets into the
     * *fully decompressed* record space (i.e. summed over all record blocks), not
     * into a single block; `mdict.js:78-79` subtracts the block's
     * `unpackAccumulatorOffset` when slicing.
     */
    class KeyWordItem(
        val recordStartOffset: Long,
        val keyText: String,
        val keyBlockIdx: Int,
        var recordEndOffset: Long,
    )

    /** `mdict-base.js:140-145`. */
    class RecordHeader(
        val recordBlocksNum: Long,
        val entriesNum: Long,
        val recordInfoCompSize: Long,
        val recordBlockCompSize: Long,
    )

    /** Shared across every lookup; `Collator` is locale-aware like `localeCompare`. */
    private val collator: Collator = Collator.getInstance(Locale.ENGLISH)

    val meta: MdictMeta
    val header: MdictHeader get() = meta.header

    lateinit var keyHeader: KeyHeader
        private set
    lateinit var recordHeader: RecordHeader
        private set

    /** `mdict-base.js:128`. */
    val keyInfoList: MutableList<KeyBlockInfo> = ArrayList()

    /** `mdict-base.js:134` — every keyword in the dictionary, sorted (STEP 4). */
    val keywordList: MutableList<KeyWordItem> = ArrayList()

    /** `mdict-base.js:151`. */
    val recordInfoList: MutableList<RecordBlockInfo> = ArrayList()

    private var headerEnd = 0L
    private var keyHeaderEnd = 0L
    private var keyInfoStart = 0L
    private var keyInfoEnd = 0L
    private var keyBlockStart = 0L
    private var recordHeaderStart = 0L
    private var recordHeaderEnd = 0L
    private var recordInfoStart = 0L
    private var recordInfoEnd = 0L
    private var recordBlockStart = 0L

    /** Constructor parameter promoted to a field so every step below can read it. */
    private val options = options

    init {
        val ext = MdictHeaderParser.extension(source.name, "mdx")
        headerEnd = readHeaderLength()
        meta = MdictMeta(source.name, ext, readUtf16Header(), options.encryptType)
        readDict()
    }

    // ------------------------------------------------------------------ STEP 1

    /** `mdict-base.js:334-346` — length-prefixed header + adler32 trailer. */
    private fun readHeaderLength(): Long {
        val headerByteSize = Bytes.beUInt32(source.read(0, 4), 0)
        if (headerByteSize <= 0 || headerByteSize > source.size) {
            throw MdictFormatException(
                "not an MDict file: header size $headerByteSize exceeds file size ${source.size}"
            )
        }
        // 4 bytes length + payload + 4 bytes adler32 (mdict-base.js:345-346).
        return headerByteSize + 8
    }

    /** `mdict-base.js:347-422` — decode the UTF-16LE XML and resolve the basics. */
    private fun readUtf16Header(): MdictHeader {
        val headerByteSize = (headerEnd - 8).toInt()
        val raw = source.read(4, headerByteSize)
        if (options.verifyChecksums) {
            val expected = Bytes.beUInt32(source.read(4L + headerByteSize, 4), 0)
            verifyAdler32(raw, expected, "header XML")
        }
        // The XML ends with a NUL NUL terminator which the attribute regex ignores.
        return MdictHeaderParser.parse(String(raw, Charsets.UTF_16LE))
    }

    // ------------------------------------------------------------------ STEP 2

    /** `mdict-base.js:427-489` — 5 x numWidth numbers (+ adler32 when v2.0+). */
    private fun readKeyHeader(): KeyHeader {
        val numWidth = meta.numWidth
        val size = if (meta.version >= 2.0) 8 * 5 else 4 * 4
        val buff = source.read(headerEnd, size)
        // DELIBERATE DEVIATION from mdict-base.js:444-457.
        //
        // The reference throws for `Encrypted="Yes"` (`meta.encrypt & 1`) at this point,
        // which makes its OWN record-block decryption branch (mdict.js:141-146)
        // unreachable — js-mdict cannot read Encrypted="Yes" dictionaries at all. Per
        // the format, `Encrypted="Yes"` encrypts the RECORD blocks only; the key header,
        // key-info table and key blocks stay plaintext (the passcode mechanism that
        // encrypts the key section is a separate, registered-user feature, and such
        // files carry `RegisterBy`). We keep parsing and let [decompressBuff] decrypt,
        // which is the "zlib + record block" requirement of the task card.
        if (meta.encrypt and 1 == 1 && header["RegisterBy"] != null) {
            throw MdictFormatException(
                "registered dictionary (RegisterBy=${header["RegisterBy"]}) needs a user " +
                    "identification key, which is not supported"
            )
        }
        var offset = 0
        val blocks = Bytes.b2n(buff, offset, numWidth); offset += numWidth
        val entries = Bytes.b2n(buff, offset, numWidth); offset += numWidth
        // Only v2.0+ stores the *unpacked* key-info size (mdict-base.js:468-474).
        val unpackSize = if (meta.version >= 2.0) {
            val v = Bytes.b2n(buff, offset, numWidth); offset += numWidth; v
        } else {
            0L
        }
        val packedSize = Bytes.b2n(buff, offset, numWidth); offset += numWidth
        val keyBlockPackedSize = Bytes.b2n(buff, offset, numWidth)
        return KeyHeader(blocks, entries, unpackSize, packedSize, keyBlockPackedSize)
    }

    private fun keyHeaderStart(): Long = headerEnd

    // ------------------------------------------------------------------ driver

    /** `mdict-base.js:257-281` (`readDict`) — same seven steps, same order. */
    private fun readDict() {
        keyHeader = readKeyHeader()
        keyHeaderEnd = keyHeaderStart() + keyHeaderBytes() +
            (if (meta.version >= 2.0) 4 else 0)

        readKeyInfos()
        readKeyBlocks()
        recordHeader = readRecordHeader()
        readRecordInfos()

        if (options.resort) {
            // mdict-base.js:277-280 — the reference sorts with localeCompare; a
            // collator + code-point tie-break is the closest total order on the JVM.
            keywordList.sortWith { a, b -> comp(a.keyText, b.keyText) }
        }
    }

    private fun keyHeaderBytes(): Long = if (meta.version >= 2.0) 8L * 5 else 4L * 4

    // ------------------------------------------------------------------ STEP 3

    /** `mdict-base.js:495-505`. */
    private fun readKeyInfos() {
        keyInfoStart = keyHeaderEnd
        val packed = source.read(keyInfoStart, keyHeader.keyInfoPackedSize.toInt())
        keyInfoList.addAll(decodeKeyInfo(packed))
        keyInfoEnd = keyInfoStart + keyHeader.keyInfoPackedSize
        if (keyInfoList.size.toLong() != keyHeader.keywordBlocksNum) {
            throw MdictFormatException(
                "key info list length ${keyInfoList.size} != keyword blocks ${keyHeader.keywordBlocksNum}"
            )
        }
        keyBlockStart = keyInfoEnd
    }

    /**
     * `mdict-base.js:511-618` — unpack the key-info table and build one descriptor
     * per key block.
     *
     * The v2.0 envelope is `[02 00 00 00][adler32][zlib payload]`. The reference
     * detects it with `keyInfoBuff.subarray(0, 4).join('') == '2000'`
     * (`mdict-base.js:514`), i.e. it concatenates the four *decimal* byte values —
     * which only ever equals the string `"2000"` for the little-endian integer 2.
     * Comparing the LE u32 directly is the same test without the accident.
     */
    private fun decodeKeyInfo(buff: ByteArray): List<KeyBlockInfo> {
        var data = buff
        if (meta.encrypt == 2) {
            // mdict-base.js:517-519 — key-info blocks only, record blocks use type 1.
            data = MdictCrypto.mdxDecrypt(data)
        }
        if (meta.version >= 2.0 && Bytes.leUInt32(data, 0) == COMPRESSION_ZLIB) {
            data = inflate(data, keyHeader.keyInfoUnpackSize, "key info")
        }

        val numWidth = meta.numWidth
        val halfWidth = numWidth / 4
        val list = ArrayList<KeyBlockInfo>(keyHeader.keywordBlocksNum.toInt())
        var indexOffset = 0
        var entriesCount = 0L
        var packAccu = 0L
        var unpackAccu = 0L
        for (kbCount in 0 until keyHeader.keywordBlocksNum.toInt()) {
            val blockWordCount = Bytes.b2n(data, indexOffset, numWidth); indexOffset += numWidth
            var firstWordSize = Bytes.b2n(data, indexOffset, halfWidth).toInt(); indexOffset += halfWidth
            firstWordSize = adjustWordSize(firstWordSize)
            val firstWord = meta.encoding.decode(data, indexOffset, firstWordSize); indexOffset += firstWordSize
            var lastWordSize = Bytes.b2n(data, indexOffset, halfWidth).toInt(); indexOffset += halfWidth
            lastWordSize = adjustWordSize(lastWordSize)
            val lastWord = meta.encoding.decode(data, indexOffset, lastWordSize); indexOffset += lastWordSize
            val packSize = Bytes.b2n(data, indexOffset, numWidth); indexOffset += numWidth
            val unpackSize = Bytes.b2n(data, indexOffset, numWidth); indexOffset += numWidth

            list.add(
                KeyBlockInfo(
                    firstKey = firstWord,
                    lastKey = lastWord,
                    keyBlockPackSize = packSize,
                    keyBlockPackAccumulator = packAccu,
                    keyBlockUnpackSize = unpackSize,
                    keyBlockUnpackAccumulator = unpackAccu,
                    keyBlockEntriesNum = blockWordCount,
                    keyBlockEntriesNumAccumulator = entriesCount,
                    keyBlockInfoIndex = kbCount,
                )
            )
            entriesCount += blockWordCount
            packAccu += packSize
            unpackAccu += unpackSize
        }
        if (packAccu != keyHeader.keywordBlockPackedSize) {
            throw MdictFormatException(
                "key block packed size mismatch: descriptors=$packAccu header=${keyHeader.keywordBlockPackedSize}"
            )
        }
        return list
    }

    /**
     * `mdict-base.js:550-581` — the stored word sizes are *length minus one* for
     * v2.0+ and raw lengths before that, then doubled for UTF-16 (the terminator is
     * not counted for UTF-16 but is for UTF-8, hence the `+1`.
     */
    private fun adjustWordSize(size: Int): Int {
        val utf16 = meta.encoding == MdictEncoding.UTF16LE
        return if (meta.version >= 2.0) {
            if (utf16) (size + 1) * 2 else size + 1
        } else {
            if (utf16) size * 2 else size
        }
    }

    // ------------------------------------------------------------------ STEP 4

    /** `mdict-base.js:657-683` — read every key block, in order (slow but needed to look up). */
    private fun readKeyBlocks() {
        var kbStart = keyBlockStart
        val all = ArrayList<KeyWordItem>()
        for (info in keyInfoList) {
            if (kbStart != info.keyBlockPackAccumulator + keyBlockStart) {
                throw MdictFormatException("key block ${info.keyBlockInfoIndex} offset drift")
            }
            val packed = source.read(kbStart, info.keyBlockPackSize.toInt())
            val block = unpackKeyBlock(packed, info.keyBlockUnpackSize, info.keyBlockInfoIndex)
            val items = splitKeyBlock(block, info.keyBlockInfoIndex)
            // mdict-base.js:670-672 — the last keyword of the previous block learns
            // its end offset from the first item of this block (the key-block split
            // cannot know it: record offsets are global, not block local).
            if (all.isNotEmpty() && all.last().recordEndOffset == -1L && items.isNotEmpty()) {
                all.last().recordEndOffset = items.first().recordStartOffset
            }
            all.addAll(items)
            kbStart += info.keyBlockPackSize
        }
        if (all.size.toLong() != keyHeader.keywordNum) {
            throw MdictFormatException(
                "keyword count ${all.size} != key header entries ${keyHeader.keywordNum}"
            )
        }
        keywordList.addAll(all)
    }

    /**
     * `mdict-base.js:625-651` — strip the 8-byte envelope and decompress.
     *
     * NOTE: unlike the record path (`mdict.js:141-149`) the reference never
     * decrypts a key block, so `Encrypted="Yes"` dictionaries whose key section is
     * encrypted cannot be read by js-mdict either; the same limitation is kept and
     * documented in `docs/p6-dictionary-architecture.md` §5.
     */
    fun unpackKeyBlock(packed: ByteArray, unpackSize: Long, index: Int = -1): ByteArray {
        val compType = Bytes.leUInt32(packed, 0)
        return when (compType) {
            COMPRESSION_NONE -> {
                if (options.verifyChecksums) {
                    verifyAdler32(packed.copyOfRange(8, packed.size), Bytes.beUInt32(packed, 4), "key block $index")
                }
                packed.copyOfRange(8, packed.size)
            }
            COMPRESSION_LZO -> throw MdictFormatException(lzoMessage("key block $index"))
            COMPRESSION_ZLIB -> inflate(packed, unpackSize, "key block $index")
            else -> throw MdictFormatException(
                "cannot determine the compress type: ${compType.toString(16).padStart(8, '0')}"
            )
        }
    }

    /**
     * `mdict-base.js:288-323` — split one decompressed key block into keywords.
     *
     * Each entry is `numWidth` bytes of definition offset followed by the key text
     * and a terminator (1 byte for UTF-8, 2 for UTF-16); the *end* offset of an
     * entry is the *start* offset of the next one, and the last one in a block is
     * patched later ([readKeyBlocks] / [readRecordInfos]).
     */
    fun splitKeyBlock(keyBlock: ByteArray, keyBlockIdx: Int): List<KeyWordItem> {
        val width = meta.keyTerminatorWidth
        val numWidth = meta.numWidth
        val items = ArrayList<KeyWordItem>()
        var keyStartIndex = 0
        while (keyStartIndex < keyBlock.size) {
            val meaningOffset = Bytes.b2n(keyBlock, keyStartIndex, numWidth)
            var keyEndIndex = -1
            var i = keyStartIndex + numWidth
            while (i < keyBlock.size) {
                val isTerminator = if (width == 1) {
                    keyBlock[i].toInt() == 0
                } else {
                    i + 1 < keyBlock.size && keyBlock[i].toInt() == 0 && keyBlock[i + 1].toInt() == 0
                }
                if (isTerminator) {
                    keyEndIndex = i
                    break
                }
                i += width
            }
            if (keyEndIndex == -1) break
            val keyText = meta.encoding.decode(keyBlock, keyStartIndex + numWidth, keyEndIndex - keyStartIndex - numWidth)
            if (items.isNotEmpty()) {
                items.last().recordEndOffset = meaningOffset
            }
            items.add(KeyWordItem(meaningOffset, keyText, keyBlockIdx, -1L))
            keyStartIndex = keyEndIndex + width
        }
        return items
    }

    /** `mdict-base.js:89-96` — decode just one key block (used by partial lookups). */
    fun lookupPartialKeyBlockListByKeyInfoId(keyInfoId: Int): List<KeyWordItem> {
        val info = keyInfoList[keyInfoId]
        val start = info.keyBlockPackAccumulator + keyBlockStart
        val packed = source.read(start, info.keyBlockPackSize.toInt())
        return splitKeyBlock(unpackKeyBlock(packed, info.keyBlockUnpackSize, keyInfoId), keyInfoId)
    }

    // ------------------------------------------------------------------ STEP 5

    /** `mdict-base.js:693-713` — record block count, entry count and both sizes. */
    private fun readRecordHeader(): RecordHeader {
        recordHeaderStart = keyInfoEnd + keyHeader.keywordBlockPackedSize
        val len = if (meta.version >= 2.0) 4 * 8 else 4 * 4
        recordHeaderEnd = recordHeaderStart + len
        val buff = source.read(recordHeaderStart, len.toInt())
        val numWidth = meta.numWidth
        var offset = 0
        val blocks = Bytes.b2n(buff, offset, numWidth); offset += numWidth
        val entries = Bytes.b2n(buff, offset, numWidth); offset += numWidth
        if (entries != keyHeader.keywordNum) {
            throw MdictFormatException("record header entries $entries != keywords ${keyHeader.keywordNum}")
        }
        val infoComp = Bytes.b2n(buff, offset, numWidth); offset += numWidth
        val blockComp = Bytes.b2n(buff, offset, numWidth)
        return RecordHeader(blocks, entries, infoComp, blockComp)
    }

    // ------------------------------------------------------------------ STEP 6

    /** `mdict-base.js:718-759` — per record block `(packSize, unpackSize)` pairs. */
    private fun readRecordInfos() {
        recordInfoStart = recordHeaderEnd
        val buff = source.read(recordInfoStart, recordHeader.recordInfoCompSize.toInt())
        val numWidth = meta.numWidth
        var offset = 0
        var packedAccu = 0L
        var unpackedAccu = 0L
        for (i in 0 until recordHeader.recordBlocksNum.toInt()) {
            val packSize = Bytes.b2n(buff, offset, numWidth); offset += numWidth
            val unpackSize = Bytes.b2n(buff, offset, numWidth); offset += numWidth
            recordInfoList.add(RecordBlockInfo(packSize, packedAccu, unpackSize, unpackedAccu))
            packedAccu += packSize
            unpackedAccu += unpackSize
        }
        if (offset.toLong() != recordHeader.recordInfoCompSize) {
            throw MdictFormatException(
                "record info consumed $offset bytes, header says ${recordHeader.recordInfoCompSize}"
            )
        }
        if (packedAccu != recordHeader.recordBlockCompSize) {
            throw MdictFormatException(
                "record block packed size mismatch: info=$packedAccu header=${recordHeader.recordBlockCompSize}"
            )
        }
        // mdict-base.js:753-755 — the very last keyword ends where the record space ends.
        if (keywordList.isNotEmpty() && recordInfoList.isNotEmpty()) {
            val last = recordInfoList.last()
            keywordList.last().recordEndOffset = last.unpackAccumulatorOffset + last.unpackSize
        }
        recordInfoEnd = recordInfoStart + recordHeader.recordInfoCompSize
        recordBlockStart = recordInfoEnd
    }

    // ---------------------------------------------------------------- lookup

    /**
     * `mdict.js:30-61` — binary search over the sorted keyword list.
     *
     * Kept quirk: with `isAssociate = true` a miss still returns the nearest entry
     * (`list[mid]`), which is what `associate` / `suggest` / `fuzzySearch` build on
     * (`mdx.js:62-129`). Without it a miss returns `null`.
     */
    fun lookupKeyBlockByWord(word: String, isAssociate: Boolean = false): KeyWordItem? {
        val list = keywordList
        if (list.isEmpty()) return null
        var left = 0
        var right = list.size - 1
        var mid = 0
        while (left <= right) {
            mid = left + ((right - left) shr 1)
            val result = comp(word, list[mid].keyText)
            if (result > 0) {
                left = mid + 1
            } else if (result == 0) {
                break
            } else {
                right = mid - 1
            }
        }
        if (comp(word, list[mid].keyText) != 0 && !isAssociate) return null
        return list[mid]
    }

    /**
     * `mdict.js:102-123` — locate the key *block* whose `[firstKey, lastKey]` range
     * contains `word`. Unused by the reference lookup path (it walks the full sorted
     * keyword list instead, see `mdict.js:188-196`) but kept for partial reads.
     */
    fun findKeyInfoIndex(word: String): Int {
        val list = keyInfoList
        var left = 0
        var right = list.size - 1
        var mid = 0
        while (left <= right) {
            mid = left + ((right - left) shr 1)
            if (comp(word, list[mid].firstKey) >= 0 && comp(word, list[mid].lastKey) <= 0) return mid
            if (comp(word, list[mid].lastKey) >= 0) left = mid + 1 else right = mid - 1
        }
        return -1
    }

    /** `mdict.js:166-180` — which record block holds `recordStart`. */
    fun reduceRecordBlockInfo(recordStart: Long): Int {
        var left = 0
        var right = recordInfoList.size - 1
        var mid = 0
        while (left <= right) {
            mid = left + ((right - left) shr 1)
            if (recordStart >= recordInfoList[mid].unpackAccumulatorOffset) left = mid + 1 else right = mid - 1
        }
        return left - 1
    }

    /**
     * `mdict.js:73-81` — read the definition bytes for one keyword.
     *
     * Only the single record block holding the entry is read and decompressed, so a
     * lookup costs one random read plus one zlib pass (the whole-dictionary read in
     * `mdict-base.js:765` is intentionally not ported).
     */
    fun lookupRecordByKeyBlock(item: KeyWordItem): ByteArray? {
        val index = reduceRecordBlockInfo(item.recordStartOffset)
        if (index < 0 || index >= recordInfoList.size) return null
        val info = recordInfoList[index]
        val raw = source.read(recordBlockStart + info.packAccumulateOffset, info.packSize.toInt())
        val block = decompressBuff(raw, info.unpackSize, index)
        val start = item.recordStartOffset - info.unpackAccumulatorOffset
        val end = item.recordEndOffset - info.unpackAccumulatorOffset
        if (start < 0 || end > block.size || start > end) {
            throw MdictFormatException(
                "definition range [$start,$end) outside record block ${block.size} for '${item.keyText}'"
            )
        }
        return block.copyOfRange(start.toInt(), end.toInt())
    }

    /** `mdict.js:124-161` — record block decompression (with decryption when `Encrypted="Yes"`). */
    fun decompressBuff(recordBuffer: ByteArray, unpackSize: Long, index: Int = -1): ByteArray {
        val compType = Bytes.leUInt32(recordBuffer, 0)
        if (compType == COMPRESSION_NONE) {
            // mdict.js:134-136 — type 0 blocks are stored verbatim and NOT decrypted.
            val payload = recordBuffer.copyOfRange(8, recordBuffer.size)
            if (options.verifyChecksums) {
                verifyAdler32(payload, Bytes.beUInt32(recordBuffer, 4), "record block $index")
            }
            return payload
        }
        // mdict.js:141-146 — the encrypted envelope keeps [0:8] (type + adler32), so
        // decrypting first leaves both fields usable for the inflate below.
        val decrypted = if (meta.encrypt == 1) MdictCrypto.mdxDecrypt(recordBuffer) else recordBuffer
        return when (compType) {
            COMPRESSION_LZO -> throw MdictFormatException(lzoMessage("record block $index"))
            COMPRESSION_ZLIB -> inflate(decrypted, unpackSize, "record block $index")
            else -> throw MdictFormatException(
                "cannot determine the compress type: ${compType.toString(16).padStart(8, '0')}"
            )
        }
    }

    // ------------------------------------------------------------ key handling

    /** `mdict-base.js:160-172` — normalise a key for fuzzy comparison. */
    fun strip(key: String): String {
        var k = key
        if (isStripKey()) k = stripKeyRegex(meta.ext).replace(k, "")
        if (!isKeyCaseSensitive()) k = k.lowercase()
        if (meta.ext == "mdd") {
            k = stripKeyRegex("mdd").replace(k, "")
            k = k.replace('_', '!')
        }
        return k.lowercase().trim()
    }

    private fun isKeyCaseSensitive(): Boolean = options.isCaseSensitive || meta.headerKeyCaseSensitive

    private fun isStripKey(): Boolean = options.isStripKey || meta.headerStripKey

    /**
     * `mdict-base.js:173-175` — the reference uses `localeCompare`; the collator is
     * the JVM equivalent, and the code-point tie-break keeps the order total (which
     * the binary searches above require).
     */
    fun comp(word1: String, word2: String): Int {
        val result = collator.compare(word1, word2)
        return if (result != 0) result else word1.compareTo(word2)
    }

    // ------------------------------------------------------------------ helpers

    /** `utils.js:592-596` — MDX v2 zlib envelope, checked before every inflate. */
    private fun inflate(envelope: ByteArray, expectedSize: Long, what: String): ByteArray {
        if (envelope.size <= 8) throw MdictFormatException("$what: compressed block too short")
        val payload = envelope.copyOfRange(8, envelope.size)
        val inflated = inflatePayload(payload, expectedSize, what)
        // The adler32 field covers the DECOMPRESSED block, not the compressed bytes
        // (mdict-analysis: "adler32 checksum of decompressed record block").
        if (options.verifyChecksums) {
            verifyAdler32(inflated, Bytes.beUInt32(envelope, 4), what)
        }
        return inflated
    }

    private fun inflatePayload(payload: ByteArray, expectedSize: Long, what: String): ByteArray {
        if (expectedSize > Int.MAX_VALUE) {
            throw MdictFormatException("$what: unpacked size $expectedSize exceeds JVM array limit")
        }
        val inflater = Inflater()
        try {
            inflater.setInput(payload)
            val out = ByteArray(expectedSize.toInt())
            var written = 0
            while (written < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, written, out.size - written)
                // n == 0 means the inflater cannot make progress (truncated stream,
                // dictionary needed, ...): stop instead of spinning on the same input.
                if (n == 0) break
                written += n
            }
            if (written != out.size) {
                throw MdictFormatException("$what: inflated $written bytes, expected ${out.size}")
            }
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw MdictFormatException("$what: zlib payload is corrupt (${e.message})")
        } finally {
            inflater.end()
        }
    }

    /**
     * Deviation 1: the reference marks every adler32 check as `TODO`
     * (`mdict-base.js:341`, `525`, `645`, `786`). Cheap integrity check that turns a
     * truncated download into a clear error instead of a broken definition.
     */
    private fun verifyAdler32(payload: ByteArray, expected: Long, what: String) {
        val adler = Adler32()
        adler.update(payload)
        val actual = adler.value
        if (actual != expected) {
            throw MdictFormatException(
                "$what: adler32 mismatch (file=$expected computed=$actual) — file may be truncated"
            )
        }
    }

    private fun lzoMessage(what: String): String =
        "$what: LZO compression (type 1) is not supported — only MDX v1.x uses it, " +
            "v2.0+ dictionaries are zlib; see docs/p6-dictionary-architecture.md §5"

    override fun close() {
        source.close()
        keywordList.clear()
        keyInfoList.clear()
        recordInfoList.clear()
    }

    companion object {
        /** `mdict-base.js:632/640/789` — block compression type, little-endian u32. */
        const val COMPRESSION_NONE = 0L
        const val COMPRESSION_LZO = 1L
        const val COMPRESSION_ZLIB = 2L

        /** `utils.js:2-5` `REGEXP_STRIPKEY` — characters dropped when comparing keys. */
        private val STRIP_MDX = Regex("[().,\\-&、 '/\\\\@_\$!]")
        private val STRIP_MDD = Regex("([.][^.]*$)|[()., '/@]")

        private fun stripKeyRegex(ext: String): Regex =
            if (ext == "mdd") STRIP_MDD else STRIP_MDX
    }
}
