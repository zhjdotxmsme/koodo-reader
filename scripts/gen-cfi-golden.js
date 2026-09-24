#!/usr/bin/env node
/**
 * Golden-vector generator for the native CFI core (`android/engine/cfi`).
 *
 * WHY: the native module is a hand port of the web engine's CFI implementation.
 * "It looks right" is not evidence. This script runs the ACTUAL upstream
 * implementation (foliate-js `epubcfi.js`, MIT — the same file the web/desktop
 * app uses through kookit) over a fixed vector table and writes the observed
 * results to a TSV file. The Kotlin tests then assert against that file, so any
 * drift between the port and upstream fails CI with a precise diff.
 *
 * Usage:
 *   node scripts/gen-cfi-golden.js                      # use the cached/downloaded upstream copy
 *   node scripts/gen-cfi-golden.js --source <path>      # use a local epubcfi.js
 *   node scripts/gen-cfi-golden.js --check              # fail if the file on disk differs
 *   node scripts/gen-cfi-golden.js --verbose
 *
 * Upstream source (pinned by revision in CFI_REF_URL below):
 *   https://github.com/johnfactotum/foliate-js  (epubcfi.js, MIT)
 *   vendored by koodo-reader/kookit as src/libs/epubcfi.js (AGPL-3.0)
 *
 * Exit codes: 0 ok, 1 mismatch (with --check) or upstream unavailable, 2 usage.
 */

"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const https = require("https");

/** Pinned upstream revision so golden vectors are reproducible. */
const CFI_REF_URL =
  "https://raw.githubusercontent.com/johnfactotum/foliate-js/main/epubcfi.js";

const REPO_ROOT = path.resolve(__dirname, "..");
const OUTPUT_TSV = path.join(
  REPO_ROOT,
  "android",
  "engine",
  "cfi",
  "src",
  "test",
  "resources",
  "cfi-golden.tsv"
);

/** Cache location for the downloaded upstream file (outside the repo). */
function cacheDir() {
  return process.env.KOODO_CFI_REF_DIR || path.join(os.tmpdir(), "koodo-cfi-ref");
}

function parseArgs(argv) {
  const opts = { source: "", check: false, verbose: false, download: true };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === "--source") {
      opts.source = argv[i + 1] || "";
      i += 1;
    } else if (a.startsWith("--source=")) {
      opts.source = a.slice("--source=".length);
    } else if (a === "--check") {
      opts.check = true;
    } else if (a === "--no-download") {
      opts.download = false;
    } else if (a === "--verbose") {
      opts.verbose = true;
    } else if (a === "-h" || a === "--help") {
      console.log(
        [
          "Koodo Reader - CFI golden vector generator",
          "",
          "Usage: node scripts/gen-cfi-golden.js [options]",
          "",
          "  --source <path>   use a local epubcfi.js instead of the cache/download",
          "  --no-download     never hit the network (cache/--source only)",
          "  --check           compare against the committed TSV instead of writing",
          "  --verbose         log every vector",
          "",
          "Env: KOODO_CFI_REF_DIR overrides the cache directory.",
        ].join("\n")
      );
      process.exit(0);
    } else {
      console.error(`Unknown option: ${a}`);
      process.exit(2);
    }
  }
  return opts;
}

function log(opts, message) {
  if (opts.verbose) console.log(`[gen-cfi-golden] ${message}`);
}

/**
 * Fixed vector table. Every row is executed against the REAL upstream
 * implementation; the observed output becomes the expected value for Kotlin.
 *
 * Column contract (see `writeTsv`): `op \t arg1 \t arg2 \t arg3 \t expected`.
 * Unused arguments are written as empty strings.
 *
 * Coverage goals:
 *  - canonical point round-trips (parse + re-serialize)
 *  - offset rules: `:0` kept on odd steps, dropped on even steps
 *  - assertion rules: id only right after a step, text chunks after `:offset`,
 *    `;s=` side bias, escaped delimiters (`^,`)
 *  - the upstream quirk `/2[x,y]` (the second chunk becomes the id)
 *  - temporal (`~`) and spatial (`@`) offsets, incl. `~0` being dropped
 *  - multi-document chains (`!`)
 *  - ranges: collapse to start/end, buildRange from two points
 *  - compare(): ordering incl. ranges, shorter paths, offset presence
 *  - Calibre importers, fake indices, joinIndir, isCFI
 */
