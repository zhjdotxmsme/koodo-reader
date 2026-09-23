#!/usr/bin/env node
/**
 * Phase 0 baseline tool — docs/android-native-migration.md §10:
 *   --modules                      per-file LOC baseline (kookit / foliate-js)
 *   --schema <file|dir|bootstrap>  SQLite schema -> schema.lock
 *       file|dir   real desktop profile (dir prefers its config/ subdir;
 *                  main.js keeps DBs at <storage>/config/<name>.db)
 *       bootstrap  no profile on this machine? materialise shipped DDL
 *                  (kookit-extra.min.mjs) with main.js getDBConnection semantics
 *                  (create + best-effort migrate, WAL), then extract
 * Options: --out <path> --kookit-dir <dir> --foliate-dir <dir> --asar <app.asar> --no-clone --help
 * Defaults: docs/android-loc-baseline.json (modules) | schema.lock (schema)
 */
"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const Module = require("module");
const { execFileSync } = require("child_process");
const { pathToFileURL } = require("url");

const REPO_ROOT = path.resolve(__dirname, "..");
const CACHE_DIR = path.join(REPO_ROOT, "node_modules", ".cache", "android-baseline");
const LOC_DEFAULT_OUT = path.join(REPO_ROOT, "docs", "android-loc-baseline.json");
const SCHEMA_DEFAULT_OUT = path.join(REPO_ROOT, "schema.lock");
const MAIN_DDL = path.join(REPO_ROOT, "src", "assets", "lib", "kookit-extra.min.mjs");
const BROWSER_DDL = path.join(REPO_ROOT, "src", "assets", "lib", "kookit-extra-browser.min.js");
const ENGINE_MODULES = {
  kookit: { url: "https://github.com/koodo-reader/kookit.git", branch: "dev" },
  "foliate-js": { url: "https://github.com/johnfactotum/foliate-js.git", branch: "main" },
};
const CODE_EXTS = new Set([".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx"]);
const SKIP_DIRS = new Set(["node_modules", ".git", "dist", "build", "out", "coverage", "release"]);

function fail(msg, code = 1) {
  console.error(`[android-baseline] ${msg}`);
  process.exit(code);
}

function usage() {
  console.log(
    [
      "Usage:",
      "  node scripts/android-baseline.js --modules [--kookit-dir d] [--foliate-dir d] [--no-clone] [--out f]",
      "  node scripts/android-baseline.js --schema <data.db | profile-dir | bootstrap> [--asar f] [--out schema.lock]",
      "",
      "  --modules  per-file LOC of kookit(dev) + foliate-js(main); missing checkouts are",
      "             shallow-cloned into node_modules/.cache/android-baseline/repos/.",
      "  --schema   sqlite_master + pragmas -> schema.lock. 'bootstrap' builds the DBs from the",
      "             shipped DDL (main .mjs, cross-checked vs browser bundle; --asar also checks",
      "             the shipped asar copy), applies migrations like main.js, then extracts.",
    ].join("\n")
  );
}

function parseArgs(argv) {
  const opts = { mode: null, schemaTarget: null, out: null, kookitDir: null, foliateDir: null, asar: null, noClone: false, help: false };
  const VALUE = { "--schema": "schema", "--out": "out", "--kookit-dir": "kookitDir", "--foliate-dir": "foliateDir", "--asar": "asar" };
  for (let i = 0; i < argv.length; i++) {
    let a = argv[i];
    let v = null;
    if (a === "--help" || a === "-h") { opts.help = true; continue; }
    if (a === "--modules") { opts.mode = "modules"; continue; }
    if (a === "--no-clone") { opts.noClone = true; continue; }
    const eq = a.indexOf("=");
    if (eq > 0) { v = a.slice(eq + 1); a = a.slice(0, eq); }
    if (a in VALUE) {
      if (v === null) { v = argv[++i]; if (v === undefined) fail(`missing value for ${a}`, 2); }
      if (a === "--schema") { opts.mode = "schema"; opts.schemaTarget = v; } else opts[VALUE[a]] = v;
      continue;
    }
    fail(`unknown argument: ${argv[i]}`, 2);
  }
  return opts;
}

