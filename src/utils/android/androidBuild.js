/**
 * Android (APK) packaging core logic for Koodo Reader.
 *
 * This module is the pure, side-effect-free "brain" of the Android build.
 * It is deliberately framework-agnostic and dependency-free so it can be:
 *   - consumed by `scripts/build-android.js` (the Node CLI orchestrator), and
 *   - unit-tested by Jest under `react-scripts test`.
 *
 * It contains NO filesystem or process access. Anything that touches disk or
 * spawns a process is injected (e.g. an `fs`-like object), which keeps every
 * function deterministic and easy to test.
 *
 * @module utils/android/androidBuild
 */

"use strict";

/**
 * ABIs understood by this app.
 * @readonly {string[]}
 */
const ANDROID_ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"];

/**
 * Build types understood by this app.
 * @readonly {string[]}
 */
const BUILD_TYPES = ["debug", "release"];

/**
 * Build targets understood by this app (dual-target build, docs §7.1):
 * `webview` packages the web build into `assets/webapp`; `native` packages
 * only native resources.
 * @readonly {string[]}
 */
const ANDROID_TARGETS = ["webview", "native"];

/**
 * Error codes thrown by {@link AndroidBuildError}.
 * @readonly {Object<string,string>}
 */
const ERROR_CODES = {
  CONFIG_INVALID: "CONFIG_INVALID",
  WEB_BUILD_MISSING: "WEB_BUILD_MISSING",
  UNKNOWN_ABI: "UNKNOWN_ABI",
  UNKNOWN_BUILD_TYPE: "UNKNOWN_BUILD_TYPE",
  UNKNOWN_TARGET: "UNKNOWN_TARGET",
  SIGNING_REQUIRED: "SIGNING_REQUIRED",
  TOOLCHAIN_MISSING: "TOOLCHAIN_MISSING",
  PRECONDITION_FAILED: "PRECONDITION_FAILED",
};

/**
 * Base error for the Android build. Carries a stable `code` so callers and
 * tests can branch on failure kind without parsing free-form messages.
 */
class AndroidBuildError extends Error {
  /**
   * @param {string} code one of {@link ERROR_CODES}
   * @param {string} message human-readable, actionable description
   * @param {Object} [extra] optional structured detail
   */
  constructor(code, message, extra) {
    super(message);
    this.name = "AndroidBuildError";
    this.code = code;
    if (extra !== undefined) {
      this.detail = extra;
    }
  }
}

/**
 * Thrown when the Android build configuration is structurally invalid.
 * @extends AndroidBuildError
 */
class AndroidConfigError extends AndroidBuildError {
  /**
   * @param {string} message
   * @param {Object} [extra]
   */
  constructor(message, extra) {
    super(ERROR_CODES.CONFIG_INVALID, message, extra);
    this.name = "AndroidConfigError";
  }
}

/**
 * @typedef {Object} AndroidSigningConfig
 * @property {string} [keystore] path to a keystore (repo-relative or absolute)
 * @property {string} [storePassword] keystore password
 * @property {string} [keyAlias] key alias
 * @property {string} [keyPassword] key password (defaults to storePassword)
 */

/**
 * @typedef {Object} AndroidBuildConfig
 * @property {string} name
 * @property {string} packageName
 * @property {string} versionName
 * @property {number} versionCode
 * @property {number} minSdk
 * @property {number} targetSdk
 * @property {number} compileSdk
 * @property {string[]} abis
 * @property {string[]} buildTypes
 * @property {string[]} targets build targets ("webview" and/or "native")
 * @property {boolean} splitPerAbi
 * @property {string} webBuildDir
 * @property {string} webBuildEntry
 * @property {string} assetStageDir
 * @property {string} [icon]
 * @property {string} outputDir
 * @property {boolean} [requireSigning]
 * @property {AndroidSigningConfig} [signing]
 */

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/**
 * @param {unknown} value
 * @param {string} name
 * @returns {number}
 */
function toPositiveInt(value, name) {
  const n = typeof value === "string" ? parseInt(value, 10) : value;
  if (typeof n !== "number" || !Number.isFinite(n) || n <= 0 || !Number.isInteger(n)) {
    throw new AndroidConfigError(
      `Android config "${name}" must be a positive integer, got ${JSON.stringify(value)}`
    );
  }
  return n;
}