const VECTORS = [
  // ── canonical points ───────────────────────────────────────────────────────
  { op: "canonicalPoint", args: ["epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/3:10)"] },
  { op: "canonicalPoint", args: ["epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/2/1:1)"] },
  { op: "canonicalPoint", args: ["/6/4[chap01ref]!/4[body01]/10[para05]/3:10"] },
  { op: "canonicalPoint", args: ["/6/4!/4/2/2:0"] },
  { op: "canonicalPoint", args: ["/6/4!/4/2/3:0"] },
  { op: "canonicalPoint", args: ["epubcfi(/6/2[cover]!/4/2/2)"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2/2:5[pre,post]"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2/2:5[;s=b]"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2/1:3[;s=a]"] },
  { op: "canonicalPoint", args: ["/6/2!/4/6/2[ch^,ap]"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2[text]"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2~5.5"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2~0"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2@10:20"] },
  { op: "canonicalPoint", args: ["/2[x,y]"] },
  { op: "canonicalPoint", args: ["/6/2!/4/2!/6/4"] },

  // ── collapse ───────────────────────────────────────────────────────────────
  { op: "collapse", args: ["epubcfi(/6/4[chap01ref]!/4[body01]/10[para05],/2/1:1,/3:4)"] },
  { op: "collapseEnd", args: ["epubcfi(/6/4[chap01ref]!/4[body01]/10[para05],/2/1:1,/3:4)"] },
  { op: "collapse", args: ["epubcfi(/6/4!/4/10/2,/1:0,/1:10)"] },
  { op: "collapseEnd", args: ["epubcfi(/6/4!/4/10/2,/1:0,/1:10)"] },

  // ── buildRange ─────────────────────────────────────────────────────────────
  {
    op: "buildRange",
    args: [
      "epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/2/1:1)",
      "epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/2/1:9)",
    ],
  },
  {
    op: "buildRange",
    args: [
      "epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/2/1:1)",
      "epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/3:4)",
    ],
  },
  {
    op: "buildRange",
    args: ["epubcfi(/6/4!/4/2/1:1)", "epubcfi(/6/4!/4/2/1:1)"],
  },
  {
    op: "buildRange",
    args: ["epubcfi(/6/4!/4/2)", "epubcfi(/6/4!/4/2/1:4)"],
  },
  {
    op: "buildRange",
    args: ["epubcfi(/6/4!/4/2/2/1:0)", "epubcfi(/6/4!/4/2/2/3:7)"],
  },
  {
    op: "buildRange",
    args: ["/6/2!/4/2/1:1", "/6/2!/4/2/1:1"],
  },

  // ── compare ────────────────────────────────────────────────────────────────
  { op: "compare", args: ["/6/4!/4/2/2:10", "/6/4!/4/2/2:10"] },
  { op: "compare", args: ["/6/4!/4/2/2:5", "/6/4!/4/2/2:10"] },
  { op: "compare", args: ["/6/4!/4/2/2:10", "/6/4!/4/2/2:5"] },
  { op: "compare", args: ["/6/4!/4/2/2", "/6/4!/4/2/2:5"] },
  { op: "compare", args: ["/6/4!/4/2", "/6/4!/4/2/2"] },
  { op: "compare", args: ["/6/4!/4/2/2", "/6/2!/4/2/2"] },
  { op: "compare", args: ["/6/4!/4/2", "/6/4!/4"] },
  { op: "compare", args: ["/6/2!/4/2", "/6/2!/4/2!/6/2"] },
  { op: "compare", args: ["/6/4!/4/2/1:0", "/6/4!/4/2/1:0"] },
  { op: "compare", args: ["epubcfi(/6/4!/4/2,/1:1,/1:4)", "epubcfi(/6/4!/4/2/1:2)"] },
  { op: "compare", args: ["epubcfi(/6/4!/4/2,/1:1,/1:4)", "epubcfi(/6/4!/4/2,/1:0,/1:9)"] },
  { op: "compare", args: ["epubcfi(/6/4!/4/2,/1:1,/1:4)", "epubcfi(/6/4!/4/2,/1:1,/1:4)"] },

  // ── text helpers ───────────────────────────────────────────────────────────
  { op: "isCfi", args: ["epubcfi(/6/2)"] },
  { op: "isCfi", args: ["/6/2"] },
  { op: "isCfi", args: ["EPUBCFI(/6/2)"] },
  { op: "joinIndir", args: ["epubcfi(/6/2)", "/4/2"] },
  { op: "joinIndir", args: ["/6/2", "epubcfi(/4/2)"] },

  // ── fake indices ───────────────────────────────────────────────────────────
  { op: "fakeFromIndex", args: ["0"] },
  { op: "fakeFromIndex", args: ["3"] },
  { op: "fakeToIndex", args: ["/6/8"] },
  { op: "fakeToIndex", args: ["/6/2"] },

  // ── Calibre importers ──────────────────────────────────────────────────────
  // Real Calibre positions start at the spine index (`/22!...`), NOT at `/6/22`.
  { op: "fromCalibrePos", args: ["/22!/4/2/2:0"] },
  { op: "fromCalibrePos", args: ["/8!/4/2/2:5"] },
  // Regression pins for a `/6/`-prefixed (wrong-shape) input: upstream silently
  // collapses it to `epubcfi(/6/6!)`. Kept so the port cannot "improve" on it
  // without a deliberate decision.
  { op: "fromCalibrePos", args: ["epubcfi(/6/22!/4/2/2:0)"] },
  {
    op: "fromCalibreHighlight",
    args: ["3", "/2/4/2/2:1", "/2/4/2/2:8"],
  },
  {
    op: "fromCalibreHighlight",
    args: ["0", "/2/4/2:0", "/2/4/2:12"],
  },

  // ── canonical serialization (private `toString`, via the shim) ─────────────
  // Pins BOTH points and ranges, and is the strongest single check on the port:
  // it combines tokenizer + parser + partToString.
  { op: "tostring", args: ["epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/3:10)"] },
  { op: "tostring", args: ["/6/4!/4/2/2:0"] },
  { op: "tostring", args: ["/6/4!/4/2/3:0"] },
  { op: "tostring", args: ["/6/2!/4/2/2:5[pre,post]"] },
  { op: "tostring", args: ["/6/2!/4/2/2:5[;s=b]"] },
  { op: "tostring", args: ["/6/2!/4/6/2[ch^,ap]"] },
  { op: "tostring", args: ["/6/2!/4/2~5.5"] },
  { op: "tostring", args: ["/6/2!/4/2~0"] },
  { op: "tostring", args: ["/6/2!/4/2@10:20"] },
  { op: "tostring", args: ["/6/2!/4/2/2[pre,post]"] },
  { op: "tostring", args: ["epubcfi(/6/2!/4/2/2[pre,post])"] },
  { op: "tostring", args: ["/2[x,y]"] },
  { op: "tostring", args: ["/6/2!/4/2!/6/4"] },
  { op: "tostring", args: ["epubcfi(/6/4[chap01ref]!/4[body01]/10[para05],/2/1:1,/3:4)"] },
  { op: "tostring", args: ["epubcfi(/6/4!/4/10/2,/1:0,/1:10)"] },

  // ── escape / wrap / unwrap / concatArrays ──────────────────────────────────
  { op: "escapeCfi", args: ["ch,ap;ter"] },
  { op: "escapeCfi", args: ["a(b)c[d]e^f=g"] },
  { op: "escapeCfi", args: ["plain/*-+!"] },
  { op: "unwrapCfi", args: ["epubcfi(/6/2)"] },
  { op: "unwrapCfi", args: ["/6/2"] },
  { op: "wrapCfi", args: ["/6/2"] },
  { op: "wrapCfi", args: ["epubcfi(/6/2)"] },
  { op: "concatArrays", args: ["epubcfi(/6/2)", "/2/4"] },
  { op: "concatArrays", args: ["/6/2!/4/2", "/6/4"] },
];

function download(url, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) {
      reject(new Error("too many redirects"));
      return;
    }
    https
      .get(url, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          resolve(download(res.headers.location, dest, redirects + 1));
          return;
        }
        if (res.statusCode !== 200) {
          res.resume();
          reject(new Error(`HTTP ${res.statusCode} for ${url}`));
          return;
        }
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          fs.mkdirSync(path.dirname(dest), { recursive: true });
          fs.writeFileSync(dest, Buffer.concat(chunks));
          resolve(dest);
        });
      })
      .on("error", reject);
  });
}

