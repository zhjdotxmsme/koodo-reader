#!/usr/bin/env node
/**
 * Android (APK) build orchestrator for Koodo Reader.
 *
 * This is the side-effecting driver. All decision logic (config validation,
 * ABI/build-type resolution, Gradle command + artifact path computation,
 * signing rules) lives in the pure, unit-tested core at
 * `src/utils/android/androidBuild.js`. This file only wires config sources
 * together, stages assets, and runs the Android toolchain.
 *
 * Usage:
 *   node scripts/build-android.js [options]
 *
 * Options:
 *   --debug                 build a (self-signed debug) APK
 *   --release               build a release APK (default when not --debug)
 *   --abi <a,b|...>         override ABIs (comma separated), repeatable
 *   --abi=<a,b>             same as above
 *   --no-split              produce one universal APK instead of per-ABI APKs
 *   --build-web             run the web build (`npm run build`) when missing
 *   --skip-web              skip the web-build precondition check entirely
 *   --stage-only            stage assets + print the plan, do NOT run Gradle
 *   --dry-run               identical to --stage-only (prints plan, no build)
 *   --keystore <path>       keystore path (release signing)
 *   --store-password <v>    keystore password
 *   --key-alias <v>         key alias
 *   --key-password <v>      key password
 *   --require-signing       fail (not warn) if a release build has no keystore
 *   --config <path>         path to android.config.json (default ./android.config.json)
 *   --verbose               extra logging
 *   -h, --help              print help
 *
 * Environment (override config):
 *   ANDROID_KEYSTORE, ANDROID_KEYSTORE_PASSWORD,
 *   ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD, ANDROID_REQUIRE_SIGNING
 *
 * Exit codes: 0 on success / successful plan; 2 usage error; 1 build failure.
 */

"use strict";

const path = require("path");
const fs = require("fs");
const { spawnSync } = require("child_process");
const process = require("process");

// The pure core is written to be require-able both by Node (here) and by the
// browser test runner. The relative path resolves from scripts/ to src/.
const core = require("../src/utils/android/androidBuild");

const REPO_ROOT = path.resolve(__dirname, "..");

function printHelp() {
  console.log(
    [
      "Koodo Reader - Android (APK) build",
      "",
      "Usage: node scripts/build-android.js [options]",
      "",
      "Options:",
      "  --debug                 build a (self-signed debug) APK",
      "  --release               build a release APK (default)",
      "  --abi <a,b>             override ABIs (comma separated), repeatable",
      "  --no-split              one universal APK instead of per-ABI APKs",
      "  --build-web             run the web build when missing",
      "  --skip-web              skip the web-build precondition check",
      "  --stage-only            stage assets + print plan, do NOT run Gradle",
      "  --dry-run               same as --stage-only",
      "  --keystore <path>       keystore path (release signing)",
      "  --store-password <v>    keystore password",
      "  --key-alias <v>         key alias",
      "  --key-password <v>      key password",
      "  --require-signing       fail if a release build has no keystore",
      "  --audit                 fail if any feature asset is missing from the package",
      "  --audit-only            stage, run the packaged-feature audit, and stop (no build)",
      "  --config <path>         android.config.json path",
      "  --verbose               extra logging",
      "  -h, --help              this help",
      "",
      "Env: ANDROID_KEYSTORE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS,",
      "     ANDROID_KEY_PASSWORD, ANDROID_REQUIRE_SIGNING",
    ].join("\n")
  );
}

function fail(message, code = 1) {
  process.stderr.write(`[build-android] ${message}\n`);
  process.exit(code);
}

/**
 * Parse argv into a plain options object. Deliberately dependency-free.
 * @param {string[]} argv process.argv.slice(2)
 */