/**
 * A single Gradle invocation in a build plan.
 * @typedef {Object} BuildStep
 * @property {string} target build target ("webview" or "native")
 * @property {string} buildType "debug" or "release"
 * @property {string|null} abi target ABI (null => universal / all ABIs)
 * @property {string} command binary to launch (e.g. "./gradlew")
 * @property {string[]} args gradle args for this step
 * @property {string} artifact expected APK path (repo-relative)
 */

/**
 * Validate a raw config object and return a fully normalized
 * {@link AndroidBuildConfig} with sensible defaults applied.
 *
 * Pure: does not read disk. Throws {@link AndroidConfigError} on invalid input.
 *
 * @param {Partial<AndroidBuildConfig>|null|undefined} raw
 * @returns {AndroidBuildConfig}
 */
function normalizeConfig(raw) {
  const cfg = isPlainObject(raw) ? raw : {};

  if (typeof cfg.packageName !== "string" || !/^[a-z0-9]+(\.[a-z0-9_]+)+$/.test(cfg.packageName)) {
    throw new AndroidConfigError(
      `Android config "packageName" must be a valid Java package (e.g. "com.koodoreader.reader"), got ${JSON.stringify(cfg.packageName)}`
    );
  }

  const name = typeof cfg.name === "string" && cfg.name.trim() ? cfg.name : "Koodo Reader";

  let versionName = cfg.versionName;
  if (typeof versionName !== "string" || !versionName.trim()) {
    versionName = "0.0.0";
  }

  const versionCode =
    typeof cfg.versionCode === "number" && Number.isInteger(cfg.versionCode) && cfg.versionCode >= 0
      ? cfg.versionCode
      : 1;

  const minSdk = toPositiveInt(cfg.minSdk === undefined ? 24 : cfg.minSdk, "minSdk");
  const targetSdk = toPositiveInt(cfg.targetSdk === undefined ? 34 : cfg.targetSdk, "targetSdk");
  const compileSdk = toPositiveInt(
    cfg.compileSdk === undefined ? 34 : cfg.compileSdk,
    "compileSdk"
  );

  if (targetSdk < minSdk) {
    throw new AndroidConfigError(
      `Android config "targetSdk" (${targetSdk}) must be >= "minSdk" (${minSdk})`
    );
  }
  if (compileSdk < Math.max(minSdk, targetSdk)) {
    throw new AndroidConfigError(
      `Android config "compileSdk" (${compileSdk}) must be >= max("minSdk","targetSdk")`
    );
  }

  const abis = resolveAbis(
    cfg.abis === undefined ? ["arm64-v8a", "armeabi-v7a"] : cfg.abis
  );

  const buildTypes = resolveBuildTypes(
    cfg.buildTypes === undefined ? ["release"] : cfg.buildTypes
  );

  // Dual-target build (docs/android-native-migration.md §7): default to the
  // existing webview shape so current configs/CI keep working unchanged.
  const targets = resolveTargets(cfg.targets === undefined ? ["webview"] : cfg.targets);

  const splitPerAbi =
    typeof cfg.splitPerAbi === "boolean" ? cfg.splitPerAbi : abis.length > 1;

  const signing = isPlainObject(cfg.signing)
    ? {
        keystore: typeof cfg.signing.keystore === "string" ? cfg.signing.keystore : "",
        storePassword:
          typeof cfg.signing.storePassword === "string" ? cfg.signing.storePassword : "",
        keyAlias: typeof cfg.signing.keyAlias === "string" ? cfg.signing.keyAlias : "",
        keyPassword:
          typeof cfg.signing.keyPassword === "string"
            ? cfg.signing.keyPassword
            : cfg.signing.storePassword || "",
      }
    : { keystore: "", storePassword: "", keyAlias: "", keyPassword: "" };

  return {
    name,
    packageName: cfg.packageName,
    versionName,
    versionCode,
    minSdk,
    targetSdk,
    compileSdk,
    abis,
    buildTypes,
    targets,
    splitPerAbi,
    webBuildDir:
      typeof cfg.webBuildDir === "string" && cfg.webBuildDir.trim()
        ? cfg.webBuildDir.replace(/\\/g, "/")
        : "build",
    webBuildEntry:
      typeof cfg.webBuildEntry === "string" && cfg.webBuildEntry.trim()
        ? cfg.webBuildEntry.replace(/\\/g, "/")
        : "index.html",
    assetStageDir:
      typeof cfg.assetStageDir === "string" && cfg.assetStageDir.trim()
        ? cfg.assetStageDir.replace(/\\/g, "/")
        : "android/app/src/main/assets/webapp",
    icon: typeof cfg.icon === "string" ? cfg.icon.replace(/\\/g, "/") : "",
    outputDir:
      typeof cfg.outputDir === "string" && cfg.outputDir.trim()
        ? cfg.outputDir.replace(/\\/g, "/")
        : "android/app/build/outputs/apk",
    requireSigning: typeof cfg.requireSigning === "boolean" ? cfg.requireSigning : false,
    signing,
  };
}