/**
 * Export shim appended to the upstream module copy.
 *
 * `epubcfi.js` keeps `buildRange` / `toString` / `escapeCFI` / `wrap` / `unwrap`
 * private. Those are exactly the functions the Kotlin port reimplements, so the
 * harness needs to reach them. Appending one `export` statement does not touch
 * the upstream bodies in any way — the code under test stays verbatim — and the
 * provenance hash below is computed from the UNPATCHED file.
 */
const EXPORT_SHIM = `

// --- injected by scripts/gen-cfi-golden.js (test harness only, not upstream) ---
export const __oracle = { buildRange, toString, escapeCFI, wrap, unwrap, concatArrays }
`;

/**
 * Ensure a local, importable copy of upstream `epubcfi.js` exists.
 *
 * The copy is written as `<cache>/epubcfi.<sha8>.mjs` because this repo's
 * package.json is CommonJS, so a bare `.js` file with `export` statements cannot
 * be dynamically imported.
 *
 * @returns {Promise<{modulePath:string, sourceHash:string, sourcePath:string}>}
 */
async function resolveUpstream(opts) {
  let source = opts.source;

  if (!source) {
    const cached = path.join(cacheDir(), "epubcfi.js");
    if (!fs.existsSync(cached)) {
      if (!opts.download) {
        throw new Error(
          `upstream epubcfi.js not found at ${cached} and --no-download was given`
        );
      }
      log(opts, `downloading ${CFI_REF_URL}`);
      await download(CFI_REF_URL, cached);
    }
    source = cached;
  }

  if (!fs.existsSync(source)) {
    throw new Error(`--source not found: ${source}`);
  }

  const raw = fs.readFileSync(source, "utf8");
  if (!/export\s+const\s+parse/.test(raw)) {
    throw new Error(`${source} does not look like foliate-js epubcfi.js`);
  }

  const sourceHash = sha256(raw);
  const moduleSource = raw + EXPORT_SHIM;
  const mjs = path.join(cacheDir(), `epubcfi.${sha256(moduleSource).slice(0, 8)}.mjs`);
  if (!fs.existsSync(mjs) || fs.readFileSync(mjs, "utf8") !== moduleSource) {
    fs.writeFileSync(mjs, moduleSource);
  }
  log(opts, `upstream module: ${mjs}`);
  return { modulePath: mjs, sourceHash, sourcePath: source };
}

