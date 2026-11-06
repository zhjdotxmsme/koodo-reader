# Android Native Validation Checklist (2026-11-02 plans)

> Device matrix and Macrobenchmark results required before **native-closeout** sign-off.
> Automation-only JVM tests do **not** satisfy performance gates in the text/PDF plans.

## Build under test

```bat
node scripts/build-android.js --target native --release
REM Optional canvas-only smoke: add -Preader=canvas (foliate remains the default)
```

## Automated results (2026-11-03, no device)

- `node scripts/check-elf-16kb.js app-nativeHost-release-unsigned.apk`: PASS — 4 `.so`, all 16 KB aligned, no pdfium.
- JVM suites green: `engine:layout`, `engine:text` (incl. new `StreamingTextIndexTest`), `core:importer`, `core:reader`, `core:sync`, `engine:mobi/fb2/docx/htmlbook/annotate/toc`, `feature:stats`, `core:dbio`, app `testNativeHostDebugUnitTest`.
- Desktop `npx jest src/utils/android/androidBuild.test.js`: 880/880.
- `check-room-schema`: OK (5 tables aligned).
- EPUB default engine reverted to foliate after the canvas-default regression (open crash + missing selection menu); canvas stays opt-in via `-Preader=canvas`.

All device rows below remain NOT RUN.

## Device matrix (record PASS/FAIL + device model / API)

| Check | Min API 24 | 4 KB page | 16 KB page (Android 15+) |
|-------|------------|-----------|---------------------------|
| Import 1000 books (no OOM) | | | |
| External VIEW/SEND → reader (`ExternalBookTrampolineActivity` on native) | | | |
| EPUB 300 chapters open ≤ 1.2s first frame | | | |
| Page turn P90 < 50 ms (canvas) | | | |
| 10万 chars peak RAM < 350 MB | | | |
| PDF 1000 pages open ≤ 1.5s (pdf.js path) | | | |
| Progress / bookmark / note round-trip | | | |
| JSON + MD export | | | |

## Macrobenchmark

```bat
scripts/ci-macro-benchmark.sh
```

Store raw JSON under `docs/benchmarks/` (gitignored or committed per release policy).

## Sign-off

- [ ] All matrix rows PASS on at least one device per column
- [ ] Macrobenchmark artifacts attached
- [ ] `node scripts/check-elf-16kb.js <apk>` PASS for release ABI set
- [ ] `node scripts/check-native-apk-assets.js <apk>` PASS (no `assets/webapp/` when `-PstripIsland=true`)