/**
 * Normalize a list (or single value) of ABIs: de-duplicate preserving order,
 * and validate each against {@link ANDROID_ABIS}.
 *
 * @param {string|string[]} input
 * @param {string[]} [allowed] allowed ABI list (defaults to {@link ANDROID_ABIS})
 * @returns {string[]}
 */
function resolveAbis(input, allowed = ANDROID_ABIS) {
  const list = Array.isArray(input) ? input : [input];
  const out = [];
  for (const item of list) {
    if (typeof item !== "string" || !allowed.includes(item)) {
      throw new AndroidBuildError(
        ERROR_CODES.UNKNOWN_ABI,
        `Unknown Android ABI "${String(item)}". Allowed: ${allowed.join(", ")}`,
        { abi: String(item), allowed }
      );
    }
    if (!out.includes(item)) {
      out.push(item);
    }
  }
  if (out.length === 0) {
    throw new AndroidBuildError(ERROR_CODES.UNKNOWN_ABI, "At least one Android ABI must be specified", {
      allowed,
    });
  }
  return out;
}

/**
 * Validate build types against {@link BUILD_TYPES}.
 * @param {string|string[]} input
 * @returns {string[]}
 */
function resolveBuildTypes(input) {
  const list = Array.isArray(input) ? input : [input];
  const out = [];
  for (const item of list) {
    if (typeof item !== "string" || !BUILD_TYPES.includes(item)) {
      throw new AndroidBuildError(
        ERROR_CODES.UNKNOWN_BUILD_TYPE,
        `Unknown Android build type "${String(item)}". Allowed: ${BUILD_TYPES.join(", ")}`,
        { buildType: String(item) }
      );
    }
    if (!out.includes(item)) {
      out.push(item);
    }
  }
  return out;
}

/**
 * Validate build targets against {@link ANDROID_TARGETS}: de-duplicate
 * preserving order and reject anything unknown (dual-target build, docs §7.1).
 *
 * @param {string|string[]} input
 * @param {string[]} [allowed] allowed target list (defaults to {@link ANDROID_TARGETS})
 * @returns {string[]}
 */
function resolveTargets(input, allowed = ANDROID_TARGETS) {
  const list = Array.isArray(input) ? input : [input];
  const out = [];
  for (const item of list) {
    if (typeof item !== "string" || !allowed.includes(item)) {
      throw new AndroidBuildError(
        ERROR_CODES.UNKNOWN_TARGET,
        `Unknown Android build target "${String(item)}". Allowed: ${allowed.join(", ")}`,
        { target: String(item), allowed }
      );
    }
    if (!out.includes(item)) {
      out.push(item);
    }
  }
  if (out.length === 0) {
    throw new AndroidBuildError(
      ERROR_CODES.UNKNOWN_TARGET,
      "At least one Android build target must be specified",
      { allowed }
    );
  }
  return out;
}

/**
 * Decide which Gradle invocation to use given the presence of a wrapper.
 *
 * @param {{platform?: "win32"|"linux"|"darwin", hasGradlew?: boolean}} ctx
 * @returns {string}
 */
