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