function parseArgs(argv) {
  const opts = {
    debug: false,
    release: true,
    abis: [],
    noSplit: false,
    buildWeb: false,
    skipWeb: false,
    stageOnly: false,
    keystore: "",
    storePassword: "",
    keyAlias: "",
    keyPassword: "",
    requireSigning: false,
    audit: false,
    auditStop: false,
    config: "android.config.json",
    verbose: false,
    help: false,
  };

  const VALUE_FLAGS = new Set([
    "--abi",
    "--keystore",
    "--store-password",
    "--key-alias",
    "--key-password",
    "--config",
  ]);

  let i = 0;
  while (i < argv.length) {
    const a = argv[i];
    let flag = a;
    let inline = "";
    const eq = a.indexOf("=");
    if (eq !== -1) {
      flag = a.slice(0, eq);
      inline = a.slice(eq + 1);
    }

    if (VALUE_FLAGS.has(flag)) {
      if (inline === "") {
        const next = argv[i + 1];
        if (next === undefined || next.indexOf("--") === 0) {
          throw new UsageError(`Option ${flag} requires a value`);
        }
        inline = next;
        i += 1; // consume the value token
      }
      switch (flag) {
        case "--abi":
          opts.abis.push(inline);
          break;
        case "--keystore":
          opts.keystore = inline;
          break;
        case "--store-password":
          opts.storePassword = inline;
          break;
        case "--key-alias":
          opts.keyAlias = inline;
          break;
        case "--key-password":
          opts.keyPassword = inline;
          break;
        case "--config":
          opts.config = inline;
          break;
      }
      i += 1;
      continue;
    }

    switch (flag) {
      case "-h":
      case "--help":
        opts.help = true;
        break;
      case "--debug":
        opts.debug = true;
        opts.release = false;
        break;
      case "--release":
        opts.release = true;
        break;
      case "--no-split":
        opts.noSplit = true;
        break;
      case "--build-web":
        opts.buildWeb = true;
        break;
      case "--skip-web":
        opts.skipWeb = true;
        break;
      case "--stage-only":
      case "--dry-run":
        opts.stageOnly = true;
        break;
      case "--require-signing":
        opts.requireSigning = true;
        break;
      case "--audit":
        opts.audit = true;
        break;
      case "--audit-only":
        opts.audit = true;
        opts.auditStop = true;
        opts.stageOnly = true;
        break;
      case "--verbose":
        opts.verbose = true;
        break;
      default:
        throw new UsageError(`Unknown option: ${a}  (see --help)`);
    }
    i += 1;
  }

  if (opts.abis.length > 0) {
    const joined = opts.abis.join(",");
    opts.abiList = joined
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }
  return opts;
}

class UsageError extends Error {}

function loadRawConfig(opts, env) {
  const configPath = path.isAbsolute(opts.config)
    ? opts.config
    : path.resolve(REPO_ROOT, opts.config);

  let fileCfg = {};
  if (fs.existsSync(configPath)) {
    try {
      fileCfg = JSON.parse(fs.readFileSync(configPath, "utf8"));
    } catch (e) {
      fail(`Failed to parse ${configPath}: ${e.message}`, 2);
    }
  } else if (opts.verbose) {
    console.log(`[build-android] no config at ${configPath}; using defaults`);
  }

  // Merge: file config < package.json version < env < CLI flags.
  const pkgVersion = readPackageVersion();
  const versionName = fileCfg.versionName || pkgVersion || "";
  const raw = Object.assign({}, fileCfg, {
    versionName,
    versionCode: fileCfg.versionCode || deriveVersionCode(versionName),
  });

  if (env.ANDROID_KEYSTORE) raw.signing = Object.assign({}, raw.signing, { keystore: env.ANDROID_KEYSTORE });
  if (env.ANDROID_KEYSTORE_PASSWORD)
    raw.signing = Object.assign({}, raw.signing, { storePassword: env.ANDROID_KEYSTORE_PASSWORD });
  if (env.ANDROID_KEY_ALIAS) raw.signing = Object.assign({}, raw.signing, { keyAlias: env.ANDROID_KEY_ALIAS });
  if (env.ANDROID_KEY_PASSWORD)
    raw.signing = Object.assign({}, raw.signing, { keyPassword: env.ANDROID_KEY_PASSWORD });
  if (env.ANDROID_REQUIRE_SIGNING)
    raw.requireSigning = /^(1|true|yes|on)$/i.test(env.ANDROID_REQUIRE_SIGNING);

  if (opts.keystore) raw.signing = Object.assign({}, raw.signing, { keystore: opts.keystore });
  if (opts.storePassword)
    raw.signing = Object.assign({}, raw.signing, { storePassword: opts.storePassword });
  if (opts.keyAlias) raw.signing = Object.assign({}, raw.signing, { keyAlias: opts.keyAlias });
  if (opts.keyPassword)
    raw.signing = Object.assign({}, raw.signing, { keyPassword: opts.keyPassword });
  if (opts.requireSigning) raw.requireSigning = true;
  if (opts.noSplit) raw.splitPerAbi = false;
  if (opts.debug) raw.buildTypes = ["debug"];
  if (opts.release) raw.buildTypes = ["release"];
  if (opts.abiList && opts.abiList.length) raw.abis = opts.abiList;

  return raw;
}

