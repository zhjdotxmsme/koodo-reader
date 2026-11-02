# Android Import and Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一 Android 文件识别、可靠导入、外部直达阅读及元数据解析/编辑，同时保持现有原生阅读路径可用。

**Architecture:** `core:importer` 拥有格式识别、原子落盘和元数据模型；Android 宿主只负责 SAF、Room 和导航。外部 Intent 与书架导入复用同一管线，重复导入返回已有书籍键。

**Tech Stack:** Kotlin 1.9.24、JDK 17、JUnit 5、Room、Compose、Android SAF。

## Global Constraints

- 不修改用户原始 SAF 文件，书籍仍复制到应用私有目录。
- CBR 明确不支持；不复制、不写 Room、不生成占位书。
- 元数据编辑只写 Room，不改写书籍副本、不改变 MD5。
- 沿用 `BookEntity` 和备份字段，不新增 Room 列。
- 不迁移、不删除旧 WebView 数据。
- 每个任务先写失败测试，再做最小实现；不得在真机未验收时宣称全面收口完成。

## File Map

- `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/BookFormat.kt`：支持格式、明确不支持格式及 canonical 扩展名。
- `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/FormatDetector.kt`：扩展名/MIME/内容联合识别。
- `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/BookMetadata.kt`：统一元数据模型与编辑校验。
- `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/`：EPUB、MOBI、DOCX、FB2、HTML 和基础元数据解析器。
- `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/ImportPipeline.kt`：临时文件、单遍 MD5、去重结果和失败清理。
- `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataViewModel.kt`：Room 元数据读取与保存。
- `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataDialog.kt`：详情和编辑 UI。
- `android/app/src/main/java/com/koodoreader/reader/shell/ExternalBookOpener.kt`：等待导入并返回导航目标。
- 测试与各文件同包放在对应 `src/test` 或 `app/src/test` 下。

---

### Task 1: 统一格式注册表与内容嗅探

**Files:**
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/BookFormat.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/FormatDetector.kt`
- Modify: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/BookRules.kt`
- Test: `android/core/importer/src/test/kotlin/com/koodoreader/core/importer/FormatDetectorTest.kt`

**Interfaces:**
- Produces: `BookFormat.detect(name: String?, mime: String?, openStream: () -> InputStream): FormatDetection`。
- `FormatDetection` 为 `Supported(BookFormat)`、`Unsupported(reason: String)` 或 `Unknown`；调用方只使用 `Supported.format.extension` 作为 canonical 扩展名。

- [ ] **Step 1: 写失败测试，覆盖错误后缀、无扩展名、通用 MIME 和 CBR**

```kotlin
@Test
fun `content wins over misleading name and mime`() {
    val pdf = "%PDF-1.7\n".byteInputStream()
    val detected = BookFormat.detect("attachment.bin", "application/octet-stream") { pdf }
    assertEquals(FormatDetection.Supported(BookFormat.PDF), detected)
}

@Test
fun `rar is explicitly unsupported before import`() {
    val detected = BookFormat.detect("book.bin", null) {
        byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1A, 0x07, 0x01, 0x00).inputStream()
    }
    assertEquals(FormatDetection.Unsupported("CBR"), detected)
}
```

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

Run: `./gradlew :core:importer:test --tests '*FormatDetectorTest'`
Working directory: `android/`
Expected: FAIL，提示 `BookFormat`/`FormatDetection` 不存在。

- [ ] **Step 3: 实现格式表和最小嗅探器**

```kotlin
enum class BookFormat(val extension: String, val mime: String) {
    EPUB("epub", "application/epub+zip"), PDF("pdf", "application/pdf"),
    TXT("txt", "text/plain"), MOBI("mobi", "application/x-mobipocket-ebook"),
    AZW3("azw3", "application/vnd.amazon.ebook"), AZW("azw", "application/vnd.amazon.ebook"),
    HTM("htm", "text/html"), HTML("html", "text/html"), XML("xml", "application/xml"),
    XHTML("xhtml", "application/xhtml+xml"), MHTML("mhtml", "message/rfc822"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MD("md", "text/markdown"), FB2("fb2", "application/x-fictionbook+xml"),
    CBZ("cbz", "application/x-cbz"), CBT("cbt", "application/x-cbt"), CB7("cb7", "application/x-cb7");

    companion object {
        fun detect(name: String?, mime: String?, openStream: () -> java.io.InputStream): FormatDetection {
            val header = openStream().use { it.readNBytes(8192) }
            if (header.startsWith(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte()))) {
                return FormatDetection.Unsupported("CBR")
            }
            if (header.startsWith("%PDF".toByteArray())) return FormatDetection.Supported(PDF)
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val byName = entries.firstOrNull { it.extension == ext }
            val byMime = entries.firstOrNull { it.mime.equals(mime?.substringBefore(';'), true) }
            return (byName ?: byMime)?.let(FormatDetection::Supported) ?: FormatDetection.Unknown
        }
    }
}

sealed interface FormatDetection {
    data class Supported(val format: BookFormat) : FormatDetection
    data class Unsupported(val reason: String) : FormatDetection
    data object Unknown : FormatDetection
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
```