function resolveGradleBinary(ctx) {
  const { platform = "linux", hasGradlew = true } = ctx || {};
  if (hasGradlew) {
    return platform === "win32" ? "gradlew.bat" : "./gradlew";
  }
  return "gradle";
}

/**
 * @param {string} s
 * @returns {string}
 */
function capitalize(s) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

/**
 * Compute the `-P` signing args for one build type. Debug builds sign with the
 * auto-generated debug keystore (no args). A release build with no keystore
 * throws when {@link AndroidBuildConfig.requireSigning} is set.
 *
 * @param {AndroidBuildConfig} config
 * @param {string} buildType
 * @returns {string[]}
 */
function collectSigningArgs(config, buildType) {
  if (buildType !== "release") {
    return [];
  }
  const signing = config.signing || { keystore: "", storePassword: "", keyAlias: "" };
  if (!signing.keystore) {
    if (config.requireSigning) {
      throw new AndroidBuildError(
        ERROR_CODES.SIGNING_REQUIRED,
        "A release APK requires a signing keystore, but none is configured. " +
          "Set signing.keystore (and its passwords) in android.config.json or via " +
          "ANDROID_KEYSTORE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS env vars, " +
          "or build a debug APK instead.",
        { requireSigning: true }
      );
    }
    return [];
  }
  const args = [
    `-Pkeystore=${signing.keystore}`,
    `-PstorePassword=${signing.storePassword || ""}`,
    `-PkeyAlias=${signing.keyAlias || ""}`,
  ];
  if (signing.keyPassword) {
    args.push(`-PkeyPassword=${signing.keyPassword}`);
  }
  return args;
}

/**
 * Repo-relative path for a single APK artifact.
 *
 * @param {AndroidBuildConfig} config
 * @param {string} buildType
 * @param {string|null} abi target ABI, or null for a universal APK
 * @param {string} [target] build target, defaults to "webview"
 * @returns {string}
 */
function getArtifactPath(config, buildType, abi, target = "webview") {
  if (!ANDROID_TARGETS.includes(target)) {
    throw new AndroidBuildError(
      ERROR_CODES.UNKNOWN_TARGET,
      `Unknown Android build target "${String(target)}". Allowed: ${ANDROID_TARGETS.join(", ")}`,
      { target: String(target), allowed: ANDROID_TARGETS }
    );
  }
  const base = (config.outputDir || "android/app/build/outputs/apk")
    .replace(/\\/g, "/")
    .replace(/\/+$/, "")
    .toLowerCase();
  const suffix = target === "native" ? "-native" : "";
  if (abi) {
    return `${base}/${buildType}/${abi}/app-${buildType}-${abi}${suffix}.apk`.toLowerCase();
  }
  return `${base}/${buildType}/app-${buildType}${suffix}.apk`.toLowerCase();
}

/**
 * Build the ordered list of Gradle steps needed to produce all requested APKs.
 *
 * Semantics:
 *   - The plan expands as `target x buildType x abi`; every step carries
 *     `-Ptarget=<target>` (same style as the existing `-PabiFilters`).
 *   - `splitPerAbi` true (and at least one ABI)  -> one step per ABI, each
 *     constrained with `-PabiFilters=<abi>`, yielding `app-<type>-<abi>.apk`.
 *   - `splitPerAbi` false                        -> one universal step (no
 *     abiFilters), yielding `app-<type>.apk`.
 *   - Native-target artifacts get the `-native` filename suffix (docs §7.1).
 *
 * Pure: returns data, spawns nothing.
 *
 * @param {AndroidBuildConfig} config
 * @param {{gradleBinary?: string, platform?: ("win32"|"linux"|"darwin"), buildTypes?: string[], targets?: string[]}} [options]
 * @returns {BuildStep[]}
 */
