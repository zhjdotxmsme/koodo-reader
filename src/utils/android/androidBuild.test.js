/**
 * Unit tests for the Android (APK) packaging core (`./androidBuild.js`).
 *
 * These run under `react-scripts test` (Jest). The core is pure and
 * side-effect-free, so we can assert exact behaviour including error codes
 * and the shape of the generated Gradle build plan / artifact paths.
 */

const {
  ANDROID_ABIS,
  BUILD_TYPES,
  ERROR_CODES,
  AndroidBuildError,
  AndroidConfigError,
  normalizeConfig,
  resolveAbis,
  resolveBuildTypes,
  resolveGradleBinary,
  collectSigningArgs,
  getArtifactPath,
  buildBuildPlan,
  stageAssetsPlan,
  auditPackage,
  validatePreconditions,
  summarizeResult,
} = require("./androidBuild");

/** Minimal valid raw config used across tests. */
function makeRaw(extra) {
  return Object.assign(
    {
      name: "Koodo Reader",
      packageName: "com.koodoreader.reader",
      versionName: "2.4.4",
      versionCode: 42,
      minSdk: 24,
      targetSdk: 34,
      compileSdk: 34,
      abis: ["arm64-v8a", "armeabi-v7a"],
      buildTypes: ["release"],
    },
    extra || {}
  );
}

