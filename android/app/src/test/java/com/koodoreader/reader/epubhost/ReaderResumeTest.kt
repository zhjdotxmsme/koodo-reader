package com.koodoreader.reader.epubhost

import com.koodoreader.engine.layout.TextMeasurers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 阅读进度恢复逻辑测试（纯函数 [resumePage]）：
 * 打开书时按存储 CFI 落页，无进度/无效 CFI 回第 0 页。
 */
class ReaderResumeTest {

    @TempDir
    lateinit var dir: File

    private fun session(): TextBookSession {
        val body = (1..40).joinToString("\n\n") { "段落 $it 的正文内容，用于产生足够多的分页。" }
        val f = File(dir, "progress.txt").apply { writeText(body, Charsets.UTF_8) }
        return TextBookSession.open(f, 400f, 700f, TextMeasurers.mono())
    }

    @Test
    fun `resumes to the page of the stored cfi`() {
        session().use { s ->
            val target = (s.pageCount - 1).coerceAtLeast(0)
            val cfi = s.cfiForPage(target) ?: return
            assertEquals(target, s.resumePage(cfi))
        }
    }

    @Test
    fun `no stored progress starts at page zero`() {
        session().use { s ->
            assertEquals(0, s.resumePage(null))
            assertEquals(0, s.resumePage(""))
            assertEquals(0, s.resumePage("   "))
        }
    }

    @Test
    fun `foreign or malformed cfi falls back to page zero`() {
        session().use { s ->
            assertEquals(0, s.resumePage("not a cfi"))
            assertEquals(0, s.resumePage("epubcfi(/6/99!/2/0)"))
        }
    }

    @Test
    fun `round-trip through every page`() {
        session().use { s ->
            for (p in 0 until s.pageCount) {
                val cfi = s.cfiForPage(p) ?: continue
                assertEquals(p, s.resumePage(cfi), "page $p")
            }
        }
    }
}