function readPackageVersion() {
  try {
    const pkg = JSON.parse(fs.readFileSync(path.resolve(REPO_ROOT, "package.json"), "utf8"));
    return pkg.version || "";
  } catch (e) {
    return "";
  }
}

/**
 * Derive a monotonically-increasing integer versionCode from a semver-ish
 * version string (2.4.4 -> 2*10000 + 4*100 + 4*10 = 20440). Falls back to 1.
 * @param {string} versionName
 * @returns {number}
 */
function deriveVersionCode(versionName) {
  const m = String(versionName || "").match(/(\d+)\.(\d+)\.(\d+)/);
  if (m) {
    const code =
      parseInt(m[1], 10) * 10000 + parseInt(m[2], 10) * 100 + parseInt(m[3], 10) * 1;
    if (Number.isFinite(code) && code > 0) return code;
  }
  return 1;
}

function logVerbose(opts, message) {
  if (opts.verbose) console.log(`[build-android] ${message}`);
}

function ensureWebBuild(config, opts) {
  const webRoot = path.resolve(REPO_ROOT, config.webBuildDir);
  const entry = path.resolve(webRoot, config.webBuildEntry);
  if (fs.existsSync(entry)) {
    logVerbose(opts, `web build present at ${entry}`);
    return entry;
  }

  if (opts.skipWeb) {
    console.log(
      `[build-android] ⚠ web build entry not found at ${entry}; continuing because --skip-web was set.`
    );
    return null;
  }

  if (!opts.buildWeb) {
    fail(
      `Web build entry not found: ${entry}\n` +
        `  Run the web build first (e.g. \`npm run build\`), or re-run with --build-web to build it here.`
    );
  }

  console.log(`[build-android] web build missing — running \`npm run build\` ...`);
  const r = spawnSync("npm", ["run", "build"], { cwd: REPO_ROOT, stdio: "inherit" });
  if (r.status !== 0) {
    fail(`Web build failed (exit ${r.status}). Aborting Android build.`, 1);
  }
  if (!fs.existsSync(entry)) {
    fail(`Web build ran but expected entry is still missing: ${entry}`, 1);
  }
  return entry;
}

function stageAssets(config, opts, { allowMissingSource = false } = {}) {
  const source = path.resolve(REPO_ROOT, config.webBuildDir);
  const dest = path.resolve(REPO_ROOT, config.assetStageDir);

  if (!fs.existsSync(source)) {
    if (allowMissingSource) {
      logVerbose(opts, `web build dir not found (${source}); skipping staging (dry-run)`);
      return { staged: 0, skipped: true };
    }
    fail(`Web build directory not found: ${source}`, 1);
  }

  // Start from a clean assets dir so stale files never leak into the APK.
  fs.rmSync(dest, { recursive: true, force: true });
  fs.mkdirSync(dest, { recursive: true });
  fs.cpSync(source, dest, { recursive: true, dereference: true });

  // Drop in a small provenance marker so the packaged app can be debugged.
  const marker = {
    name: config.name,
    packageName: config.packageName,
    versionName: config.versionName,
    versionCode: config.versionCode,
    webBuildDir: config.webBuildDir,
    stagedAt: new Date().toISOString(),
  };
  fs.writeFileSync(path.join(dest, ".koodo-android-build.json"), JSON.stringify(marker, null, 2));

  const count = countFiles(dest);
  logVerbose(opts, `staged ${count} files into ${dest}`);
  return { staged: count, skipped: false };
}

