# ADR-008: 16KB 页对齐守卫与 AGP 升级决策（P6-OCR-16K）

- **Status**: accepted (2026-09-25)
- **Card**: P6-OCR-16K (t-mufbb7d5-tw3o1i)，源自 P6-OCR 卡（t-muexn6c0-3dab3i）的 16KB 风险项

## Context

Android 15 起要求 `.so` 的 LOAD 段按 16KB（0x4000）对齐；Google Play 自
2025-11 强制。本项目原判"APK 零 .so（pdf.js 路径 + ML Kit 按需下载）"
在 P6-TTS 合入后**不再成立**：首个 `.so` 已随 `androidx.datastore` 传递
依赖进包。缺一个不依赖 NDK/llvm-readelf 的机器守卫时，该风险无法在
CI 拦截。

## Decision

1. **守卫先行（已落地）**：`scripts/check-elf-16kb.js` —— 纯 Node
   （自解析 ZIP + ELF program header，无 unzip/readelf/NDK 依赖；
   f1c5f664 由 shell-out 版重写），支持 `<apk>` / `--dir` / `--quiet` /
   `--json`；已接入 `.github/workflows/release-android.yml`（assemble 后
   对产物执行，见 §189 行）。任一 PT_LOAD 段非 16KB 正倍数对齐 → exit 1。
2. **AGP 8.2.2 → 8.5.1 升级：有条件推迟**。理由：
   - AGP 8.5.1 要求 Gradle ≥ 8.7，而本项目/CI 把 Gradle 钉在 8.5
     （`release-android.yml` 显式 grep "Gradle 8.5"，本地缓存亦为 8.5）；
     升级是 Gradle + AGP + CI 三处联动，影响当前全绿的构建链；
   - 升级的**目的**（16KB 对齐产物可被 CI 拦截）已由守卫达成——守卫
     校验的是"产物是否合规"，与构建工具版本无关；AGP 8.5.1 的默认
     16KB 对齐只影响**未来打包进来的** .so 的"出厂对齐"；
   - 触发条件（满足其一即执行升级）：
     a) 首次要**bundled** 一个非本项目编译的 .so（如 ML Kit bundled 轨
        camera-core ≥1.4.2 切换）；
     b) check-elf-16kb 在 CI 连续两次报出真实对齐失败且无法用上游
        新版本修复；
     c) 其他 ADR 依赖 AGP 8.5+ 特性。
   - 触发时的操作清单：`android/build.gradle` 三处 plugin version →
     8.5.1；Gradle distribution → 8.7+（含 CI curl 行与 grep 断言）；
     `:app:assembleDebug` + `:engine:*:test` 全量回归。
3. **camera-core ≥1.4.2**：仅 bundled 轨需要；当前按需（unbundled
   play-services-mlkit-*）轨不含 ML Kit .so，升级点与 AGP 触发条件 a)
   绑定，届时一并切换（见 `feature/ocr/build.gradle` 内注释）。

## Alternatives considered

- **立即升 AGP + Gradle** — 为满足"验收字面"而动摇全绿构建链，收益
  （出厂对齐默认值）在零/低 .so 现实下接近零。否决，改为带触发条件的
  技术债。
- **只对 CI 加 unzip+readelf shell-out** — Windows runner 无 readelf、
  本地无 unzip（守卫曾 exit 3 的根因）。否决，已由纯 Node 版替代。

## Consequences

- 16KB 合规由"守卫 + CI"持续保证，与构建工具解耦；
- AGP 8.2.2 短期保留；升级清单与触发条件落在本 ADR，避免口头挂账；
- 首个 .so（androidx.datastore）的对齐状态以 CI 守卫输出为准
  （docs/android-baseline-after.json 已记录 release APK 实测）。