function buildBuildPlan(config, options = {}) {
  const binary =
    options.gradleBinary ||
    resolveGradleBinary(
      options.platform
        ? { platform: options.platform }
        : { platform: "linux" }
    );

  const buildTypes = options.buildTypes || config.buildTypes;
  const targets = options.targets || config.targets || ["webview"];
  const steps = [];

  for (const target of targets) {
    if (!ANDROID_TARGETS.includes(target)) {
      throw new AndroidBuildError(
        ERROR_CODES.UNKNOWN_TARGET,
        `Unknown build target "${target}". Allowed: ${ANDROID_TARGETS.join(", ")}`,
        { target }
      );
    }
    for (const buildType of buildTypes) {
      if (!BUILD_TYPES.includes(buildType)) {
        throw new AndroidBuildError(
          ERROR_CODES.UNKNOWN_BUILD_TYPE,
          `Unknown build type "${buildType}"`,
          { buildType }
        );
      }
      const task = `:app:assemble${capitalize(buildType)}`;
      const signingArgs = collectSigningArgs(config, buildType);
      const versionArgs = [
        `-PversionCode=${config.versionCode}`,
        `-PversionName=${config.versionName}`,
      ];
      const targetArgs = [`-Ptarget=${target}`];

      if (config.splitPerAbi && config.abis.length > 0) {
        for (const abi of config.abis) {
          steps.push({
            target,
            buildType,
            abi,
            command: binary,
            args: [task, ...targetArgs, `-PabiFilters=${abi}`, ...versionArgs, ...signingArgs],
            artifact: getArtifactPath(config, buildType, abi, target),
          });
        }
      } else {
        steps.push({
          target,
          buildType,
          abi: null,
          command: binary,
          args: [task, ...targetArgs, ...versionArgs, ...signingArgs],
          artifact: getArtifactPath(config, buildType, null, target),
        });
      }
    }
  }

  return steps;
}

/**
 * @typedef {Object} StageAssetOperation
 * @property {"copy"|"write"} type
 * @property {string} [from] source path (copy only)
 * @property {string} destination destination path (repo-relative)
 */

/**
 * Build a plan of staging operations that record the staged source. The actual
 * copy is performed by the CLI; this returns the metadata we persist.
 *
 * Only targets that package the web build need staging metadata; the `native`
 * target ships Kotlin resources only, so it yields an empty plan (docs §7.1).
 *
 * @param {AndroidBuildConfig} config
 * @param {{targets?: string[]}} [options] override targets (defaults to config.targets)
 * @returns {StageAssetOperation[]}
 */
function stageAssetsPlan(config, options = {}) {
  const targets = options.targets || config.targets || ["webview"];
  if (!targets.includes("webview")) {
    return [];
  }
  const webRoot = (config.webBuildDir || "build").replace(/\\/g, "/").replace(/\/+$/, "");
  const destRoot = (config.assetStageDir || "android/app/src/main/assets/webapp")
    .replace(/\\/g, "/")
    .replace(/\/+$/, "");
  const entry = (config.webBuildEntry || "index.html").replace(/\\/g, "/");
  return [
    {
      type: "write",
      destination: `${destRoot}/.staged-from`,
      content:
        `webBuildDir=${webRoot}\nwebBuildEntry=${entry}\n` +
        `packageName=${config.packageName}\nversionName=${config.versionName}\n` +
        `versionCode=${config.versionCode}\n`,
    },
  ];
}

/**
 * Validate that the web build is present and ready to be staged.
 *
 * The `native` target does not package the web build, so it has no web
 * precondition (docs §7.1); the check applies only when `webview` is among
 * the requested targets.
 *
 * @param {AndroidBuildConfig} config
 * @param {{existsSync:(p:string)=>boolean}} [fs] injected fs (kept pure/testable)
 * @param {{targets?: string[]}} [options] override targets (defaults to config.targets)
 * @returns {{ok:boolean, missing:string[]}}
 */
function validatePreconditions(config, fs, options = {}) {
  const targets = options.targets || config.targets || ["webview"];
  if (!targets.includes("webview")) {
    return { ok: true, missing: [] };
  }
  const webRoot = (config.webBuildDir || "build").replace(/\\/g, "/").replace(/\/+$/, "");
  const entry = (config.webBuildEntry || "index.html").replace(/\\/g, "/");
  const entryPath = webRoot === "" ? entry : `${webRoot}/${entry}`;

  const checker =
    fs && typeof fs.existsSync === "function"
      ? fs
      : {
          existsSync: (p) => {
            // Only evaluated when needed; guarded so this module stays
            // importable outside Node (e.g. a browser bundle context).
            return existsSyncSafe(p);
          },
        };

  const missing = [];
  if (!checker.existsSync(entryPath)) {
    missing.push(entryPath);
  }
  return { ok: missing.length === 0, missing };
}