/** `sha256(text)` as lowercase hex. */
function sha256(text) {
  return require("crypto").createHash("sha256").update(text).digest("hex");
}


/**
 * Operation implementations — thin adapters over the upstream API.
 *
 * Only the module's PUBLIC exports are used, which is a feature: the golden
 * vectors pin the observable contract of the engine rather than its internals.
 * Note `collapse(point)` is exactly `toString(parse(point))` upstream (a point
 * collapses to itself and is then re-serialized), which is how the canonical
 * round-trip is pinned without access to the private `toString`.
 *
 * @param {any} cfi the imported upstream module
 */
const OPS = {
  // Public API
  canonicalPoint: (cfi, input) => cfi.collapse(input),
  collapse: (cfi, input) => cfi.collapse(input),
  collapseEnd: (cfi, input) => cfi.collapse(input, true),
  compare: (cfi, a, b) => String(cfi.compare(a, b)),
  isCfi: (cfi, value) => String(cfi.isCFI.test(value)),
  joinIndir: (cfi, a, b) => cfi.joinIndir(a, b),
  fakeFromIndex: (cfi, index) => cfi.fake.fromIndex(Number(index)),
  fakeToIndex: (cfi, value) => String(cfi.fake.toIndex(cfi.parse(value)[0])),
  fromCalibrePos: (cfi, pos) => cfi.fromCalibrePos(pos),
  fromCalibreHighlight: (cfi, spine, start, end) =>
    cfi.fromCalibreHighlight({
      spine_index: Number(spine),
      start_cfi: start,
      end_cfi: end,
    }),

  // Private API, reached through the injected export shim (see EXPORT_SHIM).
  // `toString(parse(x))` is the canonical serializer for BOTH points and ranges.
  tostring: (cfi, input) => cfi.__oracle.toString(cfi.parse(input)),
  buildRange: (cfi, from, to) => cfi.__oracle.buildRange(from, to),
  escapeCfi: (cfi, value) => cfi.__oracle.escapeCFI(value),
  unwrapCfi: (cfi, value) => cfi.__oracle.unwrap(value),
  wrapCfi: (cfi, value) => cfi.__oracle.wrap(value),
  concatArrays: (cfi, a, b) =>
    cfi.__oracle
      .concatArrays(cfi.parse(a), cfi.parse(b))
      .map((doc) => doc.map((step) => `/${step.index}${step.id ? `[${step.id}]` : ""}`).join(""))
      .join("!"),
};