/* ---------------- LOC baseline (--modules) ---------------- */

function countLoc(file) {
  const src = fs.readFileSync(file, "utf8");
  let lines = 0, blank = 0, comment = 0, code = 0, inBlock = false;
  for (const raw of src.split(/\r?\n/)) {
    lines++;
    let s = raw.trim();
    if (!s) { blank++; continue; }
    if (inBlock) {
      const end = s.indexOf("*/");
      if (end === -1) { comment++; continue; }
      s = s.slice(end + 2).trim();
      inBlock = false;
      if (!s) { comment++; continue; }
    }
    if (s.startsWith("//") || s.startsWith("#")) { comment++; continue; }
    if (s.startsWith("/*")) {
      const end = s.indexOf("*/", 2);
      if (end === -1) { inBlock = true; comment++; continue; }
      s = s.slice(end + 2).trim();
      if (!s) { comment++; continue; }
    }
    code++;
  }
  return { lines, blank, comment, code };
}

function walkCodeFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".") || SKIP_DIRS.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkCodeFiles(full, out);
    else if (entry.isFile() && CODE_EXTS.has(path.extname(entry.name)) && !/\.min\.[cm]?[jt]sx?$/.test(entry.name)) out.push(full);
  }
  return out;
}

function ensureCheckout(name, spec, opts) {
  const dest = path.join(CACHE_DIR, "repos", name);
  if (fs.existsSync(path.join(dest, ".git"))) return dest;
  if (opts.noClone) fail(`checkout missing: ${dest} (clone ${spec.url} or drop --no-clone)`, 2);
  fs.rmSync(dest, { recursive: true, force: true }); // drop partial clones
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  console.log(`[android-baseline] shallow-cloning ${name} (${spec.branch}) ...`);
  try {
    execFileSync("git", ["clone", "--depth", "1", "--branch", spec.branch, spec.url, dest], { stdio: "pipe" });
  } catch (e) {
    fail(`git clone failed for ${name}: ${(e.stderr && e.stderr.toString()) || e.message}`, 1);
  }
  return dest;
}

function gitCommit(dir) {
  try {
    return execFileSync("git", ["-C", dir, "rev-parse", "HEAD"], { stdio: "pipe" }).toString().trim();
  } catch {
    return "unknown";
  }
}

function runModules(opts) {
  const result = {
    generatedAt: new Date().toISOString(),
    method: "line-based LOC (approx): code = non-blank, non comment-only lines of .js/.jsx/.mjs/.cjs/.ts/.tsx (minified excluded)",
    modules: {},
    perFile: {},
  };
  for (const [name, spec] of Object.entries(ENGINE_MODULES)) {
    const override = name === "kookit" ? opts.kookitDir : opts.foliateDir;
    const dir = override ? path.resolve(override) : ensureCheckout(name, spec, opts);
    if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) fail(`not a directory: ${dir}`, 2);
    const files = walkCodeFiles(dir).sort();
    const totals = { files: 0, lines: 0, blank: 0, comment: 0, code: 0 };
    const byExtension = {};
    const perFile = {};
    for (const f of files) {
      const rel = path.relative(dir, f).split(path.sep).join("/");
      const c = countLoc(f);
      perFile[rel] = c;
      totals.files++;
      totals.lines += c.lines; totals.blank += c.blank; totals.comment += c.comment; totals.code += c.code;
      const ext = path.extname(f) || "(none)";
      const b = (byExtension[ext] = byExtension[ext] || { files: 0, code: 0, lines: 0 });
      b.files++; b.code += c.code; b.lines += c.lines;
    }
    result.modules[name] = {
      url: spec.url,
      ref: override ? String(override) : spec.branch,
      commit: gitCommit(dir),
      totals,
      byExtension,
    };
    result.perFile[name] = perFile;
    const top = Object.entries(perFile).sort((a, b) => b[1].code - a[1].code).slice(0, 10);
    console.log(`\n${name} @ ${result.modules[name].commit.slice(0, 8)} (${dir})`);
    console.log(`  files=${totals.files}  lines=${totals.lines}  code=${totals.code}  blank=${totals.blank}  comment=${totals.comment}`);
    for (const [f, c] of top) console.log(`    ${String(c.code).padStart(6)}  ${f}`);
  }
  const out = opts.out ? path.resolve(opts.out) : LOC_DEFAULT_OUT;
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(result, null, 2) + "\n");
  console.log(`\n[android-baseline] wrote ${path.relative(REPO_ROOT, out)}`);
}

