// engine.mjs — foliate-js host core for reflowable books.
//
// Kept as a dependency-free ES module so `scripts/test-foliate-host.js` can
// import it in Node with injected fetch/openBook stubs. The WebView bootstrap
// (`index.html`) supplies a real `foliate-view` and a fetch that hits the
// loopback LocalAssetServer.
//
// Contract (mirrored in FoliateHostBridge.kt):
//   open(url, format)     -> { title, sectionCount }
//   toc()                 -> JSON string: [{ label, href, cfi, depth }]
//   goTo(target)          -> { ok: true }
//   next()                -> { ok: true }
//   prev()                -> { ok: true }
//   search(query)         -> JSON string: [{ excerpt, href, cfi }]
//   setAppearance(opts)   -> { ok: true }
//   setViewSettings(opts) -> { ok: true }
//   selectedRange()       -> { cfi, excerpt } | null
//   close()               -> void

export function selectionPayload(sel, cfi) {
    if (!sel || sel.isCollapsed) return { excerpt: '', cfi: '' };
    const excerpt = String(sel).trim();
    if (!excerpt) return { excerpt: '', cfi: '' };
    return { excerpt, cfi: String(cfi || '') };
}

/** Same 1/3 split as ReaderPageGestures.onTap: edges turn pages, center toggles chrome. */
export function chromeTapAction(x, width) {
    if (!(width > 0)) return 'toggle';
    const edge = width / 3;
    if (x < edge) return 'prev';
    if (x > width - edge) return 'next';
    return 'toggle';
}

function rangeAtPoint(doc, x, y) {
    const caret = doc.caretRangeFromPoint
        ? doc.caretRangeFromPoint(x, y)
        : null;
    if (!caret) return null;
    const node = caret.startContainer;
    const el = node && node.nodeType === 1 ? node : node && node.parentElement;
    const block = el && el.closest
        ? el.closest('p, li, h1, h2, h3, h4, h5, h6, div, span, td, blockquote')
        : null;
    if (block) {
        const r = doc.createRange();
        r.selectNodeContents(block);
        const text = String(r).trim();
        if (text && text.length <= 400) return r;
    }
    return caret;
}

function cfiForRange(view, index, range) {
    if (!view || !range) return '';
    try {
        if (typeof view.getCFI === 'function') return String(view.getCFI(index, range) || '');
    } catch (e) { /* ignore */ }
    return (view.lastLocation && view.lastLocation.cfi) || '';
}

function emitSelection(host, payload) {
    if (!host || typeof host.onEvent !== 'function') return;
    try { host.onEvent('selection', JSON.stringify(payload)); } catch (e) { /* ignore */ }
}

function attachSelectionReporter(view, host) {
    if (!view || typeof view.addEventListener !== 'function') return;
    const LONG_MS = 480;
    const MOVE_PX = 14;
    view.addEventListener('load', (ev) => {
        const detail = (ev && ev.detail) || {};
        const doc = detail.doc;
        if (!doc || !doc.addEventListener) return;
        const index = detail.index;
        let timer = 0;
        let sx = 0;
        let sy = 0;
        const clear = () => { if (timer) { clearTimeout(timer); timer = 0; } };
        const reportDoc = () => {
            const sel = doc.getSelection && doc.getSelection();
            const range = sel && sel.rangeCount ? sel.getRangeAt(0) : null;
            emitSelection(host, selectionPayload(sel, cfiForRange(view, index, range)));
        };
        doc.addEventListener('touchstart', (e) => {
            const t = e.changedTouches && e.changedTouches[0];
            if (!t) return;
            sx = t.clientX;
            sy = t.clientY;
            clear();
            timer = setTimeout(() => {
                timer = 0;
                const range = rangeAtPoint(doc, sx, sy);
                if (!range) return;
                const sel = doc.getSelection();
                if (!sel) return;
                sel.removeAllRanges();
                sel.addRange(range);
                reportDoc();
            }, LONG_MS);
        }, { passive: true });
        doc.addEventListener('touchmove', (e) => {
            const t = e.changedTouches && e.changedTouches[0];
            if (!t) return;
            const dx = t.clientX - sx;
            const dy = t.clientY - sy;
            if ((dx * dx + dy * dy) > (MOVE_PX * MOVE_PX)) clear();
        }, { passive: true });
        doc.addEventListener('touchend', clear, { passive: true });
        doc.addEventListener('touchcancel', clear, { passive: true });
        doc.addEventListener('selectionchange', reportDoc);
        doc.addEventListener('contextmenu', (e) => {
            try { e.preventDefault(); } catch (err) { /* ignore */ }
            reportDoc();
        });
    });
}

