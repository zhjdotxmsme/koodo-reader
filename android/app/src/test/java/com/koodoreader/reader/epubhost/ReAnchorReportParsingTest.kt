package com.koodoreader.reader.epubhost

import com.koodoreader.reader.shell.HighlightRemap
import com.koodoreader.reader.shell.ReAnchorReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReAnchorReportParsingTest {

    @Test
    fun `a full report parses into remaps and failures`() {
        val report = FoliateHostBridge.parseReAnchorReport(
            """
            {"anchored":2,
             "reanchored":[{"old":"epubcfi(a)","cfi":"epubcfi(b)"}],
             "failed":["epubcfi(z)"]}
            """.trimIndent(),
        )
        assertEquals(2, report.anchored)
        assertEquals(1, report.remaps.size)
        assertEquals("epubcfi(a)", report.remaps.single().oldCfi)
        assertEquals("epubcfi(b)", report.remaps.single().newCfi)
        assertEquals(listOf("epubcfi(z)"), report.failed)
    }

    @Test
    fun `blank and malformed payloads degrade to an empty report`() {
        assertEquals(ReAnchorReport(), FoliateHostBridge.parseReAnchorReport(""))
        assertEquals(ReAnchorReport(), FoliateHostBridge.parseReAnchorReport("not json"))
    }

    @Test
    fun `identity remaps and blank entries are dropped at the wire`() {
        val report = FoliateHostBridge.parseReAnchorReport(
            """
            {"anchored":0,
             "reanchored":[
                {"old":"same","cfi":"same"},
                {"old":"","cfi":"x"},
                {"old":"a","cfi":"b"}],
             "failed":["","epubcfi(keep)"]}
            """.trimIndent(),
        )
        assertEquals(listOf(HighlightRemap("a", "b")), report.remaps)
        assertEquals(listOf("epubcfi(keep)"), report.failed)
    }

    @Test
    fun `missing arrays parse as empty`() {
        val report = FoliateHostBridge.parseReAnchorReport("""{"anchored":5}""")
        assertEquals(5, report.anchored)
        assertTrue(report.remaps.isEmpty())
        assertTrue(report.failed.isEmpty())
    }
}
