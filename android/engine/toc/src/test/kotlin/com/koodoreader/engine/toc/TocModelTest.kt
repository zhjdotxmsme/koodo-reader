package com.koodoreader.engine.toc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [TocModel] and [TocNode].
 *
 * Covers:
 *  - Multi-level TOC parsing (3-level nesting)
 *  - Pre-order flat node traversal
 *  - [lookupByHref] exact match and miss
 *  - [lookupByTitle] exact and prefix match
 */
class TocModelTest {

    // --- TocModel.build with resolver ---

    @Test
    fun `build resolves hrefs via resolver`() {
        val rawRoot = listOf(
            TocNode(
                title = "Chapter 1",
                href = "ch1.xhtml",
                level = 0,
                cfiTarget = null,
                children = listOf(
                    TocNode(
                        title = "Section 1.1",
                        href = "ch1.xhtml#sec1",
                        level = 1,
                        cfiTarget = null,
                        children = emptyList(),
                    ),
                ),
            ),
        )

        val resolver = EpubPackageResolver { href ->
            when (href) {
                "ch1.xhtml" -> "epubcfi(/6/2!/4/2/2:0)"
                "ch1.xhtml#sec1" -> "epubcfi(/6/2!/4/2/2:100)"
                else -> null
            }
        }

        val model = TocModel.build(rawRoot, resolver)

        assertEquals("epubcfi(/6/2!/4/2/2:0)", model.rootNodes[0].cfiTarget)
        assertEquals("epubcfi(/6/2!/4/2/2:100)", model.rootNodes[0].children[0].cfiTarget)
    }

    @Test
    fun `build leaves cfiTarget null when resolver returns null`() {
        val rawRoot = listOf(
            TocNode(
                title = "Bad Link",
                href = "missing.xhtml",
                level = 0,
                cfiTarget = null,
                children = emptyList(),
            ),
        )

        val resolver = EpubPackageResolver { null }
        val model = TocModel.build(rawRoot, resolver)

        assertNull(model.rootNodes[0].cfiTarget)
    }

    // --- fromResolved ---

    @Test
    fun `fromResolved creates model with given nodes`() {
        val nodes = listOf(
            TocNode(
                title = "Root",
                href = "root.xhtml",
                level = 0,
                cfiTarget = "epubcfi(/6/2!)",
                children = emptyList(),
            ),
        )
        val model = TocModel.fromResolved(nodes)

        assertEquals(1, model.rootNodes.size)
        assertEquals(1, model.flatNodes.size)
        assertEquals("Root", model.flatNodes[0].title)
    }

    // --- Flat node traversal (pre-order) ---

    @Test
    fun `flatNodes is pre-order traversal`() {
        // Structure:
        // Chapter 1
        //   Section 1.1
        //     Subsection 1.1.1
        //   Section 1.2
        // Chapter 2
        val root = listOf(
            TocNode(
                title = "Chapter 1",
                href = "ch1.xhtml",
                level = 0,
                cfiTarget = null,
                children = listOf(
                    TocNode(
                        title = "Section 1.1",
                        href = "ch1.xhtml#sec1",
                        level = 1,
                        cfiTarget = null,
                        children = listOf(
                            TocNode(
                                title = "Subsection 1.1.1",
                                href = "ch1.xhtml#sub111",
                                level = 2,
                                cfiTarget = null,
                                children = emptyList(),
                            ),
                        ),
                    ),
                    TocNode(
                        title = "Section 1.2",
                        href = "ch1.xhtml#sec12",
                        level = 1,
                        cfiTarget = null,
                        children = emptyList(),
                    ),
                ),
            ),
            TocNode(
                title = "Chapter 2",
                href = "ch2.xhtml",
                level = 0,
                cfiTarget = null,
                children = emptyList(),
            ),
        )

        val model = TocModel.fromResolved(root)

        // Expected pre-order: Chapter1, Section1.1, Subsection1.1.1, Section1.2, Chapter2
        assertEquals(5, model.flatNodes.size)
        assertEquals("Chapter 1", model.flatNodes[0].title)
        assertEquals("Section 1.1", model.flatNodes[1].title)
        assertEquals("Subsection 1.1.1", model.flatNodes[2].title)
        assertEquals("Section 1.2", model.flatNodes[3].title)
        assertEquals("Chapter 2", model.flatNodes[4].title)
    }

    // --- lookupByHref ---

    @Test
    fun `lookupByHref finds exact match`() {
        val model = TocModel.fromResolved(
            listOf(
                TocNode("A", "a.xhtml", 0, null, emptyList()),
                TocNode("B", "b.xhtml#anchor", 0, null, emptyList()),
            ),
        )
        assertEquals("A", model.lookupByHref("a.xhtml")?.title)
        assertEquals("B", model.lookupByHref("b.xhtml#anchor")?.title)
    }

    @Test
    fun `lookupByHref returns null on miss`() {
        val model = TocModel.fromResolved(
            listOf(TocNode("A", "a.xhtml", 0, null, emptyList())),
        )
        assertNull(model.lookupByHref("a.xhtml ")) // trailing space
        assertNull(model.lookupByHref("a.xhtml#other"))
        assertNull(model.lookupByHref(""))
        assertNull(model.lookupByHref("nonexistent"))
    }

    // --- lookupByTitle ---

    @Test
    fun `lookupByTitle exact match`() {
        val model = TocModel.fromResolved(
            listOf(
                TocNode("Kotlin", "a.xhtml", 0, null, emptyList()),
                TocNode("Kotlin Standard", "b.xhtml", 0, null, emptyList()),
                TocNode("Java", "c.xhtml", 0, null, emptyList()),
            ),
        )
        val results = model.lookupByTitle("Kotlin")
        assertEquals(1, results.size)
        assertEquals("Kotlin", results[0].title)
    }

    @Test
    fun `lookupByTitle prefix match`() {
        val model = TocModel.fromResolved(
            listOf(
                TocNode("Chapter One", "a.xhtml", 0, null, emptyList()),
                TocNode("Chapter Two", "b.xhtml", 0, null, emptyList()),
                TocNode("Chapter Three", "c.xhtml", 0, null, emptyList()),
                TocNode("Introduction", "d.xhtml", 0, null, emptyList()),
            ),
        )
        val results = model.lookupByTitle("Chapter")
        assertEquals(3, results.size)
    }

    @Test
    fun `lookupByTitle case insensitive`() {
        val model = TocModel.fromResolved(
            listOf(TocNode("KOTLIN", "a.xhtml", 0, null, emptyList())),
        )
        assertEquals(1, model.lookupByTitle("kotlin").size)
        assertEquals(1, model.lookupByTitle("KOTLIN").size)
        assertEquals(1, model.lookupByTitle("KoTlIn").size)
    }

    @Test
    fun `lookupByTitle empty query returns empty`() {
        val model = TocModel.fromResolved(
            listOf(TocNode("Title", "a.xhtml", 0, null, emptyList())),
        )
        assertEquals(0, model.lookupByTitle("").size)
    }

    @Test
    fun `lookupByTitle miss returns empty list`() {
        val model = TocModel.fromResolved(
            listOf(TocNode("Alpha", "a.xhtml", 0, null, emptyList())),
        )
        assertEquals(0, model.lookupByTitle("beta").size)
    }
}
