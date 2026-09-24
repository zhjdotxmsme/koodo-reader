#!/usr/bin/env node
/*
 * test-pdfengine.js — unit tests for the P3 pdf.js engine core
 * (`android/app/src/main/assets/pdfengine/engine.mjs`).
 *
 * Why: the engine bridge is the one part of the native PDF reader that cannot
 * be covered by a JVM test (it is JS) and is never exercised by a device test in
 * CI. The previous revision of `index.html` shipped three defects that only a
 * real run would have caught — a hardcoded `http://127.0.0.1/` origin that
 * ignored the ephemeral port the host bound, a search implementation that
 * scanned DOM text layers the design never builds, and `JSON.stringify` applied
 * to string results (base64 PNG / JSON payloads), which corrupted both decode
 * paths on the Kotlin side.
 *
 * This harness imports the real module in Node with a stubbed pdf.js document,
 * so the logic is pinned without an emulator.
 *
 * Usage:
 *   node scripts/test-pdfengine.js            # run, exit 0/1
 *   node scripts/test-pdfengine.js --quiet    # only the summary line
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const ROOT = path.join(__dirname, '..');
const ENGINE_DIR = path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'pdfengine');
const ENGINE_MJS = path.join(ENGINE_DIR, 'engine.mjs');

const QUIET = process.argv.includes('--quiet');

let passed = 0;
const failures = [];

function check(name, condition, detail) {
    if (condition) {
        passed++;
        if (!QUIET) console.log(`  PASS  ${name}`);
    } else {
        failures.push({ name, detail: detail || '' });
        console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`);
    }
}

function eq(name, actual, expected) {
    check(name, actual === expected, `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

/** Log capture so the boundary warnings can be asserted instead of printed. */
function collector() {
    const lines = [];
    return { lines, log: (level, message) => lines.push(level + ': ' + message) };
}

// ── fake pdf.js ──────────────────────────────────────────────────────────────
// Two pages; page 1 carries "Hello World" twice and "héllo" for the
// case-insensitivity check, page 2 carries "second page".
const PAGE_TEXT = {
    1: [
        { str: 'Hello World', transform: [12, 0, 0, 12, 72, 700], width: 66, height: 12 },
        { str: 'body text', transform: [10, 0, 0, 10, 72, 660], width: 45, height: 10 },
        { str: 'hello again', transform: [10, 0, 0, 10, 72, 640], width: 55, height: 10 },
    ],
    2: [
        { str: 'Second page', transform: [11, 0, 0, 11, 60, 720], width: 70, height: 11 },
    ],
};

function makeDocument() {
    let destroyed = false;
    return {
        numPages: 2,
        fingerprints: ['fp-abc123'],
        get destroyed() { return destroyed; },
        async getPage(n) {
            if (!(n >= 1 && n <= 2)) throw new Error('bad page ' + n);
            return {
                getViewport({ scale }) {
                    return { width: 600 * scale, height: 800 * scale };
                },
                async render() { return { promise: Promise.resolve() }; },
                async getTextContent() { return { items: PAGE_TEXT[n] }; },
            };
        },
        async getOutline() {
            return [
                {
                    title: 'Chapter 1',
                    dest: 'ch1',
                    items: [{ title: 'Section 1.1', dest: [{ num: 7, gen: 0 }, { name: 'XYZ' }], items: [] }],
                },
                { title: 'Broken', dest: 'missing', items: [] },
            ];
        },
        async getDestination(name) {
            if (name === 'ch1') return [{ num: 3, gen: 0 }, { name: 'XYZ' }];
            throw new Error('no such destination: ' + name);
        },
        async getPageIndex() { return 1; }, // 0-based 1 → pageNumber 2
        destroy() { destroyed = true; },
    };
}

/** pdfjsLib stub that records the options it was handed. */
function makePdfjs(doc) {
    const calls = [];
    return {
        calls,
        getDocument(options) {
            calls.push(options);
            return { promise: Promise.resolve(doc) };
        },
        GlobalWorkerOptions: {},
    };
}

/** Canvas stub: records the requested size, returns a fake data URL. */
function makeCanvasFactory() {
    const sizes = [];
    return {
        sizes,
        create(width, height) {
            sizes.push([width, height]);
            return {
                width,
                height,
                getContext() { return { canvas: this }; },
                toDataURL() { return 'data:image/png;base64,QUJD'; }, // "ABC"
            };
        },
    };
}

