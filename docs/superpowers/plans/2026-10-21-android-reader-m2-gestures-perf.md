# Android Reader M2 — Gestures + performance tier

**Goal:** Configurable tap zones / swipe / volume keys; `normal` vs `lite` perf profile; foliate asset prefetch at shell startup.

**Status:** Implemented 2026-10-21.

## Delivered

| Item | Location |
|------|----------|
| Gesture prefs | `ReaderGestureConfig`, `ReaderGesturePrefs`, `ReaderGestureRuntime` |
| Perf tier | `ReaderFeatureState.perfTier`, `ReaderPerformancePolicy` |
| EPUB/PDF gestures | `NativeEpubScreen`, `NativePdfScreen`, `NativeShellActivity` volume |
| Foliate limits | `ReadestViewSettings.maxInlineSizePx`, `engine.mjs` |
| PDF raster | `PdfReaderController.pdfRenderScale` |
| Settings UI | `ReadingSettingsRoute` |
| Prefetch | `NativeShellActivity.prefetchReflowEngine` |
| i18n | `src/assets/locales/en.json` |

## Verify

```powershell
node scripts/test-foliate-host.js
cd android
.\gradlew.bat --no-build-cache :app:testDebugUnitTest --tests com.koodoreader.reader.shell.ReaderGestureConfigTest --tests com.koodoreader.reader.shell.ReaderPerformancePolicyTest --tests com.koodoreader.reader.shell.ReaderPageGesturesTest
```
