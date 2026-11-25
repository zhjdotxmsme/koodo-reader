#!/usr/bin/env node
/*
 * test-foliate-host.js — unit tests for the foliate reflow engine core
 * (`android/app/src/main/assets/foliate/engine.mjs`).
 *
 * Mirrors scripts/test-pdfengine.js: import the real module in Node with
 * injected loaders so TOC flattening, TXT conversion, and dispatch are pinned
 * without a WebView.
 *
 * Usage:
 *   node scripts/test-foliate-host.js
 *   node scripts/test-foliate-host.js --quiet
 */
'use strict';

const path = require('path');
const { pathToFileURL } = require('url');

const ROOT = path.join(__dirname, '..');
const ENGINE_MJS = path.join(
    ROOT, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'engine.mjs',
);
const STYLE_MJS = path.join(
    ROOT, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'readest-style.mjs',
);

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
    check(
        name,
        actual === expected,
        `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
}

function collector() {
    const lines = [];
    return { lines, log: (level, message) => lines.push(level + ': ' + message) };
}

async function main() {
    const {
        createFoliateEngine, dispatch, encodePayload, flattenToc, splitTxtChapters, mdToHtml,
        selectionPayload, appearanceCss, chromeTapAction, parseRules, applyRulesToHtml,
        wordRangeAtPoint,
    } =
        await import(pathToFileURL(ENGINE_MJS).href);

    eq('encodePayload passes strings through', encodePayload('hello'), 'hello');
    eq('encodePayload stringifies objects', encodePayload({ a: 1 }), '{"a":1}');
    eq('encodePayload maps undefined to null', encodePayload(undefined), 'null');

    const nested = flattenToc([
        {
            label: '第一章 中原',
            href: 'Text/part0000.xhtml#chapter',
            subitems: [{ label: '第二节', href: 'Text/part0001.xhtml#s2' }],
        },
        { title: '附录', href: 'Text/app.xhtml' },
    ]);
    eq('flattenToc keeps NCX labels, not filenames', nested[0].label, '第一章 中原');
    eq('flattenToc nested child keeps its label', nested[1].label, '第二节');
    eq('flattenToc nested child depth is 1', nested[1].depth, 1);
    eq('flattenToc falls back to title', nested[2].label, '附录');

    const chapters = splitTxtChapters(
        '前言忽略\n\n第一章 天地\n正文甲\n\n第二章 人间\n正文乙\n',
    );
    eq('splitTxtChapters finds two chapters', chapters.length, 2);
    check(
        'splitTxtChapters first title is Chinese heading',
        chapters[0].title.indexOf('第一章') >= 0,
        chapters[0] && chapters[0].title,
    );
    check(
        'splitTxtChapters first body keeps chapter text',
        chapters[0].body.indexOf('正文甲') >= 0,
        chapters[0] && chapters[0].body,
    );

    const md = mdToHtml('# Title\n\nHello **world**.');
    check('mdToHtml emits an h1', md.indexOf('<h1>') >= 0 && md.indexOf('Title') >= 0, md);
    check('mdToHtml emits a paragraph', md.indexOf('<p>') >= 0 && md.indexOf('Hello') >= 0, md);

    eq('chromeTapAction left is prev', chromeTapAction(10, 300), 'prev');
    eq('chromeTapAction right is next', chromeTapAction(290, 300), 'next');
    eq('chromeTapAction middle is toggle', chromeTapAction(150, 300), 'toggle');
    eq('chromeTapAction zero width is toggle', chromeTapAction(0, 0), 'toggle');

    const sink = collector();
    const engine = createFoliateEngine({
        log: sink.log,
        fetchFile: async (url) => ({
            name: url.split('/').pop() || 'book',
            text: async () => {
                if (url.indexOf('.txt') >= 0) {
                    return '第一章 天地\n正文甲\n\n第二章 人间\n正文乙\n';
                }
                return '<html><body><p>hi</p></body></html>';
            },
        }),
        openBook: async (file, format) => {
            if (format === 'epub' || format === 'mobi' || format === 'azw3') {
                return {
                    metadata: { title: 'Fixture' },
                    toc: [
                        { label: '第一章 中原', href: 'Text/part0000.xhtml#chapter' },
                        { label: '第二章', href: 'Text/part0001.xhtml' },
                    ],
                    sections: [{ id: '1' }, { id: '2' }],
                };
            }
            throw new Error('unexpected format ' + format);
        },
    });

    const epubOpen = await engine.open('http://127.0.0.1:9/__books__/n.epub', 'epub');
    eq('open epub reports two sections', epubOpen.sectionCount, 2);
    const epubToc = JSON.parse(await engine.toc());
    eq('open epub toc first label is Chinese', epubToc[0].label, '第一章 中原');
    check(
        'open epub toc is not a filename',
        epubToc[0].label.indexOf('part0000') < 0,
        epubToc[0].label,
    );

    const txtOpen = await engine.open('http://127.0.0.1:9/__books__/n.txt', 'txt');
    check('open txt converts into chapters', txtOpen.sectionCount >= 2, String(txtOpen.sectionCount));
    const txtToc = JSON.parse(await engine.toc());
    check(
        'open txt toc keeps 第一章',
        txtToc.some((item) => item.label.indexOf('第一章') >= 0),
        JSON.stringify(txtToc),
    );

    const host = {
        results: [],
        onResult(id, payload, error) { this.results.push({ id, payload, error }); },
    };
    await dispatch(engine, 't1', 'toc', [], host);
    check('dispatch toc succeeds', !host.results[0].error, String(host.results[0].error));
    check(
        'dispatch toc payload is JSON array',
        host.results[0].payload.charAt(0) === '[',
        host.results[0].payload,
    );

    await dispatch(engine, 't2', 'nope', [], host);
    check(
        'dispatch unknown method errors once',
        host.results[1].error.indexOf('unknown method') >= 0,
        host.results[1].error,
    );

    await engine.goTo('Text/part0000.xhtml#chapter');
    eq('goTo records last target', engine.lastTarget, 'Text/part0000.xhtml#chapter');

    const turns = [];
    const turning = createFoliateEngine({
        log: sink.log,
        view: {
            next: async () => { turns.push('next'); },
            prev: async () => { turns.push('prev'); },
        },
        fetchFile: async () => ({ name: 'n', text: async () => '' }),
        openBook: async () => ({ metadata: {}, toc: [], sections: [] }),
    });
    await dispatch(turning, 'n1', 'next', [], host);
    await dispatch(turning, 'p1', 'prev', [], host);
    eq('dispatch next calls view.next', turns[0], 'next');
    eq('dispatch prev calls view.prev', turns[1], 'prev');
    check(
        'dispatch next succeeds',
        !host.results.find((r) => r.id === 'n1').error,
        String(host.results.find((r) => r.id === 'n1').error),
    );

    await engine.setAppearance({ bg: '#fff', fg: '#111', fontSize: 18 });
    eq('setAppearance records fontSize', engine.appearance.fontSize, 18);

    await engine.setViewSettings({
        bg: 'rgba(44, 47, 49,1)', fg: 'rgba(255,255,255,1)',
        defaultFontSize: 13.6, lineHeight: 1.75, marginPx: 16,
        maxColumnCount: 2, scrolled: false, overrideColor: true,
        isDarkMode: true, colorScheme: 'dark', defaultFont: 'serif',
    });
    eq('setViewSettings records paginated flow', engine.flow, 'paginated');
    eq('setViewSettings records two columns', engine.maxColumnCount, 2);

    const attrs = {};
    const marginEngine = createFoliateEngine({
        view: {
            renderer: {
                setStyles() {},
                setAttribute(name, value) { attrs[name] = value; },
            },
        },
        log: collector().log,
        fetchFile: async () => ({ name: 'x', text: async () => '' }),
        openBook: async () => ({ metadata: {}, toc: [], sections: [] }),
    });
    await marginEngine.setViewSettings({
        bg: '#111', fg: '#eee',
        defaultFontSize: 13.6, lineHeight: 1.5, marginPx: 24,
        maxColumnCount: 2, scrolled: false, overrideColor: true,
        isDarkMode: true, colorScheme: 'dark', defaultFont: 'serif',
    });
    eq('setViewSettings sets flow attribute', attrs.flow, 'paginated');
    eq('setViewSettings sets margin-left', attrs['margin-left'], '24px');
    eq('setViewSettings sets margin-top', attrs['margin-top'], '24px');

    const inlineAttrs = {};
    const inlineEngine = createFoliateEngine({
        view: {
            renderer: {
                setStyles() {},
                setAttribute(name, value) { inlineAttrs[name] = value; },
            },
        },
        log: collector().log,
        fetchFile: async () => ({ name: 'x', text: async () => '' }),
        openBook: async () => ({ metadata: {}, toc: [], sections: [] }),
    });
    await inlineEngine.setViewSettings({
        bg: '#fff', fg: '#111',
        defaultFontSize: 13.6, lineHeight: 1.5, marginPx: 0,
        maxColumnCount: 1, scrolled: true, overrideColor: true,
        isDarkMode: false, colorScheme: 'light', defaultFont: 'serif',
        maxInlineSizePx: 560,
    });
    eq('setViewSettings sets max-inline-size from perf', inlineAttrs['max-inline-size'], '560px');

    await engine.close();
    eq('close drops the book', engine.isOpen, false);

    const { selectionRangeIsUsable } = await import(pathToFileURL(ENGINE_MJS).href);
    eq('collapsed drag range is rejected before replacing a live selection', selectionRangeIsUsable({ collapsed: true }), false);
    eq('non-collapsed drag range remains usable', selectionRangeIsUsable({ collapsed: false }), true);
    eq('missing drag range is rejected', selectionRangeIsUsable(null), false);

    eq(
        'collapsed selection hides the host menu',
        JSON.stringify(selectionPayload({ isCollapsed: true, toString: () => '字' }, 'epubcfi(/6/2)')),
        JSON.stringify({ excerpt: '', cfi: '' }),
    );
    eq(
        'non-collapsed selection keeps excerpt and cfi',
        JSON.stringify(selectionPayload({ isCollapsed: false, toString: () => ' 陶寺的兴衰 ' }, 'epubcfi(/6/4)')),
        JSON.stringify({ excerpt: '陶寺的兴衰', cfi: 'epubcfi(/6/4)' }),
    );
    eq(
        'selection payload carries the press point when given',
        JSON.stringify(selectionPayload({ isCollapsed: false, toString: () => ' 陶寺的兴衰 ' }, 'epubcfi(/6/4)', { px: 12.5, py: 88 })),
        JSON.stringify({ excerpt: '陶寺的兴衰', cfi: 'epubcfi(/6/4)', px: 12.5, py: 88 }),
    );

    // wordRangeAtPoint: CJK ideographs select one char, Latin/digit runs the
    // whole word, punctuation falls back to the bare caret range.
    const fakeTextNode = (text) => ({ nodeType: 3, textContent: text });
    const fakeDoc = (text, offset) => ({
        caretRangeFromPoint: () => ({ startContainer: fakeTextNode(text), startOffset: offset }),
        createRange: () => {
            const r = { s: 0, e: 0 };
            r.setStart = (n, o) => { r.s = o; };
            r.setEnd = (n, o) => { r.e = o; };
            return r;
        },
    });
    const wordRange = (text, offset) => {
        const r = wordRangeAtPoint(fakeDoc(text, offset), 0, 0);
        return r && r.setStart ? [r.s, r.e] : null;
    };
    eq('cjk long-press selects a single ideograph', JSON.stringify(wordRange('陶寺的兴衰', 2)), JSON.stringify([2, 3]));
    eq('cjk caret at run start selects the first ideograph', JSON.stringify(wordRange('陶寺的兴衰', 0)), JSON.stringify([0, 1]));
    eq('latin long-press selects the whole word', JSON.stringify(wordRange('hello world, ok', 2)), JSON.stringify([0, 5]));
    eq('latin word after a space expands from its own run', JSON.stringify(wordRange('hello world, ok', 8)), JSON.stringify([6, 11]));
    eq('digits select as a run', JSON.stringify(wordRange('chapter 2044 end', 11)), JSON.stringify([8, 12]));
    eq('touch between word and punctuation selects the adjacent word', JSON.stringify(wordRange('hello, world', 5)), JSON.stringify([0, 5]));
    eq('blank position falls back to the caret range', wordRange('', 0), null);

    const nightCss = appearanceCss({
        bg: 'rgba(44, 47, 49,1)',
        fg: 'rgba(255,255,255,1)',
        fontSize: 18,
        lineHeight: 1.75,
        marginPx: 16,
        colorScheme: 'dark',
        invertImages: false,
    });
    check(
        'night CSS sets color-scheme dark',
        nightCss.indexOf('color-scheme: dark') >= 0,
        nightCss.slice(0, 180),
    );
    check(
        'night CSS overrides nested book colours',
        nightCss.indexOf('background-color: transparent') >= 0
            && nightCss.indexOf('!important') >= 0,
        nightCss.slice(0, 240),
    );
    check(
        'night CSS applies line-height and padding',
        nightCss.indexOf('line-height: 1.75') >= 0 && nightCss.indexOf('padding: 16px') >= 0,
        nightCss.slice(0, 240),
    );
    check(
        'night CSS does not invert images by default',
        nightCss.indexOf('filter: invert') < 0,
        nightCss.slice(0, 120),
    );
    const invertCss = appearanceCss({
        bg: '#111',
        fg: '#eee',
        fontSize: 16,
        invertImages: true,
        colorScheme: 'dark',
    });
    check(
        'invertImages adds img filter',
        invertCss.indexOf('filter: invert') >= 0,
        invertCss.slice(0, 200),
    );

    const goTos = [];
    const ruleReload = createFoliateEngine({
        view: {
            goTo: async (target) => { goTos.push(target); },
            renderer: { setStyles() {}, setAttribute() {} },
        },
        log: collector().log,
        fetchFile: async () => ({ name: 'n.epub', text: async () => '' }),
        openBook: async () => ({
            metadata: { title: 'R' },
            toc: [],
            sections: [{ id: '0', href: '0', load: async () => '<p>ok</p>' }],
        }),
    });
    await ruleReload.open('http://127.0.0.1:9/__books__/n.epub', 'epub');
    await ruleReload.goTo('0');
    await ruleReload.setViewSettings({
        textRulesJson: '[{"id":"1","pattern":"广告","replacement":""}]',
        bg: '#fff', fg: '#111',
        defaultFontSize: 13.6, lineHeight: 1.5, marginPx: 0,
        maxColumnCount: 1, scrolled: true, overrideColor: true,
        isDarkMode: false, colorScheme: 'light', defaultFont: 'serif',
    });
    eq('setViewSettings re-navigates after rule change', goTos[goTos.length - 1], '0');
    const goToCountAfterRuleChange = goTos.length;
    await ruleReload.setViewSettings({
        textRulesJson: '[{"id":"1","pattern":"广告","replacement":""}]',
        marginPx: 24,
    });
    eq('setViewSettings does not re-navigate for unchanged rules', goTos.length, goToCountAfterRuleChange);

    const rules = parseRules('[{"id":"1","pattern":"广告","replacement":""}]');
    eq('parseRules reads bare array', rules.length, 1);
    const cleaned = applyRulesToHtml('<p>前缀广告后缀</p>', rules);
    check(
        'applyRulesToHtml removes matched text',
        cleaned.indexOf('广告') < 0 && cleaned.indexOf('前缀') >= 0,
        cleaned,
    );

    let cssCaptured = '';
    const cssEngine = createFoliateEngine({
        view: {
            renderer: {
                setStyles(css) { cssCaptured = css; },
                setAttribute() {},
            },
        },
        log: collector().log,
        fetchFile: async () => ({ name: 'x', text: async () => '' }),
        openBook: async () => ({ metadata: {}, toc: [], sections: [] }),
    });
    await cssEngine.setViewSettings({
        bg: '#fff', fg: '#111',
        defaultFontSize: 13.6, lineHeight: 1.5, marginPx: 0,
        maxColumnCount: 1, scrolled: true, overrideColor: true,
        isDarkMode: false, colorScheme: 'light', defaultFont: 'serif',
        userStylesheet: 'body { outline: 1px solid red; }',
    });
    check(
        'setViewSettings appends userStylesheet to renderer css',
        cssCaptured.indexOf('outline: 1px solid red') >= 0,
        cssCaptured.slice(0, 240),
    );

    const { getStyles } = await import(pathToFileURL(STYLE_MJS).href);
    const css = getStyles(
        {
            marginTopPx: 16, marginRightPx: 16, marginBottomPx: 16, marginLeftPx: 16,
            writingMode: 'auto', vertical: false, useBookLayout: false, overrideLayout: true,
            paragraphMargin: 1, lineHeight: 1.5, wordSpacing: 0, letterSpacing: 0,
            textIndent: 0, fullJustification: false, hyphenation: false,
            serifFont: 'Georgia', sansSerifFont: 'Arial', monospaceFont: 'Consolas',
            defaultFont: 'serif', defaultCJKFont: 'Noto Serif CJK', defaultFontSize: 13.6,
            minimumFontSize: 12, fontWeight: 400, overrideFont: false,
            overrideColor: true, invertImgColorInDark: false, backgroundTextureId: '',
            isEink: false, userStylesheet: '', zoomLevel: 100,
        },
        { bg: 'rgba(44, 47, 49,1)', fg: 'rgba(255,255,255,1)', isDarkMode: true, primary: '#fff', base100: '#2c2f31' },
    );
    check('readest getStyles night overrides nested colours', css.indexOf('!important') >= 0, css.slice(0, 200));

    // ── Sidemark tick in the foliate overlay (P1-10) ─────────────────────────
    {
        // Overlayer draws into SVG elements, so a minimal DOM is enough to pin
        // the geometry: what matters is the x/width of the tick relative to the
        // highlighted rects, and that it never leaves the page.
        const nodes = [];
        function fakeElement(tag) {
            const el = {
                tag,
                attributes: {},
                children: [],
                style: {},
                setAttribute(name, value) { el.attributes[name] = value; },
                append(child) { el.children.push(child); },
            };
            nodes.push(el);
            return el;
        }
        globalThis.document = { createElementNS: (_ns, tag) => fakeElement(tag) };
        try {
            const { Overlayer } = await import(
                pathToFileURL(path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'overlayer.js')).href
            );
            const rects = [
                { left: 40, top: 100, right: 300, bottom: 124, width: 260, height: 24,
                    page: { left: 0, top: 0, right: 360, bottom: 640 } },
                { left: 40, top: 124, right: 200, bottom: 148, width: 160, height: 24,
                    page: { left: 0, top: 0, right: 360, bottom: 640 } },
            ];

            nodes.length = 0;
            const group = Overlayer.highlight(rects, {
                color: 'rgba(255,224,102,0.3)',
                sidemarkWidth: 3,
                sidemarkPadLeft: 20,
                sidemarkPadRight: 20,
            });
            const [band, marks] = group.children;
            eq('the band and the ticks are sibling groups', group.children.length, 2);
            check(
                'the tick group is NOT inside the 30% band group',
                group.children.indexOf(marks) === 1 && marks.children.length === 2,
                group.children.map((c) => c.children.length).join(','),
            );
            eq('the outer group carries no opacity of its own', group.style.opacity, undefined);
            eq('the band group keeps the 30% wash', band.style.opacity, 'var(--overlayer-highlight-opacity, .3)');
            eq(
                'the tick drops the wash alpha the caller baked into the colour',
                marks.attributes.fill,
                'rgb(255,224,102)',
            );
            eq('the tick sits in the page side padding, left of the column', marks.children[0].attributes.x, 15);
            eq('the tick is 3px wide', marks.children[0].attributes.width, 3);
            eq('the tick spans the line box', marks.children[0].attributes.height, 24);
            eq('the tick carries no opacity of its own', marks.style.opacity, undefined);
            check(
                'the tick does not follow a mid-line selection',
                // Both lines in `rects` start at left=40, so both ticks land at
                // the column edge regardless of where each run starts.
                marks.children.every((c) => c.attributes.x === 15),
                marks.children.map((c) => c.attributes.x).join(','),
            );

            nodes.length = 0;
            const midLine = Overlayer.highlight(
                [{ left: 180, top: 0, right: 240, bottom: 24, width: 60, height: 24,
                    page: { left: 0, top: 0, right: 360, bottom: 640 } }],
                { color: '#ffe066', sidemarkWidth: 3, sidemarkPadLeft: 20 },
            ).children[1];
            eq('a mid-line run still marks the margin', midLine.children[0].attributes.x, 15);

            nodes.length = 0;
            const rtlGroup = Overlayer.highlight(rects, {
                color: '#ffe066', sidemarkWidth: 3, sidemarkRtl: true,
                sidemarkPadLeft: 20, sidemarkPadRight: 20,
            });
            const rtlMarks = rtlGroup.children[1];
            eq('rtl sidemark mirrors to the right margin', rtlMarks.children[0].attributes.x, 342);

            nodes.length = 0;
            const tight = Overlayer.highlight(rects, {
                color: '#ffe066', sidemarkWidth: 3, sidemarkPadLeft: 1,
            });
            eq(
                'a margin narrower than the tick draws no mark at all',
                tight.children.length,
                2,
            );
            eq('… so the tick group stays empty', tight.children[1].children.length, 0);

            const none = Overlayer.highlight(rects, { color: '#ffe066' });
            eq('no tick group without a width', none.children.length, 1);
            eq('the bands are still drawn', none.children[0].children.length, 2);
        } finally {
            delete globalThis.document;
        }
    }

    // ── Highlight re-anchoring ──────────────────────────────────────────────
    {
        const { spineIndexOf, findTextRange } = await import(pathToFileURL(ENGINE_MJS).href);

        eq('spineIndexOf reads a point cfi', spineIndexOf('epubcfi(/6/2[cover]!/4/2:0)'), 1);
        eq('spineIndexOf reads a range cfi', spineIndexOf('epubcfi(/6/4!/4/2:0,/4/2:50)'), 1);
        eq('spineIndexOf rejects odd steps', spineIndexOf('epubcfi(/6/2!/3/1)'), null);
        eq('spineIndexOf rejects no-indir cfis', spineIndexOf('epubcfi(/6/4)'), null);
        eq('spineIndexOf rejects garbage', spineIndexOf(''), null);

        function fakeDoc(texts) {
            const body = { children: texts.map((data) => ({ data })) };
            return {
                body,
                documentElement: body,
                createTreeWalker(root) {
                    let i = -1;
                    return {
                        nextNode() {
                            i++;
                            return i < root.children.length ? root.children[i] : null;
                        },
                    };
                },
                createRange() {
                    const range = {};
                    range.setStart = (node, offset) => { range.start = { node, offset }; };
                    range.setEnd = (node, offset) => { range.end = { node, offset }; };
                    return range;
                },
            };
        }

        const doc = fakeDoc(['前半段', '后半段继续这里', '尾部']);
        const range = findTextRange(doc, '前半段 后半段');
        check(
            'findTextRange matches across text nodes with flexible whitespace',
            range && range.start && range.start.node.data === '前半段' && range.start.offset === 0
                && range.end.node.data === '后半段继续这里' && range.end.offset === 3,
            JSON.stringify(range && range.end),
        );
        const punct = findTextRange(doc, '继续这里，尾部');
        check(
            'findTextRange rejects a needle the document does not contain',
            punct === null,
            JSON.stringify(punct),
        );
        eq('findTextRange needs a real needle', findTextRange(doc, '尾'), null);
        eq('findTextRange tolerates a null doc', findTextRange(null, '前半段'), null);

        const { createFoliateEngine } = await import(pathToFileURL(ENGINE_MJS).href);
        const section0Doc = fakeDoc([
            '重排前的第一段文字足够长可以被唯一找到。',
            '第二段也有内容。',
        ]);
        const drawn = [];
        const reanchorView = {
            resolveCFI: async (cfi) => {
                if (cfi.indexOf('dead') >= 0 || cfi.indexOf('/3/') >= 0) {
                    throw new Error('cfi out of range');
                }
                return {};
            },
            addAnnotation: async (annotation) => {
                drawn.push(annotation);
                return {};
            },
            getCFI: (index, r) =>
                `epubcfi(/6/4!/4/${2 + drawn.length}/1:0,/1:${r.end.offset})`,
            book: {
                sections: [{ createDocument: async () => section0Doc }, {}],
            },
        };
        const anchorEngine = createFoliateEngine({
            log: () => {},
            fetchFile: async () => ({ name: 'b.epub', text: async () => '<html><body><p>x</p></body></html>' }),
            openBook: async () => ({
                metadata: { title: 'Fixture' },
                sections: [
                    { id: '1', createDocument: async () => section0Doc },
                    { id: '2' },
                ],
            }),
            view: reanchorView,
        });
        await anchorEngine.open('http://127.0.0.1:9/__books__/b.epub', 'epub');

        const report = await anchorEngine.reAnchorHighlights([
            { cfi: 'epubcfi(/6/4!/4/2:0)', text: '', color: '#ffe066' },
            {
                cfi: 'epubcfi(/6/2!/2/2:dead0,/2:99)',
                text: '第一段文字足够长',
                color: '#90ee90',
            },
            { cfi: 'epubcfi(/6/4!/3/9:0)', text: 'never matched anywhere', color: '#ff0000' },
            { cfi: '', text: 'ignored' },
        ]);
        eq('reAnchor anchored the healthy cfi', report.anchored, 1);
        eq('reAnchor remapped the dead cfi', report.reanchored.length, 1);
        check(
            'reAnchor kept the same section when remapping',
            report.reanchored[0].cfi.indexOf('!/4/') >= 0
                && report.reanchored[0].old.indexOf('dead') >= 0,
            JSON.stringify(report.reanchored),
        );
        eq('reAnchor reported the unrepairable one', report.failed.length, 1);
        eq('reAnchor ignored an empty cfi', report.failed[0], 'epubcfi(/6/4!/3/9:0)');
        check(
            'reAnchor drew with the requested colour',
            drawn.some((d) => d.color === '#90ee90' && d.value === report.reanchored[0].cfi),
            JSON.stringify(drawn),
        );
        const empty = await anchorEngine.reAnchorHighlights([]);
        eq('reAnchor on no items is a clean report', empty.anchored + empty.failed.length, 0);
    }


    if (failures.length) {
        console.log(`\n${failures.length} failed, ${passed} passed`);
        process.exit(1);
    }
    console.log(`\nOK  ${passed} passed`);
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