async function main() {
    if (!fs.existsSync(ENGINE_MJS)) {
        console.error(`[test-pdfengine] missing ${ENGINE_MJS}`);
        process.exit(1);
    }
    const mod = await import(pathToFileURL(ENGINE_MJS).href);
    const { createPdfEngine, dispatch, encodePayload, METHODS } = mod;

    console.log('pdfengine: payload encoding');
    eq('encodePayload passes strings through verbatim', encodePayload('QUJD'), 'QUJD');
    eq('encodePayload stringifies objects', encodePayload({ a: 1 }), '{"a":1}');
    eq('encodePayload never emits undefined', encodePayload(undefined), 'null');
    eq('encodePayload serialises null', encodePayload(null), 'null');

    console.log('pdfengine: open');
    {
        const sink = collector();
        const doc = makeDocument();
        const pdfjs = makePdfjs(doc);
        const engine = createPdfEngine({ pdfjsLib: pdfjs, log: sink.log });
        const url = 'http://127.0.0.1:41234/__books__/x.pdf';
        const result = await engine.open(url, '');
        // The regression this pins: the old code rebuilt the URL against port 80.
        eq('open hands the exact loopback URL to pdf.js', pdfjs.calls[0].url, url);
        eq('open reports the page count', result.pageCount, 2);
        eq('open reports the fingerprint', result.fingerprint, 'fp-abc123');
        eq('open reads page-1 metrics from the viewport', result.pageWidthPt, 600);
        eq('open reports the page-1 height', result.pageHeightPt, 800);
        eq('open disables auto-fetch', pdfjs.calls[0].disableAutoFetch, true);
        eq('open requests packed cmaps', pdfjs.calls[0].cMapPacked, true);
        eq('open marks the engine as open', engine.isOpen, true);

        let threw = null;
        try { await engine.open('', null); } catch (e) { threw = e.message; }
        check('open rejects an empty URL', threw !== null && /requires the book URL/.test(threw), String(threw));
    }

    console.log('pdfengine: renderPage');
    {
        const doc = makeDocument();
        const pdfjs = makePdfjs(doc);
        const canvas = makeCanvasFactory();
        const engine = createPdfEngine({ pdfjsLib: pdfjs, log: collector().log, createCanvas: canvas.create });
        await engine.open('http://127.0.0.1:1/__books__/x.pdf', '');
        const png = await engine.renderPage(1, 300);
        // Kotlin calls Base64.decode on the raw string: a data-URL prefix would
        // decode to garbage. The prefix must be stripped.
        eq('renderPage returns bare base64 (no data-URL prefix)', png, 'QUJD');
        eq('renderPage rasterises at the requested width', canvas.sizes[0][0], 300);
        eq('renderPage keeps the page aspect ratio', canvas.sizes[0][1], 400);
        let threw = null;
        try { await engine.renderPage(9, 300); } catch (e) { threw = e.message; }
        check('renderPage rejects an out-of-range page', threw !== null && /out of range/.test(threw), String(threw));
    }

    console.log('pdfengine: search');
    {
        const doc = makeDocument();
        const pdfjs = makePdfjs(doc);
        const engine = createPdfEngine({ pdfjsLib: pdfjs, log: collector().log });
        await engine.open('http://127.0.0.1:1/__books__/x.pdf', '');
        const pages = JSON.parse(await engine.search('hello'));
        eq('search returns only pages with a match', pages.length, 1);
        eq('search reports the 1-based page number', pages[0].pageNumber, 1);
        eq('search is case-insensitive and finds every item', pages[0].hits.length, 2);
        const rect = pages[0].hits[0].rects[0];
        // engine:pdf PdfSearchEngine.parseRects drops hits whose rects are empty
        // or non-positive — an empty rects list is silently invisible.
        check('search emits at least one rect per hit', pages[0].hits[0].rects.length === 1);
        check('search rects are positive in PDF user space',
            rect.x > 0 && rect.y > 0 && rect.width > 0 && rect.height > 0,
            JSON.stringify(rect));
        eq('search rect x comes from the text matrix', rect.x, 72);
        eq('search keeps the page text for the snippet', pages[0].hits[0].text.indexOf('Hello World') >= 0, true);
        eq('search finds nothing for an absent term', JSON.parse(await engine.search('absent-term')).length, 0);
        eq('search returns an empty list for a blank query', await engine.search('   '), '[]');
        const second = JSON.parse(await engine.search('second'));
        eq('search scans later pages', second[0].pageNumber, 2);
        await engine.close();
        let threw = null;
        try { await engine.search('hello'); } catch (e) { threw = e.message; }
        check('search refuses to run on a closed document', threw !== null && /not open/.test(threw), String(threw));
    }

    console.log('pdfengine: outline');
    {
        const doc = makeDocument();
        const pdfjs = makePdfjs(doc);
        const engine = createPdfEngine({ pdfjsLib: pdfjs, log: collector().log });
        await engine.open('http://127.0.0.1:1/__books__/x.pdf', '');
        const tree = JSON.parse(await engine.outline());
        eq('outline keeps the root entries', tree.length, 2);
        // Stub getPageIndex() returns 0-based 1, so the resolved page is 2.
        eq('outline resolves a named destination to a 1-based page', tree[0].pageNumber, 2);
        eq('outline keeps nesting', tree[0].children.length, 1);
        eq('outline resolves a raw destination array', tree[0].children[0].pageNumber, 2);
        eq('outline keeps an unresolvable entry as page 0', tree[1].pageNumber, 0);
        eq('outline keeps the title', tree[1].title, 'Broken');
        // OutlineResolver.resolve() consumes exactly this shape.
        check('outline emits the OutlineResolver shape',
            typeof tree[0].title === 'string' && Array.isArray(tree[0].children) && 'pageNumber' in tree[0]);

        const failing = createPdfEngine({
            pdfjsLib: { getDocument: () => ({ promise: Promise.resolve({ numPages: 1, fingerprints: ['f'], destroy() {}, getPage: async () => ({ getViewport: () => ({ width: 1, height: 1 }) }) }) }) },
            log: collector().log,
        });
        await failing.open('http://127.0.0.1:1/x.pdf', '');
        eq('outline degrades to [] when the document has no /Outlines', await failing.outline(), '[]');
    }

    console.log('pdfengine: unsupported boundaries');
    {
        const sink = collector();
        const doc = makeDocument();
        const engine = createPdfEngine({ pdfjsLib: makePdfjs(doc), log: sink.log });
        await engine.open('http://127.0.0.1:1/x.pdf', '');
        eq('selectedRect returns null (no text layer in the raster host)', await engine.selectedRect(), null);
        eq('paintHighlights returns false (no text layer)', engine.paintHighlights([]), false);
        check('both boundaries log a warning rather than failing silently',
            sink.lines.filter((l) => l.indexOf('unsupported in the raster host') >= 0).length === 2,
            sink.lines.join(' | '));
    }

    console.log('pdfengine: close');
    {
        const doc = makeDocument();
        const engine = createPdfEngine({ pdfjsLib: makePdfjs(doc), log: collector().log });
        await engine.open('http://127.0.0.1:1/x.pdf', '');
        engine.close();
        eq('close destroys the pdf.js document', doc.destroyed, true);
        eq('close clears the open flag', engine.isOpen, false);
        eq('close resets the page count', engine.pageCount, 0);
    }

    console.log('pdfengine: dispatch');
    {
        const doc = makeDocument();
        const engine = createPdfEngine({
            pdfjsLib: makePdfjs(doc),
            log: collector().log,
            createCanvas: makeCanvasFactory().create,
        });
        await engine.open('http://127.0.0.1:1/x.pdf', '');
        const seen = [];
        const host = { onResult: (id, payload, error) => seen.push({ id, payload, error }) };
        await dispatch(engine, 'c1', 'renderPage', [1, 200], host);
        eq('dispatch answers exactly once', seen.length, 1);
        eq('dispatch reports the call id', seen[0].id, 'c1');
        eq('dispatch passes a string payload unquoted', seen[0].payload, 'QUJD');
        eq('dispatch reports no error on success', seen[0].error, '');
        await dispatch(engine, 'c2', 'outline', [], host);
        check('dispatch JSON-encodes object/array results', seen[1].payload.startsWith('[{'), seen[1].payload.slice(0, 20));
        // PdfJsHostBridge.runJs throws "PDF engine: <error>" on a non-empty error.
        await dispatch(engine, 'c3', 'nope', [], host);
        eq('dispatch rejects an unknown method', seen[2].error, 'unknown method: nope');
        eq('dispatch sends an empty payload on error', seen[2].payload, '');
        await dispatch(engine, 'c4', 'open', [], host);
        check('dispatch forwards engine errors', /requires the book URL/.test(seen[3].error), seen[3].error);
        // Kotlin's method names must all resolve to real engine methods.
        const missing = METHODS.filter((m) => typeof engine[m] !== 'function');
        eq('every METHODS entry exists on the engine', missing.join(','), '');
    }

    console.log('pdfengine: packaged assets');
    {
        const html = fs.readFileSync(path.join(ENGINE_DIR, 'index.html'), 'utf8');
        const refs = [];
        const re = /(?:href|src)\s*=\s*["']([^"']+)["']/g;
        let m;
        while ((m = re.exec(html)) !== null) refs.push(m[1]);
        // The previous revision linked ./pdf_viewer.css, which is not shipped.
        const localMissing = refs.filter((r) =>
            !/^(https?:)?\/\//.test(r) && !r.startsWith('data:') &&
            !fs.existsSync(path.join(ENGINE_DIR, r.replace(/^\.\//, ''))));
        eq('index.html references no missing local asset', localMissing.join(','), '');
        check('index.html loads the engine module', /engine\.mjs/.test(html));
        for (const required of ['pdf.mjs', 'pdf.worker.mjs', 'cmaps']) {
            check(`assets/pdfengine ships ${required}`, fs.existsSync(path.join(ENGINE_DIR, required)));
        }
    }

    const total = passed + failures.length;
    if (failures.length > 0) {
        console.error(`\n[test-pdfengine] ${failures.length}/${total} checks FAILED`);
        for (const f of failures) console.error(`  - ${f.name}${f.detail ? ': ' + f.detail : ''}`);
        process.exit(1);
    }
    console.log(`\n[test-pdfengine] OK — ${passed} checks passed`);
}

main().catch((err) => {
    console.error('[test-pdfengine] harness error:', err);
    process.exit(2);
});
