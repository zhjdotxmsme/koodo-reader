// engine.mjs — pdf.js engine core for the native PDF reader (P3).
//
// Why this file exists separately from index.html: the engine is the part of
// the P3 bridge that has *logic* (URL plumbing, text search, outline
// flattening, payload encoding) and therefore the part that can be wrong in
// ways a compiler cannot see. Keeping it as a dependency-free ES module lets
// `scripts/test-pdfengine.js` import it in Node with a stubbed pdfjs document
// and pin that logic without an emulator.
//
// Contract (mirrored in android/engine/pdf/.../PdfHostBridge.kt):
//   open(url, password)      -> { fingerprint, pageCount, pdfVersion,
//                                 pageWidthPt, pageHeightPt }
//   renderPage(n, widthPx)   -> base64 PNG (no data-URL prefix)
//   search(query)            -> JSON string: [{ pageNumber, hits: [{ rects,
//                                 text }] }]  (shape consumed by
//                                 engine:pdf PdfSearchEngine.parseHits)
//   outline()                -> JSON string: [{ title, pageNumber, children }]
//                                 (shape consumed by OutlineResolver.resolve)
//   selectedRect()           -> null (see "Not supported" below)
//   paintHighlights(items)   -> false (see "Not supported" below)
//   close()                  -> void
//
// Not supported in the headless (raster) design — these are honest boundaries,
// not missing wiring:
//   * `selectedRect()` reads `window.getSelection()` from a text layer. This
//     engine never builds one: pages are rasterised to a detached canvas and
//     drawn by Compose, so there is no selectable DOM. It returns null and
//     logs. PDF annotation capture needs a different host design (P6).
//   * `paintHighlights()` paints divs into `.page-text-layer` elements, which
//     the same design never creates. It returns false and logs.

/** Payload encoding for the Kotlin bridge (see `PdfJsHostBridge.runJs`).
 *
 * The Kotlin side consumes the raw string for string results (base64 PNG,
 * outline/search JSON) and parses an object for `open`. `JSON.stringify` on a
 * string would wrap it in quotes and break both decode paths, so strings are
 * passed through verbatim. This is pinned by scripts/test-pdfengine.js.
 */
export function encodePayload(result) {
    if (typeof result === 'string') return result;
    if (result === undefined) return 'null';
    return JSON.stringify(result);
}

/** Default canvas factory: a detached DOM canvas (browser/WebView only). */
function domCanvas(width, height) {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    return canvas;
}

/**
 * Build the engine.
 *
 * @param {object} options
 * @param {object} options.pdfjsLib  the imported pdf.mjs namespace
 * @param {function} [options.log]   (level, message) sink; defaults to no-op
 * @param {function} [options.createCanvas] (width, height) -> canvas-like,
 *   default `document.createElement('canvas')`; injectable for Node tests
 */