function countFiles(dir) {
  let n = 0;
  const walk = (d) => {
    let items;
    try {
      items = fs.readdirSync(d, { withFileTypes: true });
    } catch (e) {
      return;
    }
    for (const it of items) {
      const p = path.join(d, it.name);
      if (it.isDirectory()) walk(p);
      else if (it.isFile()) n += 1;
    }
  };
  walk(dir);
  return n;
}

function findApks(config) {
  const base = path.resolve(REPO_ROOT, config.outputDir).toLowerCase();
  const hits = [];
  const walk = (d) => {
    let items;
    try {
      items = fs.readdirSync(d, { withFileTypes: true });
    } catch (e) {
      return;
    }
    for (const it of items) {
      const p = path.join(d, it.name);
      if (it.isDirectory()) walk(p);
      else if (it.isFile() && it.name.toLowerCase().endsWith(".apk")) hits.push(p);
    }
  };
  if (fs.existsSync(base)) walk(base);
  return hits.sort();
}

function spawnGradle(step, androidDir) {
  console.log(`[build-android] $ ${step.command} ${step.args.join(" ")}  (cwd: android/)`);

  const r = spawnSync(step.command, step.args, {
    cwd: androidDir,
    stdio: "inherit",
    env: Object.assign({}, process.env, {
      ANDROID_HOME: process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || "",
    }),
  });

  if (r.error && r.error.code === "ENOENT") {
    fail(
      `Could not find "${step.command}". Install the Android SDK + Gradle ` +
        `(or commit a Gradle wrapper in android/), then re-run.`,
      1
    );
  }
  if (r.status !== 0) {
    fail(`Gradle exited with code ${r.status} for "${step.command} ${step.args.join(" ")}".`, 1);
  }
}

function runBuildPlan(config, opts) {
  const androidDir = path.resolve(REPO_ROOT, "android");
  const hasGradlew =
    fs.existsSync(path.join(androidDir, "gradlew")) ||
    fs.existsSync(path.join(androidDir, "gradlew.bat"));
  const platform = process.platform === "win32" ? "win32" : process.platform;
  const binary = core.resolveGradleBinary({ platform, hasGradlew });

  const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || "";
  if (!androidHome) {
    console.log(
      "[build-android] ⚠ ANDROID_HOME / ANDROID_SDK_ROOT is not set. " +
        "If Gradle cannot find the SDK, set ANDROID_HOME (or android/local.properties)."
    );
  }

  const plan = core.buildBuildPlan(config, { gradleBinary: binary, platform });
  plan.forEach((step) => {
    spawnGradle(step, androidDir);
  });
  return { commands: plan.map((s) => s.command), plan };
}