在后续实现同一任务的 ZIP 分支中，用 fresh stream 读取条目名：`mimetype=application/epub+zip` → EPUB，`[Content_Types].xml` + `word/document.xml` → DOCX，只有图片条目 → CBZ；MOBI 检查 PalmDB 头，FB2/HTML/MHTML 检查解码后的根结构。探针流每次调用都必须重新打开。

- [ ] **Step 4: 补齐 ZIP、PalmDB 和文本根结构测试并运行**

Run: `./gradlew :core:importer:test --tests '*FormatDetectorTest'`
Expected: PASS；至少包含 EPUB、DOCX、CBZ、PDF、RAR、MOBI、FB2、HTML、TXT 和无扩展名用例。

- [ ] **Step 5: 提交**

```bash
git add android/core/importer && git commit -m "feat(android): detect book formats from content"
```

### Task 2: 原子导入、单遍 MD5 与可导航结果

**Files:**
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/ImportOutcome.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/AtomicBookStore.kt`
- Modify: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/ImportPipeline.kt`
- Test: `android/core/importer/src/test/kotlin/com/koodoreader/core/importer/AtomicImportTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `FormatDetection`。
- Produces: `ImportOutcome.Success(record: BookRecord, duplicate: Boolean)` 和 `ImportOutcome.Failure(reason: ImportFailure)`；`BookRecord.key` 对重复书表示已有键。
- Produces: `AtomicBookStore.copy(source: InputStream, finalName: String): StoredCopy`，其中 `StoredCopy(file: File, md5: String)`。

- [ ] **Step 1: 写失败测试，断言失败无残留、重复保留已有键**

```kotlin
@Test
fun `failed copy removes partial file`() {
    val dir = createTempDirectory().toFile()
    val broken = object : InputStream() {
        private var calls = 0
        override fun read(): Int = if (calls++ == 0) 1 else throw IOException("disk full")
    }
    assertThrows<IOException> { AtomicBookStore(dir).copy(broken, "1.txt") }
    assertEquals(emptyList<String>(), dir.listFiles()?.map { it.name }.orEmpty())
}
```

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :core:importer:test --tests '*AtomicImportTest'`
Expected: FAIL，`AtomicBookStore` 不存在。

- [ ] **Step 3: 实现临时文件复制与摘要同步计算**

```kotlin
data class StoredCopy(val file: File, val md5: String)

class AtomicBookStore(private val booksDir: File) {
    fun copy(source: InputStream, finalName: String): StoredCopy {
        require(booksDir.isDirectory || booksDir.mkdirs()) { "cannot create books directory" }
        val part = File(booksDir, "$finalName.part")
        val digest = java.security.MessageDigest.getInstance("MD5")
        try {
            source.use { input -> part.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            } }
            val final = File(booksDir, finalName)
            if (!part.renameTo(final)) throw IOException("cannot commit book file")
            return StoredCopy(final, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }
}
```

`ImportPipeline` 在检测到 `Unsupported/Unknown` 时不得调用 `copy`。重复判断发生在 `copy` 返回后；重复时删除新文件并返回数据库已有记录。空间预检使用 `BookSource.size` 与 `booksDir.usableSpace`，未知大小仍依赖流式异常清理。

- [ ] **Step 4: 验证成功、重复、异常和空间不足**

Run: `./gradlew :core:importer:test --tests '*AtomicImportTest' --tests '*ImportPipelineTest'`
Expected: PASS；测试中重复导入后原 `BookRecord.name` 保持编辑值。

- [ ] **Step 5: 提交**

```bash
git add android/core/importer && git commit -m "fix(android): make book imports atomic"
```

### Task 3: 全格式元数据解析