/* ---------------- schema extraction (--schema) ---------------- */

// sql.js (WASM, repo-shipped at public/lib/sqljs-wasm) — zero native deps, so the
// script runs on any Node/CI without node-gyp. Same engine family the desktop
// export/restore path uses (src/utils/file/sqlUtil.ts).
const SQLJS_DIR = path.join(REPO_ROOT, "public", "lib", "sqljs-wasm");
let sqlJsPromise = null;

function loadSqlJs() {
  if (!sqlJsPromise) {
    let initSqlJs;
    try {
      initSqlJs = require(path.join(SQLJS_DIR, "sql-wasm.js"));
    } catch (e) {
      fail(`sql.js loader unavailable at ${SQLJS_DIR}: ${e.message}`);
    }
    sqlJsPromise = initSqlJs({ locateFile: (f) => path.join(SQLJS_DIR, f) });
  }
  return sqlJsPromise;
}

function rowsOf(result) {
  if (!result || !result.length) return [];
  const { columns, values } = result[0];
  return values.map((v) => Object.fromEntries(columns.map((c, i) => [c, v[i]])));
}

function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

// Deterministic stringify (recursively sorted keys) so DDL cross-checks are order-proof.
function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function ddlFingerprint(statements) {
  return stableStringify({ c: statements.createTableStatement, m: statements.migrateStatement || {} });
}

// kookit-extra.min.mjs transitively requires electron-store -> electron, which throws
// under plain Node (main.js runs it inside Electron). Stub 'electron' at the CJS loader
// so the exact shipped DDL can be loaded outside Electron.
function installElectronStub() {
  if (Module.__koodoBaselineStubbed) return;
  const orig = Module._load;
  Module._load = function (request) {
    if (request === "electron") {
      return {
        app: { getPath: () => require("os").tmpdir(), getVersion: () => "0.0.0-baseline-stub", isPackaged: false, getName: () => "koodo-reader" },
        ipcMain: { on() {}, handle() {} },
        dialog: {},
        shell: {},
      };
    }
    return orig.apply(this, arguments);
  };
  Module.__koodoBaselineStubbed = true;
}

async function loadSqlStatements(mjsPath) {
  installElectronStub();
  const m = await import(pathToFileURL(mjsPath).href);
  if (!m.SqlStatement || !m.SqlStatement.sqlStatement) fail(`${mjsPath} did not export SqlStatement`);
  return m.SqlStatement.sqlStatement;
}

function loadBrowserSqlStatements() {
  const m = require(BROWSER_DDL);
  if (!m.SqlStatement || !m.SqlStatement.sqlStatement) fail(`${BROWSER_DDL} did not export SqlStatement`);
  return m.SqlStatement.sqlStatement;
}

function extractShippedDdl(asarPath) {
  let asar;
  try {
    asar = require("@electron/asar");
  } catch (e) {
    fail(`--asar needs @electron/asar (present in devDependencies): ${e.message}`, 2);
  }
  // Windows-built archives index nested paths with backslash separators.
  const candidates = ["src\\assets\\lib\\kookit-extra.min.mjs", "src/assets/lib/kookit-extra.min.mjs"];
  for (const p of candidates) {
    try {
      const buf = asar.extractFile(asarPath, p);
      // MUST live inside the repo so bare specifiers (axios, ...) resolve via node_modules.
      const dest = path.join(CACHE_DIR, "shipped-kookit-extra.mjs");
      fs.mkdirSync(CACHE_DIR, { recursive: true });
      fs.writeFileSync(dest, buf);
      return { dest, sha256: sha256(buf) };
    } catch {
      /* try next candidate */
    }
  }
  fail(`kookit-extra.min.mjs not found inside ${asarPath}`, 2);
}