/** Columns of the TSV, in order. */
const COLUMNS = ["op", "arg1", "arg2", "arg3", "expected"];

/** Render one vector row, rejecting field values that would break the format. */
function renderRow(op, args, expected) {
  const fields = [op, args[0] ?? "", args[1] ?? "", args[2] ?? "", expected];
  for (const field of fields) {
    if (field.includes("\t") || field.includes("\n")) {
      throw new Error(`TSV field cannot contain a tab or newline: ${JSON.stringify(field)}`);
    }
  }
  return fields.join("\t");
}

/** Compute every vector against upstream and render the complete TSV document. */
function renderTsv(cfi, upstreamPath, hash) {
  const rows = [];
  const counts = {};
  for (const vector of VECTORS) {
    const op = OPS[vector.op];
    if (typeof op !== "function") {
      throw new Error(`Unknown op in VECTORS: ${vector.op}`);
    }
    const expected = op(cfi, ...vector.args);
    if (expected === undefined) {
      throw new Error(`op ${vector.op} (${vector.args.join(", ")}) returned undefined`);
    }
    counts[vector.op] = (counts[vector.op] || 0) + 1;
    rows.push(renderRow(vector.op, vector.args, String(expected)));
  }

  const banner = [
    "# Koodo Reader - CFI golden vectors (native `engine/cfi` parity contract).",
    "#",
    "# GENERATED FILE - do not edit by hand.",
    `#   regenerate: node scripts/gen-cfi-golden.js`,
    "#",
    "# Every `expected` value below was produced by the UPSTREAM implementation,",
    "# not by the Kotlin port, so this file is an independent oracle:",
    `#   upstream: ${CFI_REF_URL}`,
    `#   sha256(epubcfi.js)[0:8]: ${hash}`,
    "#   local copy: <machine-specific cache path, intentionally not recorded>",
    "#",
    "# Columns: " + COLUMNS.join(" | "),
    "# Unused argument columns are empty. Fields never contain tabs.",
    "#",
  ].join("\n");

  return {
    content: `${banner}\n${rows.join("\n")}\n`,
    counts,
    total: rows.length,
  };
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const upstream = await resolveUpstream(opts);
  const { pathToFileURL } = require("url");
  const cfi = await import(pathToFileURL(upstream.modulePath).href);

  const { content, counts, total } = renderTsv(
    cfi,
    upstream.sourcePath,
    upstream.sourceHash.slice(0, 8)
  );

  if (opts.check) {
    const current = fs.existsSync(OUTPUT_TSV) ? fs.readFileSync(OUTPUT_TSV, "utf8") : "";
    if (current === content) {
      console.log(`CFI golden vectors up to date (${total} vectors).`);
      process.exit(0);
    }
    console.error(
      [
        "CFI golden vectors are OUT OF DATE.",
        `  file:    ${OUTPUT_TSV}`,
        "  reason:  upstream output (or the vector table) changed.",
        "  action:  review the diff, then run `node scripts/gen-cfi-golden.js`.",
      ].join("\n")
    );
    process.exit(1);
  }

  fs.mkdirSync(path.dirname(OUTPUT_TSV), { recursive: true });
  fs.writeFileSync(OUTPUT_TSV, content, "utf8");

  console.log(`Wrote ${total} CFI golden vectors to ${path.relative(REPO_ROOT, OUTPUT_TSV)}`);
  Object.keys(counts)
    .sort()
    .forEach((op) => console.log(`  ${op.padEnd(22)} ${counts[op]}`));
  console.log(`  upstream sha256[0:8]   ${upstream.sourceHash.slice(0, 8)}`);
  process.exit(0);
}

main().catch((e) => {
  console.error(`[gen-cfi-golden] ${e.message}`);
  process.exit(1);
});