export function encodePayload(result) {
    if (typeof result === 'string') return result;
    if (result === undefined) return 'null';
    return JSON.stringify(result);
}

function cssColor(value, fallback) {
    const s = String(value || '').trim();
    if (/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(s)) return s;
    if (/^rgba?\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}(?:\s*,\s*[0-9.]+)?\s*\)$/i.test(s)) return s;
    return fallback;
}

/**
 * Theme CSS injected into each chapter iframe via renderer.setStyles.
 * Book-authored colours on p/div/span otherwise beat html/body rules.
 */
export function appearanceCss(opts) {
    const o = opts || {};
    const bg = cssColor(o.bg, '#ffffff');
    const fg = cssColor(o.fg, '#111111');
    const size = Number(o.fontSize || 18);
    const family = String(o.fontFamily || '').replace(/[;"{}\\]/g, '');
    const lineHeight = Number(o.lineHeight || 1.5);
    const margin = Math.max(0, Number(o.marginPx || 0));
    const scheme = o.colorScheme === 'dark' ? 'dark' : 'light';
    let css = 'html { color-scheme: ' + scheme + ' !important; background: ' + bg
        + ' !important; background-color: ' + bg + ' !important; }';
    css += 'html, body { background: ' + bg + ' !important; background-color: ' + bg
        + ' !important; background-image: none !important; color: ' + fg
        + ' !important; -webkit-text-fill-color: ' + fg + ' !important; font-size: '
        + size + 'px !important; line-height: ' + lineHeight + ' !important; padding: '
        + margin + 'px !important; -webkit-user-select: text; user-select: text; }';
    if (family) css += ' body { font-family: ' + family + ', serif !important; }';
    css += 'body, body *:not(img):not(image):not(svg):not(video):not(canvas):not(source) {'
        + ' color: ' + fg + ' !important; -webkit-text-fill-color: ' + fg
        + ' !important; background-color: transparent !important; }';
    css += 'a, a:link, a:visited { color: ' + fg + ' !important; }';
    if (o.invertImages) {
        css += 'img, image, video, canvas, svg { filter: invert(1) hue-rotate(180deg) !important; }';
    }
    return css;
}

export function flattenToc(items, depth) {
    const out = [];
    const level = depth || 0;
    for (const node of items || []) {
        if (!node) continue;
        const label = String(node.label || node.title || '').trim();
        const href = String(node.href || '');
        const cfi = String(node.cfi || '');
        if (label || href) {
            out.push({ label: label || href, href, cfi, depth: level });
        }
        const kids = node.subitems || node.children || node.items;
        if (kids && kids.length) out.push.apply(out, flattenToc(kids, level + 1));
    }
    return out;
}

const HEADING = /(?:^|\n)([ \t]*(?:第[0-9零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+[章节回卷部篇]|Chapter\s+\d+)[^\n]*)/gi;

export function splitTxtChapters(text) {
    const src = String(text || '').replace(/\r\n/g, '\n');
    const matches = [];
    HEADING.lastIndex = 0;
    let m;
    while ((m = HEADING.exec(src))) {
        matches.push({ index: m.index + (m[0].startsWith('\n') ? 1 : 0), title: m[1].trim() });
    }
    if (matches.length === 0) {
        const trimmed = src.trim();
        return trimmed ? [{ title: 'Text', body: trimmed }] : [];
    }
    const chapters = [];
    for (let i = 0; i < matches.length; i++) {
        const start = matches[i].index;
        const titleEnd = src.indexOf('\n', start);
        const bodyStart = titleEnd < 0 ? src.length : titleEnd + 1;
        const bodyEnd = i + 1 < matches.length ? matches[i + 1].index : src.length;
        chapters.push({
            title: matches[i].title,
            body: src.slice(bodyStart, bodyEnd).trim(),
        });
    }
    return chapters;
}

function escapeHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

export function mdToHtml(md) {
    const lines = String(md || '').replace(/\r\n/g, '\n').split('\n');
    const out = [];
    let para = [];
    function flush() {
        if (!para.length) return;
        const raw = para.join(' ').trim();
        para = [];
        if (!raw) return;
        const withInline = raw
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/\*([^*]+)\*/g, '<em>$1</em>');
        out.push('<p>' + withInline + '</p>');
    }
    for (const line of lines) {
        const heading = /^(#{1,6})\s+(.*)$/.exec(line);
        if (heading) {
            flush();
            const level = heading[1].length;
            out.push('<h' + level + '>' + escapeHtml(heading[2].trim()) + '</h' + level + '>');
            continue;
        }
        if (!line.trim()) {
            flush();
            continue;
        }
        para.push(escapeHtml(line.trim()));
    }
    flush();
    return out.join('\n') || '<p></p>';
}

function makeHtmlBook(html, title) {
    const doc = String(html || '<p></p>');
    return {
        metadata: { title: title || '' },
        rendition: {},
        toc: [{ label: title || 'Text', href: '0' }],
        sections: [{
            id: '0',
            href: '0',
            linear: 'yes',
            size: doc.length,
            createDocument: undefined,
            load: async () => doc,
        }],
        splitTOCHref: (href) => [href, null],
        getTOCFragment: (d) => d && d.documentElement,
        getCover: async () => null,
    };
}

function txtToHtmlBook(text, title) {
    const chapters = splitTxtChapters(text);
    if (!chapters.length) {
        return makeHtmlBook('<pre>' + escapeHtml(text) + '</pre>', title);
    }
    return {
        metadata: { title: title || chapters[0].title },
        rendition: {},
        toc: chapters.map((c, i) => ({ label: c.title, href: String(i) })),
        sections: chapters.map((c, i) => {
            const html = '<h1>' + escapeHtml(c.title) + '</h1><p>'
                + escapeHtml(c.body).replace(/\n+/g, '</p><p>') + '</p>';
            return {
                id: String(i),
                href: String(i),
                linear: 'yes',
                size: html.length,
                load: async () => html,
            };
        }),
        splitTOCHref: (href) => [href, null],
        getTOCFragment: (d) => d && d.documentElement,
        getCover: async () => null,
    };
}

function attachCreateDocument(book) {
    if (typeof DOMParser === 'undefined') return book;
    for (const section of book.sections || []) {
        if (typeof section.createDocument === 'function') continue;
        const load = section.load;
        if (typeof load !== 'function') continue;
        section.createDocument = async () => {
            const html = await load();
            return new DOMParser().parseFromString(
                '<!doctype html><html><head></head><body>' + html + '</body></html>',
                'text/html',
            );
        };
    }
    return book;
}

async function defaultFetchFile(url) {
    const res = await fetch(url);
    if (!res.ok) throw new Error('fetch failed: ' + res.status);
    const blob = await res.blob();
    const name = String(url).split('?')[0].split('/').pop() || 'book';
    if (typeof File === 'function') return new File([blob], name);
    blob.name = name;
    blob.text = blob.text || (async () => new TextDecoder().decode(await blob.arrayBuffer()));
    return blob;
}

/**
 * @param {object} options
 * @param {function} [options.log]
 * @param {function} [options.fetchFile]  (url, format) -> File-like
 * @param {function} [options.openBook]   (file, format) -> foliate book (tests)
 * @param {object}   [options.view]       foliate-view element
 * @param {object}   [options.host]       optional event sink for relocate
 */
export function createFoliateEngine(options) {
    const log = (options && options.log) || function () {};
    const fetchFile = (options && options.fetchFile) || defaultFetchFile;
    const openBook = options && options.openBook;
    const view = options && options.view;
    const host = options && options.host;

    let book = null;
    let lastTarget = null;
    let appearance = {
        bg: '#ffffff',
        fg: '#111111',
        fontSize: 18,
        fontFamily: '',
        lineHeight: 1.5,
        marginPx: 0,
        invertImages: false,
        colorScheme: 'light',
    };
    let flow = 'scrolled';
    let maxColumnCount = 1;
    attachSelectionReporter(view, host);

    function requireBook() {
        if (!book) throw new Error('document not open');
        return book;
    }

    async function open(url, format) {
        if (!url) throw new Error('open() requires the book URL');
        const fmt = String(format || '').toLowerCase();
        if (book) close();
        const file = await fetchFile(url, fmt);
        if (fmt === 'txt') {
            const text = await file.text();
            try {
                book = txtToHtmlBook(text, file.name || 'Text');
            } catch (e) {
                log('warn', 'txt convert failed, wrapping as pre: ' + e);
                book = makeHtmlBook('<pre>' + escapeHtml(text) + '</pre>', file.name || 'Text');
            }
            book = attachCreateDocument(book);
            if (view && typeof view.open === 'function') await view.open(book);
        } else if (fmt === 'md') {
            const html = mdToHtml(await file.text());
            book = attachCreateDocument(makeHtmlBook(html, file.name || 'Markdown'));
            if (view && typeof view.open === 'function') await view.open(book);
        } else if (fmt === 'html' || fmt === 'htm' || fmt === 'xhtml' || fmt === 'xml'
            || fmt === 'mhtml' || fmt === 'mht' || fmt === 'docx') {
            // DOCX is converted to HTML by Kotlin before publish.
            book = attachCreateDocument(makeHtmlBook(await file.text(), file.name || 'HTML'));
            if (view && typeof view.open === 'function') await view.open(book);
        } else if (openBook) {
            book = await openBook(file, fmt);
        } else if (view && typeof view.open === 'function') {
            await view.open(file);
            book = view.book;
        } else {
            throw new Error('no opener for format ' + fmt);
        }
        if (!book) throw new Error('open() produced no book');
        if (view && view.renderer && typeof view.renderer.setStyles === 'function') {
            view.renderer.setStyles(appearanceCss(appearance));
        }
        if (view && typeof view.addEventListener === 'function') {
            view.addEventListener('relocate', (ev) => {
                const detail = (ev && ev.detail) || {};
                const payload = {
                    cfi: detail.cfi || '',
                    fraction: typeof detail.fraction === 'number' ? detail.fraction : 0,
                    label: (detail.tocItem && detail.tocItem.label) || '',
                };
                try { host && host.onEvent('relocate', JSON.stringify(payload)); } catch (e) {}
            });
        }
        return {
            title: (book.metadata && book.metadata.title) || '',
            sectionCount: (book.sections && book.sections.length) || 0,
        };
    }

    function toc() {
        const b = requireBook();
        return JSON.stringify(flattenToc(b.toc || []));
    }

    async function goTo(target) {
        requireBook();
        lastTarget = target;
        if (view && typeof view.goTo === 'function') {
            await view.goTo(target);
        }
        return { ok: true };
    }

    async function next() {
        if (view && typeof view.next === 'function') await view.next();
        return { ok: true };
    }

    async function prev() {
        if (view && typeof view.prev === 'function') await view.prev();
        return { ok: true };
    }

    async function search(query) {
        const b = requireBook();
        const needle = String(query == null ? '' : query).trim().toLowerCase();
        if (!needle) return '[]';
        if (view && typeof view.search === 'function') {
            const hits = [];
            try {
                for await (const item of view.search({ query: needle })) {
                    if (!item || item.progress) continue;
                    hits.push({
                        excerpt: String(item.excerpt || item.text || ''),
                        href: String(item.href || ''),
                        cfi: String(item.cfi || ''),
                    });
                    if (hits.length >= 40) break;
                }
            } catch (e) {
                log('warn', 'view.search failed: ' + e);
            }
            if (hits.length) return JSON.stringify(hits);
        }
        const hits = [];
        const items = flattenToc(b.toc || []);
        for (const item of items) {
            if (item.label.toLowerCase().indexOf(needle) >= 0) {
                hits.push({ excerpt: item.label, href: item.href, cfi: item.cfi });
            }
        }
        return JSON.stringify(hits);
    }

    async function setViewSettings(opts) {
        appearance = Object.assign({}, appearance, opts || {});
        flow = opts && opts.scrolled === false ? 'paginated' : 'scrolled';
        maxColumnCount = Math.max(1, Number((opts && opts.maxColumnCount) || 1));
        const themeCode = {
            bg: appearance.bg,
            fg: appearance.fg,
            isDarkMode: !!appearance.isDarkMode,
            primary: appearance.fg,
            base100: appearance.bg,
        };
        const viewSettings = {
            marginTopPx: appearance.marginPx || 0,
            marginRightPx: appearance.marginPx || 0,
            marginBottomPx: appearance.marginPx || 0,
            marginLeftPx: appearance.marginPx || 0,
            writingMode: 'auto',
            vertical: false,
            useBookLayout: false,
            overrideLayout: true,
            paragraphMargin: 1,
            lineHeight: Number(appearance.lineHeight || 1.5),
            wordSpacing: 0,
            letterSpacing: 0,
            textIndent: 0,
            fullJustification: false,
            hyphenation: false,
            serifFont: 'Georgia',
            sansSerifFont: 'Arial',
            monospaceFont: 'Consolas',
            defaultFont: appearance.defaultFont || 'serif',
            defaultCJKFont: 'Noto Serif CJK SC',
            defaultFontSize: Number(appearance.defaultFontSize || 13.6),
            minimumFontSize: 12,
            fontWeight: 400,
            overrideFont: false,
            overrideColor: appearance.overrideColor !== false,
            invertImgColorInDark: false,
            backgroundTextureId: '',
            isEink: false,
            userStylesheet: '',
            zoomLevel: 100,
            scrolled: flow === 'scrolled',
            maxColumnCount,
            gapPercent: 5,
            showHeader: false,
            showFooter: false,
        };
        let css = '';
        try {
            const mod = await import('./readest-style.mjs');
            css = mod.getStyles(viewSettings, themeCode);
        } catch (e) {
            css = appearanceCss(appearance);
        }
        if (view && view.renderer) {
            if (typeof view.renderer.setStyles === 'function') view.renderer.setStyles(css);
            if (typeof view.renderer.setAttribute === 'function') {
                view.renderer.setAttribute('flow', flow);
                // Host owns swipe / volume-key turns so foliate does not double-flip.
                view.renderer.setAttribute('no-swipe', '');
                view.renderer.setAttribute('max-column-count', String(maxColumnCount));
                view.renderer.setAttribute('max-inline-size', '720px');
                view.renderer.setAttribute('gap', '5%');
                const margin = Math.max(0, Number(appearance.marginPx || 0)) + 'px';
                view.renderer.setAttribute('margin-top', margin);
                view.renderer.setAttribute('margin-right', margin);
                view.renderer.setAttribute('margin-bottom', margin);
                view.renderer.setAttribute('margin-left', margin);
            }
        }
        return { ok: true };
    }

    async function setAppearance(opts) {
        return setViewSettings(Object.assign({}, opts, {
            defaultFontSize: (opts && opts.fontSize ? opts.fontSize : 17) / 1.25,
            scrolled: flow !== 'paginated',
            maxColumnCount,
            overrideColor: true,
            isDarkMode: (opts && opts.colorScheme) === 'dark',
        }));
    }

    function selectedRange() {
        const contents = view && view.renderer && typeof view.renderer.getContents === 'function'
            ? view.renderer.getContents()
            : [];
        for (let i = 0; i < contents.length; i++) {
            const item = contents[i] || {};
            const doc = item.doc;
            const sel = doc && doc.getSelection && doc.getSelection();
            const range = sel && sel.rangeCount ? sel.getRangeAt(0) : null;
            const payload = selectionPayload(sel, cfiForRange(view, item.index, range));
            if (payload.excerpt) return payload;
        }
        return { excerpt: '', cfi: '' };
    }

    async function addHighlight(cfi, color) {
        if (!cfi || !view || typeof view.addAnnotation !== 'function') return { ok: false };
        await view.addAnnotation({ value: String(cfi), color: color || '#ffe066' });
        return { ok: true };
    }

    async function removeHighlight(cfi) {
        if (!cfi || !view || typeof view.addAnnotation !== 'function') return { ok: false };
        await view.addAnnotation({ value: String(cfi) }, true);
        return { ok: true };
    }

    function close() {
        if (view && typeof view.close === 'function') {
            try { view.close(); } catch (e) { log('warn', 'view.close: ' + e); }
        }
        book = null;
        lastTarget = null;
    }

    return {
        open,
        toc,
        goTo,
        next,
        prev,
        search,
        setAppearance,
        setViewSettings,
        selectedRange,
        addHighlight,
        removeHighlight,
        close,
        get isOpen() { return book !== null; },
        get lastTarget() { return lastTarget; },
        get appearance() { return appearance; },
        get flow() { return flow; },
        get maxColumnCount() { return maxColumnCount; },
    };
}

export const METHODS = [
    'open',
    'toc',
    'goTo',
    'next',
    'prev',
    'search',
    'setAppearance',
    'setViewSettings',
    'selectedRange',
    'addHighlight',
    'removeHighlight',
    'close',
];

export async function dispatch(engine, callId, method, args, host) {
    try {
        if (METHODS.indexOf(method) < 0) throw new Error('unknown method: ' + method);
        const result = await engine[method].apply(engine, args || []);
        host.onResult(callId, encodePayload(result), null);
    } catch (err) {
        host.onResult(callId, '', String((err && err.message) || err));
    }
}
