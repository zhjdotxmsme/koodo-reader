/**
 * TypeScript declarations for the Android (APK) packaging core
 * (`./androidBuild.js`). The runtime is framework-agnostic CommonJS; this file
 * provides typed interfaces for consumers and editor tooling.
 */

export interface AndroidSigningConfig {
  keystore?: string;
  storePassword?: string;
  keyAlias?: string;
  keyPassword?: string;
}

export interface AndroidBuildConfig {
  name: string;
  packageName: string;
  versionName: string;
  versionCode: number;
  minSdk: number;
  targetSdk: number;
  compileSdk: number;
  abis: string[];
  buildTypes: string[];
  splitPerAbi: boolean;
  webBuildDir: string;
  webBuildEntry: string;
  assetStageDir: string;
  icon?: string;
  outputDir: string;
  requireSigning: boolean;
  signing: AndroidSigningConfig;
}

export type AndroidBuildConfigInput = Partial<AndroidBuildConfig>;

export interface FsLike {
  existsSync: (path: string) => boolean;
}

export interface PreconditionsResult {
  ok: boolean;
  missing: string[];
}

export interface StageAssetOperation {
  type: "copy" | "write";
  from?: string;
  destination: string;
  content?: string;
}

/** Result of auditing packaged assets against index.html references. */
export interface PackageAuditResult {
  total: number;
  present: number;
  missing: string[];
  ok: boolean;
}

/** A single Gradle invocation in a build plan. */
export interface BuildStep {
  buildType: string;
  abi: string | null;
  command: string;
  args: string[];
  artifact: string;
}

export interface BuildResultSummary {
  ok: boolean;
  error?: string;
  dryRun?: boolean;
  app: {
    name: string;
    packageName: string;
    versionName: string;
    versionCode: number;
    minSdk: number;
    targetSdk: number;
  };
  build: {
    buildType: string;
    abis: string[];
    artifacts: string[];
    commands: string[];
    stagedAssets: number;
  };
}

export interface AndroidBuildError extends Error {
  code: string;
  detail?: unknown;
}

export interface AndroidBuildModule {
  ANDROID_ABIS: string[];
  BUILD_TYPES: string[];
  ERROR_CODES: Record<string, string>;
  AndroidBuildError: new (code: string, message: string, detail?: unknown) => AndroidBuildError;
  AndroidConfigError: new (message: string, detail?: unknown) => AndroidBuildError;
  normalizeConfig(raw?: AndroidBuildConfigInput | null): AndroidBuildConfig;
  resolveAbis(input: string | string[], allowed?: string[]): string[];
  resolveBuildTypes(input: string | string[]): string[];
  resolveGradleBinary(ctx?: { platform?: string; hasGradlew?: boolean }): string;
  collectSigningArgs(config: AndroidBuildConfig, buildType: string): string[];
  getArtifactPath(config: AndroidBuildConfig, buildType: string, abi: string | null): string;
  buildBuildPlan(
    config: AndroidBuildConfig,
    options?: {
      gradleBinary?: string;
      platform?: string;
      buildTypes?: string[];
    }
  ): BuildStep[];
  stageAssetsPlan(config: AndroidBuildConfig): StageAssetOperation[];
  auditPackage(html: string, exists: (ref: string) => boolean): PackageAuditResult;
  validatePreconditions(config: AndroidBuildConfig, fs?: FsLike): PreconditionsResult;
  summarizeResult(input: {
    config: AndroidBuildConfig;
    buildType: string;
    abis: string[];
    artifacts?: string[];
    commands?: string[];
    dryRun?: boolean;
    staged?: number;
    error?: string;
  }): BuildResultSummary;
}

declare const androidBuild: AndroidBuildModule;
export default androidBuild;