export function createPdfEngine(options) {
    const pdfjsLib = options.pdfjsLib;
    const log = options.log || function () {};
    const createCanvas = options.createCanvas || domCanvas;

    if (!pdfjsLib || typeof pdfjsLib.getDocument !== 'function') {
        throw new Error('pdfjsLib.getDocument is required');
    }

    let doc = null;
    let docUrl = '';
    let fingerprint = '';

    function requireDoc() {
        if (!doc) throw new Error('document not open');
        return doc;
    }

    /**
     * Open `url` — the **full** loopback URL the host exposed the file at
     * (e.g. `http://127.0.0.1:41234/__books__/x.pdf`).
     *
     * The previous implementation rebuilt the URL from the file name against a
     * hardcoded `http://127.0.0.1/` (port 80) and ignored the ephemeral port
     * the host had actually bound, so every open fetched a dead origin. The
     * host owns the URL; this function uses it verbatim.
     */
    async function open(url, password) {
        if (!url) throw new Error('open() requires the book URL');
        if (doc) close();
        docUrl = url;
        const task = pdfjsLib.getDocument({
            url,
            password: password || '',
            // Cmaps ship next to the worker; the worker URL is set by the
            // bootstrap (index.html) because it depends on the import base.
            cMapUrl: options.cMapUrl,
            cMapPacked: true,
            // Never auto-fetch pdfjs.history.json: it does not exist in an APK.
            disableAutoFetch: true,
        });
        doc = await task.promise;
        fingerprint = (doc.fingerprints && doc.fingerprints[0]) || '';
        // pdf.js does not expose page-0 metrics cheaply; the first page is the
        // best available answer and the Compose side re-reads per page.
        let widthPt = 612, heightPt = 792;
        try {
            const first = await doc.getPage(1);
            const vp = first.getViewport({ scale: 1 });
            widthPt = Math.round(vp.width);
            heightPt = Math.round(vp.height);
        } catch (e) {
            log('warn', 'page-1 viewport unavailable, using Letter: ' + e);
        }
        return {
            fingerprint,
            pageCount: doc.numPages,
            pdfVersion: '1.7',
            pageWidthPt: widthPt,
            pageHeightPt: heightPt,
        };
    }

    /** Rasterise page `pageNumber` (1-based) at `targetWidthPx` → base64 PNG. */
    async function renderPage(pageNumber, targetWidthPx) {
        const d = requireDoc();
        const n = Number(pageNumber);
        if (!(n >= 1 && n <= d.numPages)) {
            throw new Error('page out of range: ' + pageNumber);
        }
        const wanted = Math.max(1, Number(targetWidthPx) || 1024);
        const page = await d.getPage(n);
        const base = page.getViewport({ scale: 1 });
        const scale = Math.max(0.01, wanted / Math.max(base.width, 1));
        const viewport = page.getViewport({ scale });
        const canvas = createCanvas(Math.floor(viewport.width), Math.floor(viewport.height));
        const ctx = canvas.getContext('2d');
        await page.render({ canvasContext: ctx, viewport }).promise;
        const dataUrl = canvas.toDataURL('image/png');
        // Kotlin decodes base64 directly, so strip the data-URL prefix.
        return dataUrl.slice(dataUrl.indexOf(',') + 1);
    }

    /**
     * Full-document text search.
     *
     * Walks `getTextContent()` per page — **not** a DOM text layer, which this
     * design never builds (the previous implementation scanned
     * `.page-text-layer` elements that did not exist, so it could only ever
     * return zero hits).
     *
     * Rectangles come from each matching item's text matrix (`transform`), so
     * they are in PDF user space (points, origin bottom-left) as
     * `PdfSearchEngine` documents. Matches split across two text items are not
     * joined (documented limitation).
     */
    async function search(query) {
        const d = requireDoc();
        const needle = String(query == null ? '' : query).trim().toLowerCase();
        if (!needle) return '[]';
        const pages = [];
        for (let p = 1; p <= d.numPages; p++) {
            const page = await d.getPage(p);
            const content = await page.getTextContent();
            const items = (content && content.items) || [];
            const pageText = items.map((i) => i.str || '').join(' ').trim();
            if (!pageText) continue;
            const hits = [];
            for (const item of items) {
                const text = item.str || '';
                if (!text) continue;
                if (text.toLowerCase().indexOf(needle) < 0) continue;
                const t = item.transform || [1, 0, 0, 1, 0, 0];
                const width = Math.abs((item.width || 0) * (t[0] || 1)) || 1;
                const height = Math.abs((item.height || 0) * (t[3] || 1)) || 1;
                hits.push({
                    rects: [{ x: t[4] || 0, y: t[5] || 0, width, height }],
                    text: pageText,
                });
            }
            if (hits.length > 0) pages.push({ pageNumber: p, hits });
        }
        return JSON.stringify(pages);
    }

    /**
     * Resolve the outline tree to `{title, pageNumber, children}` with
     * **1-based** page numbers, resolving pdf.js's `dest` (a name or a raw
     * `[pageRef, ...]` array) through the document proxy — the Kotlin
     * `OutlineResolver` deliberately does not do this and expects it done here.
     * Entries whose destination cannot be resolved keep `pageNumber: 0`, which
     * the Kotlin side renders as "unresolved" rather than dropping.
     */
    async function outline() {
        const d = requireDoc();
        let raw = null;
        try {
            raw = await d.getOutline();
        } catch (e) {
            log('warn', 'getOutline failed: ' + e);
            return '[]';
        }
        return JSON.stringify(await flatten(raw, d));
    }

    async function flatten(items, d) {
        if (!items || !items.length) return [];
        const out = [];
        for (const item of items) {
            let pageNumber = 0;
            try {
                if (item.dest) {
                    const dest = typeof item.dest === 'string'
                        ? await d.getDestination(item.dest)
                        : item.dest;
                    if (dest && dest[0]) pageNumber = (await d.getPageIndex(dest[0])) + 1;
                }
            } catch (e) {
                pageNumber = 0; // unresolved: kept, rendered disabled by the host
            }
            out.push({
                title: item.title || '(untitled)',
                pageNumber,
                children: await flatten(item.items, d),
            });
        }
        return out;
    }

    /** Not supported: the host rasterises pages, so there is no DOM selection. */
    function selectedRect() {
        log('warn', 'selectedRect: unsupported in the raster host (no text layer)');
        return null;
    }

    /** Not supported: there is no `.page-text-layer` to paint into. */
    function paintHighlights() {
        log('warn', 'paintHighlights: unsupported in the raster host (no text layer)');
        return false;
    }

    function close() {
        if (doc) {
            try {
                doc.destroy();
            } catch (e) {
                log('warn', 'destroy failed: ' + e);
            }
            doc = null;
        }
        docUrl = '';
    }

    return {
        open,
        renderPage,
        search,
        outline,
        selectedRect,
        paintHighlights,
        close,
        get isOpen() { return doc !== null; },
        get url() { return docUrl; },
        get pageCount() { return doc ? doc.numPages : 0; },
    };
}

/** Method table used by the `window.__koodoPdf.call` shim in index.html. */
export const METHODS = [
    'open',
    'renderPage',
    'search',
    'outline',
    'selectedRect',
    'paintHighlights',
    'close',
];

/**
 * Dispatch one bridge call and report the result through `host`.
 *
 * The call is answered exactly once: a success payload, or `(payload='')` plus
 * the error message, which `PdfJsHostBridge.runJs` turns into a thrown
 * `RuntimeException("PDF engine: …")`.
 */
export async function dispatch(engine, callId, method, args, host) {
    try {
        if (METHODS.indexOf(method) < 0) throw new Error('unknown method: ' + method);
        const result = await engine[method].apply(engine, args || []);
        host.onResult(callId, encodePayload(result), '');
    } catch (err) {
        host.onResult(callId, '', String((err && err.message) || err));
    }
}
