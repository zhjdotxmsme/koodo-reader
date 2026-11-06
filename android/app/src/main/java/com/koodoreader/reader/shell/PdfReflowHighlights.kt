package com.koodoreader.reader.shell

import com.koodoreader.core.data.entity.NoteEntity
import com.koodoreader.engine.annotate.HighlightPalette
import com.koodoreader.engine.pdf.CfiPdfMapper
import org.json.JSONArray
import org.json.JSONObject

/** Persistent highlight overlays inside the PDF reflow WebView (document pixel space). */
object PdfReflowHighlights {

    fun jsonForPage(notes: List<NoteEntity>, page: Int): String {
        val arr = JSONArray()
        notes.forEach { note ->
            val range = note.cfi?.let(CfiPdfMapper::parse)
                ?.takeIf { it.page == page && it.reflowDocument && !it.isPageLevel }
                ?: return@forEach
            if (range.width <= 0f || range.height <= 0f) return@forEach
            arr.put(
                JSONObject()
                    .put("l", range.x.toDouble())
                    .put("t", range.y.toDouble())
                    .put("w", range.width.toDouble())
                    .put("h", range.height.toDouble())
                    .put("c", HighlightPalette.css(note.color)),
            )
        }
        return arr.toString()
    }

    /** Injected after each reflow page load and whenever notes change. */
    fun paintJavascript(highlightsJson: String): String {
        val escaped = highlightsJson
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        return """
(function(){
  document.body.style.position='relative';
  document.querySelectorAll('[data-koodo-reflow-hl]').forEach(function(n){ n.remove(); });
  var items;
  try { items = JSON.parse('$escaped'); } catch (e) { return; }
  if (!items || !items.length) return;
  items.forEach(function(h){
    var el = document.createElement('div');
    el.setAttribute('data-koodo-reflow-hl','1');
    el.style.position='absolute';
    el.style.left=(h.l||0)+'px';
    el.style.top=(h.t||0)+'px';
    el.style.width=(h.w||0)+'px';
    el.style.height=(h.h||0)+'px';
    el.style.background=(h.c||'#ffe066');
    el.style.opacity='0.45';
    el.style.pointerEvents='none';
    el.style.borderRadius='2px';
    el.style.zIndex='1';
    document.body.appendChild(el);
  });
})();
""".trimIndent()
    }
}
