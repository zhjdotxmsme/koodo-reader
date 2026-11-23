package com.koodoreader.reader.epubhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoliateSelectionMenuTest {

    @Test
    fun `payload without a point yields null`() {
        assertNull(FoliateSelectionMenu.payloadPoint(JSONObject("""{"excerpt":"字","cfi":"epubcfi(/6/2)"}""")))
    }

    @Test
    fun `payload with a point parses to offset`() {
        val point = FoliateSelectionMenu.payloadPoint(
            JSONObject("""{"excerpt":"字","cfi":"epubcfi(/6/2)","px":12.5,"py":88}"""),
        )
        assertEquals(12.5f, point!!.x, 0.001f)
        assertEquals(88f, point.y, 0.001f)
    }

    @Test
    fun `non-finite points are rejected`() {
        assertNull(
            FoliateSelectionMenu.payloadPoint(
                JSONObject("""{"excerpt":"字","cfi":"c","px":"NaN","py":88}"""),
            ),
        )
    }

    @Test
    fun `anchor maps css px through bounds and density`() {
        val anchor = FoliateSelectionMenu.anchorPx(
            point = androidx.compose.ui.geometry.Offset(12.5f, 88f),
            boundsLeft = 100f,
            boundsTop = 200f,
            density = 2f,
        )
        assertEquals(125f, anchor.x, 0.001f)
        assertEquals(376f, anchor.y, 0.001f)
    }

    @Test
    fun `edges payload parses to start and end offsets`() {
        val edges = FoliateSelectionMenu.parseSelectionEdges("""{"sx":10,"sy":20,"ex":30,"ey":40}""")
        assertEquals(10f, edges!!.start.x, 0.001f)
        assertEquals(20f, edges.start.y, 0.001f)
        assertEquals(30f, edges.end.x, 0.001f)
        assertEquals(40f, edges.end.y, 0.001f)
    }

    @Test
    fun `edges payload without all four fields is rejected`() {
        assertNull(FoliateSelectionMenu.parseSelectionEdges("""{"sx":10,"sy":20}"""))
    }

    @Test
    fun `null and blank edges payloads yield null`() {
        assertNull(FoliateSelectionMenu.parseSelectionEdges("null"))
        assertNull(FoliateSelectionMenu.parseSelectionEdges(""))
        assertNull(FoliateSelectionMenu.parseSelectionEdges("not json"))
    }
}