**Files:**
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/BookMetadata.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/MetadataParsers.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/EpubMetadataParser.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/MobiMetadataParser.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/DocxMetadataParser.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/Fb2MetadataParser.kt`
- Create: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/metadata/HtmlMetadataParser.kt`
- Modify: `android/core/importer/src/main/kotlin/com/koodoreader/core/importer/ImportPipeline.kt`
- Modify: `android/core/importer/build.gradle`
- Test: `android/core/importer/src/test/kotlin/com/koodoreader/core/importer/metadata/MetadataParsersTest.kt`

**Interfaces:**
- Produces: `BookMetadata(name: String?, author: String?, description: String?, publisher: String?)`。
- Produces: `MetadataParsers.parse(format: BookFormat, file: File, fallbackName: String): BookMetadata`；失败时返回 `BookMetadata(fallbackName, null, null, null)`。
- PDF 的 Document Info 由 Android/PDFium 宿主 extractor 注入，纯 JVM 模块不加载原生库。

- [ ] **Step 1: 写各格式真实结构测试**

```kotlin
@Test
fun `docx reads core properties`() {
    val file = zipWith("docProps/core.xml", """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Native title</dc:title><dc:creator>Author</dc:creator><dc:description>Description</dc:description></cp:coreProperties>""")
    val data = MetadataParsers.parse(BookFormat.DOCX, file, "fallback")
    assertEquals("Native title", data.name)
    assertEquals("Author", data.author)
}
```

同文件中分别加入 EPUB OPF、MOBI EXTH、FB2 title-info、HTML title/meta 和损坏文件回退用例；TXT/CBZ 只断言文件名回退，不猜测作者。

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :core:importer:test --tests '*MetadataParsersTest'`
Expected: FAIL，元数据类型不存在。

- [ ] **Step 3: 实现统一模型与注册入口**

```kotlin
data class BookMetadata(
    val name: String?, val author: String?, val description: String?, val publisher: String?,
)

object MetadataParsers {
    fun parse(format: BookFormat, file: File, fallbackName: String): BookMetadata = runCatching {
        when (format) {
            BookFormat.EPUB -> EpubMetadataParser.parse(file)
            BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3 -> MobiMetadataParser.parse(file)
            BookFormat.DOCX -> DocxMetadataParser.parse(file)
            BookFormat.FB2 -> Fb2MetadataParser.parse(file)
            BookFormat.HTML, BookFormat.HTM, BookFormat.XHTML, BookFormat.XML, BookFormat.MHTML -> HtmlMetadataParser.parse(file)
            else -> BookMetadata(fallbackName, null, null, null)
        }
    }.getOrElse { BookMetadata(fallbackName, null, null, null) }
}
```

各解析器只读取所需 ZIP 条目或文本头部；复用 `EpubBook`、`MobiParser`、`ZipArchives` 和现有 XML/HTML 工具，不新增解析依赖。`core:importer` 增加对 `:engine:mobi` 的依赖。导入管线仅在所有非空字段中填值，缺失字段继续使用文件名等原默认值。

- [ ] **Step 4: 运行全部导入与元数据测试**

Run: `./gradlew :core:importer:test :engine:mobi:test`
Expected: PASS；损坏元数据不会使 `ImportResult.failures` 增加。

- [ ] **Step 5: 提交**

```bash
git add android/core/importer android/engine/mobi && git commit -m "feat(android): parse book metadata during import"
```

### Task 4: 元数据详情与编辑

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataEditor.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataViewModel.kt`
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/BookMetadataDialog.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/LibraryScreen.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/BookCard.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/BookMetadataEditorTest.kt`

**Interfaces:**
- Consumes: `BookDao.getByKey`、`BookDao.upsert`。
- Produces: `BookMetadataEditor.validate(book: BookEntity, draft: BookMetadata): BookEntity`；书名 trim 后为空时抛 `IllegalArgumentException`。

- [ ] **Step 1: 写编辑规则测试**

```kotlin
@Test
fun `save changes metadata but preserves identity and file fields`() {
    val original = book().copy(md5 = "abc", path = "/books/1.epub", name = "Old")
    val edited = BookMetadataEditor.validate(original, BookMetadata("New", "A", "D", "P"))
    assertEquals("New", edited.name)
    assertEquals(original.key, edited.key)
    assertEquals(original.md5, edited.md5)
    assertEquals(original.path, edited.path)
}

