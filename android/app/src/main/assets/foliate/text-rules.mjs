// text-rules.mjs — desktop-compatible text replacement (mirrors TextRuleEngine.kt).

/**
 * @typedef {{ id?: string, pattern?: string, replacement?: string, enabled?: boolean,
 *             isRegex?: boolean, matchType?: string, type?: string }} TextRuleWire
 */

/**
 * @param {string} json
 * @returns {TextRuleWire[]}
 */
export function parseRules(json) {
    const raw = String(json || '').trim();
    if (!raw) return [];
    try {
        const root = JSON.parse(raw);
        if (Array.isArray(root)) return root;
        if (root && Array.isArray(root.textRuleList) && root.textRules) {
            return root.textRuleList
                .map((id) => root.textRules[id])
                .filter(Boolean);
        }
    } catch (e) {
        return [];
    }
    return [];
}

function isRegexRule(rule) {
    if (rule.matchType === 'regex') return true;
    if (rule.matchType === 'plain') return false;
    return !!rule.isRegex;
}

function ruleType(rule) {
    return String(rule.type || 'replace');
}

/**
 * @param {string} text
 * @param {TextRuleWire[]} rules
 * @returns {string}
 */
export function applyRulesToText(text, rules) {
    if (!text || !rules || !rules.length) return text;
    let current = text;
    for (const rule of rules) {
        if (rule.enabled === false) continue;
        const type = ruleType(rule);
        if (type === 'highlight') continue;
        const pattern = String(rule.pattern || '');
        if (!pattern) continue;
        const regex = compilePattern(pattern, isRegexRule(rule));
        if (!regex) continue;
        const replacement = type === 'delete' ? '' : String(rule.replacement || '');
        try {
            current = current.replace(regex, escapeReplacement(replacement, isRegexRule(rule)));
        } catch (e) {
            // skip invalid replacement
        }
    }
    return current;
}

/**
 * Strip script/style then apply rules to text nodes in a simple HTML string.
 * @param {string} html
 * @param {TextRuleWire[]} rules
 * @returns {string}
 */
export function applyRulesToHtml(html, rules) {
    if (!html || !rules || !rules.length) return html;
    if (typeof DOMParser !== 'undefined') {
        try {
            const doc = new DOMParser().parseFromString(
                '<!doctype html><html><head></head><body>' + html + '</body></html>',
                'text/html',
            );
            const walk = (node) => {
                if (!node) return;
                if (node.nodeType === 3) {
                    node.textContent = applyRulesToText(node.textContent || '', rules);
                    return;
                }
                if (node.nodeType !== 1) return;
                const tag = node.tagName && node.tagName.toLowerCase();
                if (tag === 'script' || tag === 'style') return;
                for (let i = 0; i < node.childNodes.length; i++) walk(node.childNodes[i]);
            };
            walk(doc.body);
            return doc.body.innerHTML;
        } catch (e) {
            // fall through
        }
    }
    return applyRulesToText(html, rules);
}

function compilePattern(pattern, isRegex) {
    try {
        if (isRegex) return new RegExp(pattern, 'g');
        return new RegExp(pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g');
    } catch (e) {
        return null;
    }
}

function escapeReplacement(replacement, isRegex) {
    if (!replacement) return '';
    if (isRegex) return replacement.replace(/\\/g, '\\\\');
    return replacement.replace(/\\/g, '\\\\').replace(/\$/g, '$$');
}