function collectDbFiles(target) {
  const abs = path.resolve(target);
  if (!fs.existsSync(abs)) fail(`schema target not found: ${abs}`, 2);
  if (fs.statSync(abs).isFile()) return [abs];
  const configDir = path.join(abs, "config");
  const dir = fs.existsSync(configDir) && fs.statSync(configDir).isDirectory() ? configDir : abs;
  const files = fs
    .readdirSync(dir)
    .filter((f) => f.toLowerCase().endsWith(".db"))
    .sort()
    .map((f) => path.join(dir, f));
  if (!files.length) fail(`no *.db files under ${dir} (desktop profiles live in <storage>/config/*.db)`, 2);
  return files;
}

async function extractOneDatabase(file) {
  const SQL = await loadSqlJs();
  const db = new SQL.Database(fs.readFileSync(file));
  try {
    const rows = rowsOf(db.exec("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name")).filter(
      (r) => !r.name.startsWith("sqlite_") || r.name.startsWith("sqlite_autoindex")
    );
    const q = (sql) => rowsOf(db.exec(sql));
    const tables = {};
    const views = {};
    const triggers = {};
    const standaloneIndexes = {};
    for (const r of rows) {
      if (r.type === "table") {
        const columns = q(`PRAGMA table_info(${JSON.stringify(r.name)})`).map((c) => ({
          name: c.name, type: c.type, notnull: !!c.notnull, dflt: c.dflt_value, pk: c.pk,
        }));
        const indexes = {};
        for (const idx of q(`PRAGMA index_list(${JSON.stringify(r.name)})`)) {
          const cols = q(`PRAGMA index_info(${JSON.stringify(idx.name)})`).map((c) => c.name);
          const master = rows.find((x) => x.type === "index" && x.name === idx.name);
          indexes[idx.name] = { sql: master ? master.sql : null, unique: !!idx.unique, origin: idx.origin, partial: !!idx.partial, columns: cols };
        }
        tables[r.name] = { sql: r.sql, columns, indexes };
      } else if (r.type === "view") views[r.name] = { sql: r.sql };
      else if (r.type === "trigger") triggers[r.name] = { sql: r.sql };
      else if (r.type === "index") standaloneIndexes[r.name] = { sql: r.sql, table: r.tbl_name };
    }
    return {
      pragmas: {
        user_version: (q("PRAGMA user_version")[0] || {}).user_version,
        application_id: (q("PRAGMA application_id")[0] || {}).application_id,
      },
      tables,
      ...(Object.keys(views).length ? { views } : {}),
      ...(Object.keys(triggers).length ? { triggers } : {}),
      ...(Object.keys(standaloneIndexes).length ? { indexes: standaloneIndexes } : {}),
    };
  } finally {
    db.close();
  }
}

async function buildLock(provenance, files) {
  const databases = {};
  for (const f of files) databases[path.basename(f, path.extname(f))] = await extractOneDatabase(f);
  const sorted = {};
  for (const k of Object.keys(databases).sort()) sorted[k] = databases[k];
  return { lockVersion: 1, generatedAt: new Date().toISOString(), provenance, databases: sorted };
}

function writeLock(lock, opts) {
  const out = opts.out ? path.resolve(opts.out) : SCHEMA_DEFAULT_OUT;
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(lock, null, 2) + "\n");
  const summary = Object.entries(lock.databases).map(([name, d]) => `${name}(${Object.keys(d.tables).length}t)`).join(" ");
  console.log(`[android-baseline] wrote ${path.relative(REPO_ROOT, out)} — ${Object.keys(lock.databases).length} databases: ${summary}`);
}

