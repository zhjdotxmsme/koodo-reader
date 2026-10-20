# Foliate Reflow Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reflowable books (EPUB/MOBI/AZW/AZW3/FB2/TXT/MD/HTML/DOCX) render in a visible WebView via vendored Readest/foliate-js, keeping CSS, TOC, and HUFF.

**Architecture:** Mirror `pdfengine`: `LocalAssetServer` serves `assets/foliate/`; `FoliateHostBridge` hosts a visible `foliate-view`; `NativeEpubScreen` Compose chrome stays; Canvas `ReaderSessionFactory` remains for tests and non-foliate formats.

**Tech Stack:** Kotlin/Compose, foliate-js (MIT), fflate, marked, existing `PdfEngineReadyLatch` / loopback HTTP.

## Global Constraints

- Native Android track only (`-Ptarget=native`); no cloud sync, no desktop plugins.
- User-visible strings via `t("key")` from existing en.json keys.
- WebView mutations only on the WebView thread (`webView.post`).
- PDF / CBZ / CBT / CB7 / CBR routing unchanged.
- Do not commit unless the user asks.

---

## File map

| File | Role |
|------|------|
| `android/app/src/main/java/com/koodoreader/reader/AssetPaths.kt` | Add `foliate` root |
| `android/app/src/test/java/com/koodoreader/reader/AssetPathsTest.kt` | Pin foliate bootstrap path |
| `android/app/src/main/java/com/koodoreader/reader/LocalAssetServer.kt` | extraRoots from DEFAULT_ROOTS |
| `android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateFormats.kt` | Which extensions use the host |
| `android/app/src/test/java/com/koodoreader/reader/epubhost/FoliateFormatsTest.kt` | Routing tests |
| `android/app/src/main/assets/foliate/` | Vendored foliate-js + `index.html` + `engine.mjs` |
| `scripts/test-foliate-host.js` | Node tests for engine.mjs |
| `android/app/src/main/java/com/koodoreader/reader/epubhost/FoliateHostBridge.kt` | Visible WebView bridge |
| `android/app/src/main/java/com/koodoreader/reader/epubhost/NativeEpubScreen.kt` | Foliate pane vs Canvas |

---

## Task 1: AssetPaths + server roots

- [ ] RED: `AssetPathsTest` expects `DEFAULT_ROOTS` includes `foliate`; `/foliate/index.html` does not double-prefix.
- [ ] GREEN: add `"foliate"` to `DEFAULT_ROOTS`; `LocalAssetServer` extraRoots default to `AssetPaths.DEFAULT_ROOTS - assetRoot`.
- [ ] Verify tests pass.

## Task 2: FoliateFormats routing

- [ ] RED: `FoliateFormatsTest` — epub/mobi/azw3/fb2/txt/md/html/docx → true; pdf/cbz/cbr → false.
- [ ] GREEN: implement `FoliateFormats.usesHost(format)`.
- [ ] Verify tests pass.

## Task 3: Vendor foliate-js + engine.mjs

- [ ] Copy MIT foliate-js (epub/mobi/fb2/view + fflate) into `assets/foliate/`.
- [ ] RED: `scripts/test-foliate-host.js` — open minimal EPUB fixture returns toc with real labels; TXT path converts.
- [ ] GREEN: `engine.mjs` + `index.html` implementing `open/toc/goTo/search/setAppearance/close` and `ready` event.
- [ ] Run `node scripts/test-foliate-host.js`.

## Task 4: FoliateHostBridge

- [ ] Kotlin bridge modeled on `PdfJsHostBridge`: visible view, post-to-main bootstrap, reuse `PdfEngineReadyLatch`.
- [ ] Methods: `bootstrap`, `open`, `toc`, `goTo`, `search`, `setAppearance`, `close`.

## Task 5: NativeEpubScreen host path

- [ ] If `FoliateFormats.usesHost(format)`: publish book URL, attach visible WebView, Compose TOC from `bridge.toc()`, progress via relocate CFI.
- [ ] Hide bionic/ruler/paragraph/speed menu items on this path.
- [ ] Canvas path unchanged for other formats / factory tests.

## Task 6: Device smoke (if phone connected)

- [ ] Open the existing 中原图景 EPUB: CSS present, TOC Chinese titles.
- [ ] Confirm PDF/CBZ still use old screens.