function main(argv) {
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (e) {
    if (e instanceof UsageError) {
      console.error(`\n${e.message}\n`);
      printHelp();
      process.exit(2);
    }
    throw e;
  }

  if (opts.help) {
    printHelp();
    process.exit(0);
  }

  let raw;
  try {
    raw = loadRawConfig(opts, process.env);
  } catch (e) {
    fail(`Config error: ${e.message}`, 2);
  }

  let config;
  try {
    config = core.normalizeConfig(raw);
  } catch (e) {
    fail(`Invalid Android config: ${e.message}`, 2);
  }

  const abis = config.abis;
  const platform = process.platform === "win32" ? "win32" : process.platform;
  const plan = core.buildBuildPlan(config, { platform });
  const expected = plan.map((s) => s.artifact);
  const commands = plan.map((s) => s.command);
  const buildType = plan.length ? plan[0].buildType : "release";

  // Validate the toolchain is at least present when we intend to build.
  const androidDir = path.resolve(REPO_ROOT, "android");
  if (!fs.existsSync(androidDir)) {
    fail(`Android host project not found at ${androidDir}. This repo should contain android/.`, 1);
  }

  if (!opts.stageOnly) {
    ensureWebBuild(config, opts);
  }

  const stageResult =
    opts.stageOnly || !opts.skipWeb ? tryStage(config, opts) : { staged: 0, skipped: true };
  const staged = stageResult.staged;

  // Enforce "all features are packaged": verify staged assets cover index.html.
  if (opts.audit) {
    const audit = auditPackage(config, opts);
    if (!audit.ok) {
      fail("Packaged-feature audit FAILED — a feature asset is missing from the APK.", 1);
    }
    console.log(`AUDIT PASS: all ${audit.total} referenced feature assets are packaged in the APK.`);
    if (opts.auditStop) {
      process.exit(0);
    }
  }

  if (opts.stageOnly) {
    const summary = core.summarizeResult({
      config,
      buildType,
      abis,
      artifacts: expected,
      commands,
      dryRun: true,
      staged,
    });
    console.log("\n==================== DRY RUN (no Gradle executed) ====================");
    console.log(JSON.stringify(summary, null, 2));
    console.log(
      "\nGradle steps to execute:\n" +
        plan.map((s) => `  $ ${s.command} ${s.args.join(" ")}`).join("\n")
    );
    console.log(
      "\nExpected APK artifact(s):\n  " + expected.map((p) => path.resolve(REPO_ROOT, p)).join("\n  ")
    );
    process.exit(0);
  }

  // Real build: run every step in the plan (one per ABI when splitting).
  const runInfo = runBuildPlan(config, opts);

  // Prefer artifacts on disk; fall back to the expected set if none were found.
  const found = findApks(config);
  const artifacts = found.length > 0 ? found : expected;

  const summary = core.summarizeResult({
    config,
    buildType,
    abis,
    artifacts,
    commands: runInfo.commands,
    dryRun: false,
    staged,
  });

  console.log("\n==================== ANDROID BUILD SUMMARY ====================");
  console.log(JSON.stringify(summary, null, 2));
  if (found.length === 0) {
    console.log(
      "\n⚠ No .apk found under the expected output directory; look for it in:\n  " +
        expected.map((p) => path.resolve(REPO_ROOT, p)).join("\n  ")
    );
  }
  process.exit(0);
}

function tryStage(config, opts) {
  try {
    const result = stageAssets(config, opts, { allowMissingSource: !!opts.stageOnly });
    if (result.skipped) {
      console.log(
        `[build-android] ⚠ skipped asset staging (web build not found at ${config.webBuildDir}/). ` +
          "Run the web build first for a real package."
      );
    }
    return result;
  } catch (e) {
    if (e instanceof core.AndroidBuildError || /not found/i.test(e.message)) {
      fail(`Asset staging failed: ${e.message}`, 1);
    }
    throw e;
  }
}

/**
 * Verify that every LOCAL asset referenced by the staged index.html is present
 * in the packaged APK assets. Fails loudly (exit 1) when a feature asset is
 * missing, so "all features are packaged" is enforced, not assumed.
 *
 * @param {Object} config normalized android config
 * @param {Object} opts parsed CLI options
 */
function auditPackage(config, opts) {
  const assetsDir = path.resolve(REPO_ROOT, config.assetStageDir);
  const indexFile = path.join(assetsDir, config.webBuildEntry);

  if (!fs.existsSync(indexFile)) {
    fail(
      `Packaged-feature audit failed: no staged ${config.webBuildEntry} at ${indexFile}. ` +
        "Run the web build + staging first (or pass --build-web).",
      1
    );
  }

  const html = fs.readFileSync(indexFile, "utf8");
  const exists = (ref) => fs.existsSync(path.join(assetsDir, ref));
  const result = core.auditPackage(html, exists);

  console.log("\n===== PACKAGED FEATURE AUDIT =====");
  console.log(JSON.stringify(result, null, 2));
  if (result.missing.length > 0) {
    console.log("\nMISSING PACKAGED FEATURE ASSET(S):\n  " + result.missing.join("\n  "));
  }
  logVerbose(opts, `audited ${result.total} local reference(s) in ${indexFile}`);
  return result;
}

if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch (e) {
    if (e instanceof UsageError) {
      console.error(`\n${e.message}\n`);
      printHelp();
      process.exit(2);
    }
    fail(`Unexpected error: ${e && e.stack ? e.stack : e}`, 1);
  }
}

module.exports = { parseArgs, loadRawConfig, _main: main };