async function bootstrapSchema(opts) {
  if (!fs.existsSync(MAIN_DDL)) fail(`shipped DDL not found: ${MAIN_DDL}`, 2);
  const rel = (p) => path.relative(REPO_ROOT, p).split(path.sep).join("/");
  const statements = await loadSqlStatements(MAIN_DDL);
  // Cross-check: browser bundle must agree with the main-process .mjs, otherwise the
  // lock's source of truth is ambiguous — refuse to write a lying lock.
  const browser = loadBrowserSqlStatements();
  const cross = {
    file: rel(BROWSER_DDL),
    sha256: sha256(fs.readFileSync(BROWSER_DDL)),
    match: ddlFingerprint(statements) === ddlFingerprint(browser),
  };
  if (!cross.match) fail("DDL drift: kookit-extra.min.mjs != kookit-extra-browser.min.js (create/migrate statements differ)", 1);
  const provenance = {
    mode: "bootstrap",
    note: "No populated desktop profile on this machine; DBs materialised from shipped DDL using main.js getDBConnection semantics (create + best-effort migrate, WAL), then extracted.",
    ddl: { file: rel(MAIN_DDL), sha256: sha256(fs.readFileSync(MAIN_DDL)) },
    crossCheck: cross,
  };
  if (opts.asar) {
    const shipped = extractShippedDdl(path.resolve(opts.asar));
    const shippedStatements = await loadSqlStatements(shipped.dest);
    provenance.shippedAsar = {
      file: path.resolve(opts.asar),
      ddlSha256: shipped.sha256,
      match: ddlFingerprint(statements) === ddlFingerprint(shippedStatements),
    };
    if (!provenance.shippedAsar.match) fail(`shipped asar DDL drifts from repo ${rel(MAIN_DDL)}`, 1);
  }
  const bootDir = path.join(CACHE_DIR, "bootstrap-db");
  fs.rmSync(bootDir, { recursive: true, force: true });
  const configDir = path.join(bootDir, "config");
  fs.mkdirSync(configDir, { recursive: true });
  const SQL = await loadSqlJs();
  const names = Object.keys(statements.createTableStatement).sort();
  const migrations = {};
  for (const name of names) {
    const db = new SQL.Database();
    try {
      db.exec("PRAGMA journal_mode = WAL;"); // same as main.js getDBConnection (no-op on sql.js memory VFS)
      db.exec(statements.createTableStatement[name]);
      const applied = [];
      const failed = [];
      for (const sql of (statements.migrateStatement && statements.migrateStatement[name]) || []) {
        try {
          db.exec(sql);
          applied.push(sql.replace(/\s+/g, " ").slice(0, 80));
        } catch (e) {
          // main.js swallows per-statement migration errors; mirror but record them.
          failed.push(`${e.message}: ${sql.replace(/\s+/g, " ").slice(0, 80)}`);
        }
      }
      migrations[name] = { applied, failed };
      if (failed.length) console.error(`[android-baseline] warn: ${failed.length} migration(s) failed for ${name} (mirrors main.js best-effort)`);
    } finally {
      fs.writeFileSync(path.join(configDir, `${name}.db`), Buffer.from(db.export()));
      db.close();
    }
  }
  provenance.engine = "sql.js (public/lib/sqljs-wasm) — same engine family as the desktop export path (sqlUtil.ts)";
  provenance.createOrder = names;
  provenance.migrations = migrations;
  writeLock(await buildLock(provenance, collectDbFiles(configDir)), opts);
}

async function runSchema(opts) {
  if (!opts.schemaTarget) fail("--schema requires a <file|dir> target or 'bootstrap'", 2);
  if (opts.schemaTarget === "bootstrap") {
    await bootstrapSchema(opts);
    return;
  }
  const files = collectDbFiles(opts.schemaTarget);
  const provenance = {
    mode: "profile",
    source: path.resolve(opts.schemaTarget),
    files: files.map((f) => ({ file: path.basename(f), sha256: sha256(fs.readFileSync(f)) })),
  };
  writeLock(await buildLock(provenance, files), opts);
}

if (require.main === module) {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    usage();
    process.exit(0);
  } else if (opts.mode === "modules") {
    runModules(opts);
  } else if (opts.mode === "schema") {
    runSchema(opts).catch((e) => fail(e.stack || e.message));
  } else {
    usage();
    process.exit(2);
  }
}

module.exports = { countLoc, walkCodeFiles, parseArgs, stableStringify, ddlFingerprint, extractOneDatabase, ENGINE_MODULES };