describe("constants", () => {
  it("exposes known ABIs and build types", () => {
    expect(ANDROID_ABIS).toEqual(["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]);
    expect(BUILD_TYPES).toEqual(["debug", "release"]);
  });

  it("exposes stable error codes", () => {
    expect(ERROR_CODES.CONFIG_INVALID).toBe("CONFIG_INVALID");
    expect(ERROR_CODES.SIGNING_REQUIRED).toBe("SIGNING_REQUIRED");
    expect(ERROR_CODES.UNKNOWN_ABI).toBe("UNKNOWN_ABI");
  });
});

describe("normalizeConfig", () => {
  it("applies the given values and returns a full config", () => {
    const cfg = normalizeConfig(makeRaw());
    expect(cfg.packageName).toBe("com.koodoreader.reader");
    expect(cfg.versionName).toBe("2.4.4");
    expect(cfg.versionCode).toBe(42);
    expect(cfg.minSdk).toBe(24);
    expect(cfg.targetSdk).toBe(34);
    expect(cfg.compileSdk).toBe(34);
    expect(cfg.abis).toEqual(["arm64-v8a", "armeabi-v7a"]);
    expect(cfg.buildTypes).toEqual(["release"]);
    expect(cfg.webBuildEntry).toBe("index.html");
    expect(cfg.assetStageDir).toBe("android/app/src/main/assets/webapp");
  });

  it("fills defaults for omitted optional fields", () => {
    const cfg = normalizeConfig({ packageName: "com.koodoreader.reader" });
    expect(cfg.name).toBe("Koodo Reader");
    expect(cfg.versionName).toBe("0.0.0");
    expect(cfg.versionCode).toBe(1);
    expect(cfg.minSdk).toBe(24);
    expect(cfg.targetSdk).toBe(34);
    expect(cfg.compileSdk).toBe(34);
    expect(cfg.abis).toEqual(["arm64-v8a", "armeabi-v7a"]);
    expect(cfg.buildTypes).toEqual(["release"]);
    expect(cfg.requireSigning).toBe(false);
    expect(cfg.signing.keystore).toBe("");
  });

  it("defaults splitPerAbi to true when more than one ABI is present", () => {
    expect(normalizeConfig(makeRaw()).splitPerAbi).toBe(true);
  });

  it("throws a typed error for an invalid packageName", () => {
    try {
      normalizeConfig({ packageName: "not-valid" });
      expect.unreachable("should have thrown");
    } catch (e) {
      expect(e).toBeInstanceOf(AndroidConfigError);
      expect(e).toBeInstanceOf(AndroidBuildError);
      expect(e.code).toBe(ERROR_CODES.CONFIG_INVALID);
    }
  });

  it("throws when targetSdk < minSdk", () => {
    expect(() => normalizeConfig(makeRaw({ targetSdk: 20, minSdk: 24 }))).toThrow(/targetSdk/);
  });

  it("throws when compileSdk < max(minSdk,targetSdk)", () => {
    expect(() =>
      normalizeConfig(makeRaw({ compileSdk: 20, targetSdk: 34 }))
    ).toThrow(/compileSdk/);
  });

  it("normalizes signing material and defaults keyPassword to storePassword", () => {
    const cfg = normalizeConfig(
      makeRaw({
        signing: { keystore: "ks.jks", storePassword: "secret", keyAlias: "koodo" },
      })
    );
    expect(cfg.signing.keyPassword).toBe("secret");
  });
});

describe("resolveAbis", () => {
  it("deduplicates while preserving order", () => {
    expect(resolveAbis(["armeabi-v7a", "arm64-v8a", "armeabi-v7a"])).toEqual([
      "armeabi-v7a",
      "arm64-v8a",
    ]);
  });

  it("accepts a single ABI string", () => {
    expect(resolveAbis("arm64-v8a")).toEqual(["arm64-v8a"]);
  });

  it("rejects an unknown ABI with a typed error", () => {
    try {
      resolveAbis(["nope"]);
      expect.unreachable("should have thrown");
    } catch (e) {
      expect(e).toBeInstanceOf(AndroidBuildError);
      expect(e.code).toBe(ERROR_CODES.UNKNOWN_ABI);
      expect(e.detail.allowed).toEqual(ANDROID_ABIS);
    }
  });
});

describe("resolveBuildTypes", () => {
  it("accepts debug/release", () => {
    expect(resolveBuildTypes(["release", "debug"])).toEqual(["release", "debug"]);
  });

  it("rejects unknown build types", () => {
    try {
      resolveBuildTypes(["prod"]);
      expect.unreachable("should have thrown");
    } catch (e) {
      expect(e.code).toBe(ERROR_CODES.UNKNOWN_BUILD_TYPE);
    }
  });
});

describe("resolveGradleBinary", () => {
  it("prefers the wrapper on POSIX", () => {
    expect(resolveGradleBinary({ platform: "linux" })).toBe("./gradlew");
    expect(resolveGradleBinary({ platform: "darwin" })).toBe("./gradlew");
  });

  it("uses the .bat wrapper on windows", () => {
    expect(resolveGradleBinary({ platform: "win32" })).toBe("gradlew.bat");
  });

  it("falls back to system gradle when no wrapper", () => {
    expect(resolveGradleBinary({ platform: "linux", hasGradlew: false })).toBe("gradle");
  });
});

describe("buildBuildPlan", () => {
  const base = normalizeConfig(makeRaw());

  it("produces one step per ABI when splitting", () => {
    const plan = buildBuildPlan(base);
    expect(plan).toHaveLength(2);
    const abis = plan.map((s) => s.abi);
    expect(abis).toEqual(["arm64-v8a", "armeabi-v7a"]);
    plan.forEach((s) => {
      expect(s.buildType).toBe("release");
      expect(s.command).toBe("./gradlew");
      expect(s.args).toContain(":app:assembleRelease");
      expect(s.args).toContain(`-PabiFilters=${s.abi}`);
      expect(s.artifact).toMatch(new RegExp(`-${s.abi}\\.apk$`));
      expect(s.artifact).toContain("release");
    });
  });

  it("produces a single universal step when not splitting", () => {
    const cfg = normalizeConfig(makeRaw({ splitPerAbi: false }));
    const plan = buildBuildPlan(cfg);
    expect(plan).toHaveLength(1);
    expect(plan[0].abi).toBeNull();
    expect(plan[0].args).not.toContain(expect.stringContaining("abiFilters"));
    expect(plan[0].artifact).toMatch(/app-release\.apk$/);
  });

  it("includes signing args for a signed release build", () => {
    const cfg = normalizeConfig(
      makeRaw({
        signing: {
          keystore: "keystore/release.jks",
          storePassword: "secret",
          keyAlias: "koodo",
          keyPassword: "secret",
        },
      })
    );
    const plan = buildBuildPlan(cfg);
    plan.forEach((s) => {
      expect(s.args).toContain("-Pkeystore=keystore/release.jks");
      expect(s.args).toContain("-PstorePassword=secret");
      expect(s.args).toContain("-PkeyAlias=koodo");
      expect(s.args).toContain("-PkeyPassword=secret");
    });
  });

  it("adds no signing args for a debug build", () => {
    const cfg = normalizeConfig(
      makeRaw({
        buildTypes: ["debug"],
        signing: { keystore: "ks.jks", storePassword: "s", keyAlias: "a" },
      })
    );
    const plan = buildBuildPlan(cfg);
    plan.forEach((s) => {
      expect(s.buildType).toBe("debug");
      expect(s.args).toContain(":app:assembleDebug");
      expect(s.args).not.toContain(expect.stringContaining("keystore"));
    });
  });

  it("uses the windows wrapper for a win32 platform", () => {
    const plan = buildBuildPlan(base, { platform: "win32" });
    plan.forEach((s) => expect(s.command).toBe("gradlew.bat"));
  });

  it("builds steps for every requested build type", () => {
    const cfg = normalizeConfig(makeRaw({ buildTypes: ["debug", "release"], splitPerAbi: false }));
    const plan = buildBuildPlan(cfg);
    const types = plan.map((s) => s.buildType);
    expect(types).toEqual(["debug", "release"]);
  });
});

describe("getArtifactPath", () => {
  const cfg = normalizeConfig(makeRaw());

  it("returns a per-ABI path when an abi is given", () => {
    const p = getArtifactPath(cfg, "release", "arm64-v8a");
    expect(p).toMatch(/android\/app\/build\/outputs\/apk\/release\/arm64-v8a\/app-release-arm64-v8a\.apk/);
  });

  it("returns a universal path when abi is null", () => {
    const p = getArtifactPath(cfg, "release", null);
    expect(p).toMatch(/android\/app\/build\/outputs\/apk\/release\/app-release\.apk/);
  });
});

describe("collectSigningArgs", () => {
  it("returns nothing for a release build without a keystore unless required", () => {
    const cfg = normalizeConfig(makeRaw());
    expect(collectSigningArgs(cfg, "release")).toEqual([]);
  });

  it("throws SIGNING_REQUIRED when a release build needs signing but has none", () => {
    const cfg = normalizeConfig(makeRaw({ requireSigning: true }));
    try {
      collectSigningArgs(cfg, "release");
      expect.unreachable("should have thrown");
    } catch (e) {
      expect(e).toBeInstanceOf(AndroidBuildError);
      expect(e.code).toBe(ERROR_CODES.SIGNING_REQUIRED);
    }
  });
});

describe("stageAssetsPlan", () => {
  it("returns a write op that records the staged source", () => {
    const cfg = normalizeConfig(makeRaw());
    const plan = stageAssetsPlan(cfg);
    expect(plan.length).toBeGreaterThanOrEqual(1);
    expect(plan[0].type).toBe("write");
    expect(plan[0].destination).toContain("assets/webapp");
    expect(plan[0].content).toContain("com.koodoreader.reader");
  });
});

describe("auditPackage", () => {
  const html = [
    '<!doctype html><html><head>',
    '<link rel="icon" href="./favicon.png">',
    '<link rel="dns-prefetch" href="https://web.koodoreader.com"/>',
    '<script src="/static/js/main.js"></script>',
    '<link href="data:text/css;base64,aGVsbG8=" rel="stylesheet"/>',
    '<meta content="mailto:support@example.com"/>',
    '<script type="module" src="./lib/kookit-extra.min.mjs"></script>',
    '</head><body></body></html>',
  ].join("");

  const present = (ref) =>
    ["favicon.png", "static/js/main.js", "lib/kookit-extra.min.mjs"].includes(ref);

  it("counts only local references and skips externals/data/mailto", () => {
    const r = auditPackage(html, present);
    expect(r.total).toBe(3);
    expect(r.present).toBe(3);
    expect(r.ok).toBe(true);
    expect(r.missing).toEqual([]);
  });

  it("normalizes ./ and / reference prefixes", () => {
    const r = auditPackage(html, present);
    expect(r.missing).toEqual([]); // both ./static/js/main.js style and /static/js/main.js resolve
  });

  it("flags missing feature assets", () => {
    const strict = (ref) => ref === "favicon.png"; // engine + bundle missing
    const r = auditPackage(html, strict);
    expect(r.ok).toBe(false);
    expect(r.missing).toContain("lib/kookit-extra.min.mjs");
    expect(r.missing).toContain("static/js/main.js");
    expect(r.present).toBe(1);
  });

  it("reports everything missing when the package is empty", () => {
    const empty = () => false;
    const r = auditPackage(html, empty);
    expect(r.ok).toBe(false);
    expect(r.present).toBe(0);
    expect(r.missing).toHaveLength(3);
  });

  it("returns empty result when there are no local references", () => {
    const r = auditPackage('<html><head><link href="https://x.example/"/></head></html>', () => true);
    expect(r.total).toBe(0);
    expect(r.ok).toBe(true);
    expect(r.missing).toEqual([]);
  });

  it("throws typed errors on bad inputs", () => {
    const notString = () => {
      try {
        auditPackage(123, () => true);
        expect.unreachable();
      } catch (e) {
        expect(e).toBeInstanceOf(AndroidBuildError);
        expect(e.code).toBe(ERROR_CODES.CONFIG_INVALID);
      }
    };
    const notFunc = () => {
      try {
        auditPackage("<html></html>", "yes");
        expect.unreachable();
      } catch (e) {
        expect(e).toBeInstanceOf(AndroidBuildError);
        expect(e.code).toBe(ERROR_CODES.CONFIG_INVALID);
      }
    };
    notString();
    notFunc();
  });
});

describe("validatePreconditions", () => {
  const cfg = normalizeConfig(makeRaw());

  it("reports ok when the entry exists (injected fs)", () => {
    const result = validatePreconditions(cfg, { existsSync: () => true });
    expect(result.ok).toBe(true);
    expect(result.missing).toEqual([]);
  });

  it("reports missing when the entry is absent (injected fs)", () => {
    const result = validatePreconditions(cfg, {
      existsSync: (p) => !p.includes("index.html"),
    });
    expect(result.ok).toBe(false);
    expect(result.missing.length).toBe(1);
    expect(result.missing[0]).toContain("index.html");
  });
});

describe("summarizeResult", () => {
  it("records success with app + build details", () => {
    const cfg = normalizeConfig(makeRaw());
    const summary = summarizeResult({
      config: cfg,
      buildType: "release",
      abis: ["arm64-v8a"],
      artifacts: ["android/app/build/outputs/apk/release/arm64-v8a/app-release-arm64-v8a.apk"],
      commands: ["./gradlew"],
    });
    expect(summary.ok).toBe(true);
    expect(summary.app.packageName).toBe("com.koodoreader.reader");
    expect(summary.build.buildType).toBe("release");
    expect(summary.build.artifacts).toHaveLength(1);
    expect(summary.build.commands).toEqual(["./gradlew"]);
  });

  it("records a failure when an error is provided", () => {
    const cfg = normalizeConfig(makeRaw());
    const summary = summarizeResult({ config: cfg, buildType: "release", abis: [], error: "boom" });
    expect(summary.ok).toBe(false);
    expect(summary.error).toBe("boom");
  });
});
