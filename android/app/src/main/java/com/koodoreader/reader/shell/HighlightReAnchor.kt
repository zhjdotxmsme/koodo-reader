package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.NoteEntity
import org.json.JSONObject

/**
 * One highlight to anchor in the foliate engine.
 *
 * @param cfi the stored EPUB CFI — the primary key (ADR-002)
 * @param text the saved excerpt, the fallback key when the CFI is dead
 * @param colorCss the overlay colour, forwarded verbatim to `addAnnotation`
 */
data class HighlightAnchorRequest(
    val cfi: String,
    val text: String,
    val colorCss: String,
)

/** A CFI the engine could not resolve, repaired to a fresh one in the same section. */
data class HighlightRemap(val oldCfi: String, val newCfi: String)

/**
 * Result of one [batch](FoliateHostBridge.reAnchorHighlights) — how many
 * highlights resolved directly, which were repaired (and into what), and which
 * are beyond repair. Repair never deletes: `failed` entries stay in the notes
 * list exactly as they were.
 */
data class ReAnchorReport(
    val anchored: Int = 0,
    val remaps: List<HighlightRemap> = emptyList(),
    val failed: List<String> = emptyList(),
)

/**
 * Building re-anchor requests from notes and applying the engine's remaps are
 * pure list transforms, so they live here instead of the bridge — this file has
 * no Android dependencies and is unit-tested on the JVM.
 */
object HighlightReAnchor {

    /**
     * Notes that carry a CFI become requests; notes without one are skipped
     * (they were never anchored and have nothing to repair).
     */
    fun requests(
        notes: List<NoteEntity>,
        colorCss: (NoteEntity) -> String,
    ): List<HighlightAnchorRequest> = notes.mapNotNull { note ->
        val cfi = note.cfi?.trim().orEmpty()
        if (cfi.isEmpty()) return@mapNotNull null
        HighlightAnchorRequest(
            cfi = cfi,
            text = note.text.orEmpty(),
            colorCss = colorCss(note),
        )
    }

    /**
     * Notes whose stored CFI was remapped, ready to be persisted with the new
     * CFI. The stale DOM `range` is cleared: it described a node that no longer
     * exists, and keeping it would make the next desktop sync resurrect a
     * dead anchor next to the live one.
     *
     * Idempotent: a note already written back carries the new CFI, so a second
     * run with the same report matches nothing.
     */
    fun applyRemaps(
        notes: List<NoteEntity>,
        remaps: List<HighlightRemap>,
    ): List<NoteEntity> {
        if (remaps.isEmpty()) return emptyList()
        val byOld = LinkedHashMap<String, String>()
        for (remap in remaps) {
            if (remap.oldCfi.isBlank() || remap.newCfi.isBlank()) continue
            if (remap.oldCfi == remap.newCfi) continue
            byOld.putIfAbsent(remap.oldCfi, remap.newCfi)
        }
        if (byOld.isEmpty()) return emptyList()
        val changed = mutableListOf<NoteEntity>()
        for (note in notes) {
            val cfi = note.cfi ?: continue
            val newCfi = byOld[cfi] ?: continue
            changed.add(note.copy(cfi = newCfi, range = null))
        }
        return changed
    }

    /**
     * Parse the engine's `reAnchorHighlights` report into a [ReAnchorReport].
     * Pure so tests can pin the wire format without a WebView; lives here (not
     * in the bridge companion) so touching it never triggers Android class init.
     * Blank or malformed payloads degrade to an empty report.
     */
    fun parseReport(raw: String): ReAnchorReport {
        if (raw.isBlank()) return ReAnchorReport()
        return runCatching {
            val o = JSONObject(raw)
            val remaps = mutableListOf<HighlightRemap>()
            val remapArr = o.optJSONArray("reanchored")
            if (remapArr != null) {
                for (i in 0 until remapArr.length()) {
                    val r = remapArr.optJSONObject(i) ?: continue
                    val old = r.optString("old", "")
                    val new = r.optString("cfi", "")
                    if (old.isNotBlank() && new.isNotBlank() && old != new) {
                        remaps.add(HighlightRemap(old, new))
                    }
                }
            }
            val failed = mutableListOf<String>()
            val failedArr = o.optJSONArray("failed")
            if (failedArr != null) {
                for (i in 0 until failedArr.length()) {
                    val f = failedArr.optString(i, "")
                    if (f.isNotBlank()) failed.add(f)
                }
            }
            ReAnchorReport(
                anchored = o.optInt("anchored", 0),
                remaps = remaps,
                failed = failed,
            )
        }.getOrDefault(ReAnchorReport())
    }
}