/**
 * @typedef {Object} PackageAuditResult
 * @property {number} total number of local references found
 * @property {number} present references resolvable in the package
 * @property {string[]} missing references that are absent
 * @property {boolean} ok true when nothing is missing
 */

/**
 * Audit that every LOCAL asset referenced by the app's index.html is present
 * in the packaged assets. Pure: the caller injects an `exists(ref)` predicate,
 * so this stays testable in Jest and usable from the Node CLI.
 *
 * External URLs (http/https, data:, mailto:, tel:) are excluded — they are
 * network resources, not packaged assets.
 *
 * @param {string} html index.html content
 * @param {(ref:string)=>boolean} exists predicate over repo-relative-ish refs
 * @returns {PackageAuditResult}
 */
function auditPackage(html, exists) {
  if (typeof html !== "string") {
    throw new AndroidBuildError(ERROR_CODES.CONFIG_INVALID, "auditPackage expects the index.html content as a string", {});
  }
  if (typeof exists !== "function") {
    throw new AndroidBuildError(ERROR_CODES.CONFIG_INVALID, "auditPackage expects an exists(ref) predicate", {});
  }

  const re = /(?:src|href|data-src|data-href)=["']([^"'#?]+)/g;
  const refs = new Set();
  let m;
  while ((m = re.exec(html)) !== null) {
    const raw = m[1];
    if (/^(https?:)?\/\//i.test(raw) || /^(data:|mailto:|tel:)/i.test(raw)) continue;
    const p = raw.replace(/^\.\//, "").replace(/^\//, "");
    if (p) refs.add(p);
  }

  const missing = [];
  let present = 0;
  const sorted = [...refs].sort();
  for (const ref of sorted) {
    if (exists(ref)) {
      present += 1;
    } else {
      missing.push(ref);
    }
  }

  return { total: refs.size, present, missing, ok: missing.length === 0 };
}

/**
 * Side-effecting default filesystem check, used only when the caller did not
 * inject an `fs`. Guarded so this module never requires Node APIs at the top
 * level (keeps it importable in a browser bundle context).
 *
 * @param {string} p
 * @returns {boolean}
 */
function existsSyncSafe(p) {
  try {
    // eslint-disable-next-line global-require
    const fs = require("fs");
    return fs.existsSync(p);
  } catch (e) {
    return false;
  }
}

/**
 * Produce a human-facing summary object for a completed (or planned) build run.
 * Pure.
 *
 * @param {Object} input
 * @param {AndroidBuildConfig} input.config
 * @param {string} input.buildType
 * @param {string[]} input.abis
 * @param {string[]} [input.targets] build targets (defaults to config.targets)
 * @param {string[]} [input.artifacts]
 * @param {string[]} [input.commands] binaries launched
 * @param {boolean} [input.dryRun]
 * @param {number} [input.staged] staged asset count
 * @param {string} [input.error]
 * @returns {Object}
 */
function summarizeResult(input) {
  const {
    config,
    buildType,
    abis,
    targets = config.targets || ["webview"],
    artifacts = [],
    commands = [],
    dryRun = false,
    staged = 0,
    error,
  } = input;

  return {
    ok: !error,
    error,
    dryRun,
    app: {
      name: config.name,
      packageName: config.packageName,
      versionName: config.versionName,
      versionCode: config.versionCode,
      minSdk: config.minSdk,
      targetSdk: config.targetSdk,
    },
    build: {
      targets,
      buildType,
      abis,
      artifacts,
      commands,
      stagedAssets: staged,
    },
  };
}

module.exports = {
  ANDROID_ABIS,
  BUILD_TYPES,
  ANDROID_TARGETS,
  ERROR_CODES,
  AndroidBuildError,
  AndroidConfigError,
  normalizeConfig,
  resolveAbis,
  resolveBuildTypes,
  resolveTargets,
  resolveGradleBinary,
  collectSigningArgs,
  getArtifactPath,
  buildBuildPlan,
  stageAssetsPlan,
  auditPackage,
  validatePreconditions,
  summarizeResult,
};