@Test(expected = IllegalArgumentException::class)
fun `blank title is rejected`() {
    BookMetadataEditor.validate(book(), BookMetadata(" ", null, null, null))
}
```

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*BookMetadataEditorTest'`
Expected: FAIL，编辑器不存在。

- [ ] **Step 3: 实现纯校验和 Room 保存**

```kotlin
object BookMetadataEditor {
    fun validate(book: BookEntity, draft: BookMetadata): BookEntity {
        val title = draft.name?.trim().orEmpty()
        require(title.isNotEmpty()) { "Book name must not be blank" }
        return book.copy(
            name = title,
            author = draft.author?.trim()?.ifEmpty { null },
            description = draft.description?.trim()?.ifEmpty { null },
            publisher = draft.publisher?.trim()?.ifEmpty { null },
        )
    }
}
```

`BookMetadataViewModel.save` 在 `viewModelScope` 中读取最新行、调用 `validate` 并 `upsert`。书卡长按菜单增加“书籍详情”；对话框展示只读格式/大小/页数/MD5和四个可编辑字段，保存成功关闭，校验错误显示在对话框内。不重载文件、不更新 MD5。

- [ ] **Step 4: 验证编辑、重启读取和备份字段**

Run: `./gradlew :app:testDebugUnitTest :core:dbio:test`
Expected: PASS；备份 round-trip 后编辑值不变。

- [ ] **Step 5: 提交**

```bash
git add android/app android/core/dbio && git commit -m "feat(android): edit library book metadata"
```

### Task 5: 外部打开直达正文与 CBR 拒绝

**Files:**
- Create: `android/app/src/main/java/com/koodoreader/reader/shell/ExternalBookOpener.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/MainActivity.kt:271-379`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/NativeShellActivity.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ShellDestinations.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ShellNavHost.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/IntentRoutePolicy.kt`
- Test: `android/app/src/test/java/com/koodoreader/reader/shell/ExternalBookOpenerTest.kt`
- Modify: `scripts/check-import-rules.js`

**Interfaces:**
- Consumes: Task 2 的 `ImportOutcome`。
- Produces: `ExternalBookOpener.open(uri): Flow<ExternalOpenState>`，状态为 `Importing`、`Open(bookKey)`、`Failed(message)`。
- `NativeShellActivity` 接收 `extra_book_key`，Compose 启动后导航 `ShellNav.reader(bookKey)`。

- [ ] **Step 1: 写状态转换测试**

```kotlin
@Test
fun `duplicate import opens the existing book`() {
    val state = ExternalBookOpener.stateAfter(ImportOutcome.Success(book("stable-key"), duplicate = true))
    assertEquals(ExternalOpenState.Open("stable-key"), state)
}

@Test
fun `unsupported file does not navigate`() {
    val state = ExternalBookOpener.stateAfter(ImportOutcome.Failure(ImportFailure.UNSUPPORTED))
    assertEquals(ExternalOpenState.Failed("Unsupported book format"), state)
}
```

- [ ] **Step 2: 确认测试失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExternalBookOpenerTest'`
Expected: FAIL，状态类型不存在。

- [ ] **Step 3: 实现等待导入和显式导航**

```kotlin
sealed interface ExternalOpenState {
    data object Importing : ExternalOpenState
    data class Open(val bookKey: String) : ExternalOpenState
    data class Failed(val message: String) : ExternalOpenState
}

object ExternalBookOpener {
    fun stateAfter(result: ImportOutcome): ExternalOpenState = when (result) {
        is ImportOutcome.Success -> ExternalOpenState.Open(result.record.key)
        is ImportOutcome.Failure -> ExternalOpenState.Failed(result.reason.userMessage)
    }
}
```

`MainActivity` 删除立即 `startActivity` 的旧行为，收集状态后携带 `extra_book_key` 启动原生壳；失败只显示错误。`NativeShellActivity` 在 NavHost 就绪后消费一次性 extra。`IntentRoutePolicy` 不再把 CBR 标为可导入路径，格式守卫使用 `SUPPORTED - EXPLICITLY_UNSUPPORTED` 规则。

- [ ] **Step 4: 跑外部路由、格式守卫和全量相关测试**

Run: `./gradlew :app:testDebugUnitTest :core:importer:test && node scripts/check-import-rules.js`
Expected: PASS；测试确认 CBR 无 Room 写入，外部新书和重复书均产生同一阅读路由。

- [ ] **Step 5: 提交**

```bash
git add android/app android/core/importer scripts/check-import-rules.js && git commit -m "fix(android): open external books after import"
```
