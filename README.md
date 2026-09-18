<div align="left">

[简体中文](./README_cn.md) | [हिंदी](./README_hi.md)
|[Português](./README_pt.md) | [Indonesian](./README_id.md) | English | [Türkçe](./README_tr.md)

</div>

<div align="center" >
  <img src="https://dl.koodoreader.com/screenshots/logo.png" width="96px" height="96px"/>
</div>

<h1 align="center">
  Koodo Reader
</h1>

<h3 align="center">
  A cross-platform ebook reader
</h3>
<div align="center">

[Download](https://koodoreader.com/en) | [Preview](https://web.koodoreader.com) | [Roadmap](https://koodoreader.com/en/roadmap) | [Document](https://koodoreader.com/en/document) | [Plugins](https://koodoreader.com/en/plugin)

</div>

## Preview

<div align="center">
  <br/>
  <br/>
  <img src="https://dl.koodoreader.com/screenshots/7.png" width="800px">
  <br/>
  <br/>
  <img src="https://dl.koodoreader.com/screenshots/8.png" width="800px">
  <br/>
  <br/>
</div>

## Features

- Format support:
  - EPUB (**.epub**)
  - PDF (**.pdf**)
  - DRM-free Mobipocket (**.mobi**) and Kindle (**.azw3**, **.azw**)
  - Plain-text (**.txt**)
  - FictionBook (**.fb2**)
  - Comic book archive (**.cbr**, **.cbz**, **.cbt**, **.cb7**)
  - Rich text (**.md**, **.docx**)
  - HyperText (**.html**, **.xml**, **.xhtml**, **.mhtml**, **.htm**)
- Platform support: **Windows**, **macOS**, **Linux**, **Android**, **iOS** and **Web**
- Sync and backup your data with **OneDrive**, **Google Drive**, **Dropbox**, **iCloud**, **MEGA**, **pCloud**, **Yandex Disk**, **Box**, **FTP**, **SFTP**, **WebDAV**, **SMB**, or **Object Storage**
- Easily import books from **OneDrive**, **Google Drive**, **MEGA**, **Yandex Disk**, **Box**, **FTP**, **SFTP**, **WebDAV**, **SMB**, or **Object Storage**
- Use your custom AI model to power AI Translation, AI Dictionary, AI Summarization, and AI Encyclopedia
- Sync reading progress with **KOReader**
- Sync notes and highlights to **Readwise**, **Notion**, **Obsidian**, **Joplin**, and more
- Support local MDX dictionary lookup
- Automatically sync words to **Anki** and **Eudic**
- Protect your library with password, PIN, Windows Hello, Touch ID, and more
- One-click export of all books
- One-click export of notes and highlights, supporting **CSV**, **Markdown**, **HTML**, **TXT**, and **PDF**
- Privacy-first design: no tracking services, and no proactive uploading of your reading data or personal information
- Support **OPDS** protocol and share your library as an **OPDS** feed
- Support browser extension to save anything on the web to your library
- Built-in 50+ plugins for translation, dictionaries, and text-to-speech, with support for custom plugins
- Support vertical layout book
- Support reading statistics
- Built-in **Paddle** and **Tesseract** OCR engines
- Support library snapshots and version control
- Single-column, two-column or continuous scrolling layouts
- Text-to-speech, translation, dictionary, touch screen support, and batch import
- Add bookmarks, notes, and highlights to your books
- Adjust font size, font family, line-spacing, paragraph spacing, background color, text color, margins, and brightness
- Night mode and theme color
- Text highlighting, underline, boldness, italics, and shadow

## Installation

### Desktop version: [Download](https://koodoreader.com/en/download)

### Web version：[Visit](https://web.koodoreader.com)

### Android version：[Download](https://koodoreader.com/en/download)

### iOS version：[Download](https://koodoreader.com/en/download)

### Browser extension：[Download](https://www.koodoreader.com/en/use-extension)

### Install with Scoop:

```shell
scoop bucket add extras
scoop install extras/koodo-reader
```

### Install with Winget:

```shell
winget install AppByTroye.KoodoReader
```

### Install with Flathub:

```shell
flatpak install flathub io.github.troyeguo.koodo-reader
```

### Install with Snap Store:

```shell
sudo snap install koodo-reader
```

### Install with Homebrew:

```shell
brew install --cask koodo-reader
```

### Install with Docker:

[Installation Guide](https://koodoreader.com/en/deploy-docker)

## Screenshot

<div align="center">
  <b>Book list</b>
  <br/>
  <br/>
  <kbd><img src="https://dl.koodoreader.com/screenshots/1.png" width="800px"></kbd>
  <br/>
  <br/>
  <b>Book display</b>
  <br/>
  <br/>
  <kbd><img src="https://dl.koodoreader.com/screenshots/5.png" width="800px"></kbd>
  <br/>
  <br/>
  <b>List mode</b>
  <br/>
  <br/>
  <kbd><img src="https://dl.koodoreader.com/screenshots/2.png" width="800px"></kbd>
  <br/>
  <br/>
  <b>Cover mode</b>
  <br/>
  <br/>
  <kbd><img src="https://dl.koodoreader.com/screenshots/3.png" width="800px"></kbd>
  <br/>
  <br/>
  <b>Reader menu</b>
  <br/>
  <br/>
  <kbd><img src="https://dl.koodoreader.com/screenshots/6.png" width="800px"></kbd>
  <br/>
  <br/>
  <b>Dark mode</b>
  <br/>
  <br/>
  <kbd><img src="https://dl.koodoreader.com/screenshots/4.png" width="800px"></kbd>
  <br/>
</div>

## Develop

Make sure that you have installed yarn and git

1. Download the repo

   ```
   git clone https://github.com/koodo-reader/koodo-reader.git
   ```

2. Enter desktop mode

   ```
   yarn
   yarn dev
   ```

3. Enter web mode

   ```
   yarn
   yarn start
   ```

4. Build the Android APK

   The Android host lives in `android/`; the web build is staged into the APK's
   assets. You need the Android SDK + a Gradle 8.x installation (CI sets both up).

    ```
    yarn build                                  # build the web app into build/
    node scripts/build-android.js --debug       # build an installable, self-signed debug APK
    ```

   Useful options:
   - `--release` — build a release APK (requires a signing keystore, see below).
   - `--abi arm64-v8a,armeabi-v7a` — restrict which ABIs to build (both by default).
   - `--no-split` — produce one universal APK instead of one APK per ABI.
   - `--dry-run` — stage assets and print the Gradle plan without running Gradle.
   - `--keystore <path>` / `--store-password <p>` / `--key-alias <a>` / `--key-password <p>` — release signing.

   A release build needs a keystore (via the flags above, or the
   `ANDROID_KEYSTORE` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` /
   `ANDROID_KEY_PASSWORD` environment variables). The build is configured in
   `android.config.json`, and CI produces the APK via
   [.github/workflows/release-android.yml](.github/workflows/release-android.yml).

    **Local library import (SAF)** — call `window.AndroidBridge.pickFolder()` (or
    `window.ReactNativeWebView.pickFolder()`) from the page: the host opens Android's
    storage picker, keeps the folder's read permission across restarts, and returns its
    files (root + 2 sub-directory levels, up to 1000 files) via
    `window.ReactNativeWebView.onFolderPicked(json)` and a `document` `message` event
    (engine style: `JSON.parse(event.data)`):
    `{"event":"folder-picked","folder":"content://...","count":3,"files":[{"name","uri","size","mime"}]}`.
    Every `uri` is a `content://` URI the engine can `fetch()` directly (e.g.
    `addMobileBook`). `AndroidBridge.listFolder(uri)` re-lists a previously picked
    folder. Book-file rules (extensions/MIME/payload) live in
    `src/utils/android/folderBridge.js` (unit tested; Kotlin only enumerates).

    **Local HTTP origin** — the host does not load the page over `file://`. It starts a
    dependency-free loopback server (`LocalAssetServer`, bound to `127.0.0.1` on an
    ephemeral port) and loads `http://127.0.0.1:<port>/index.html`, so the page runs on a
    real origin (IndexedDB / localStorage / service workers / CORS behave normally). The
    server serves `assets/webapp/…` with correct MIME types, supports single-range
    requests and falls back to `index.html` for extension-less routes. If it cannot
    start, the loader silently falls back to `file:///android_asset/webapp/index.html`.

    **"Open with" / share target** — the manifest declares `VIEW` and `SEND` filters for
    the ebook MIME types the app supports, so books can be opened from a file manager,
    browser or share sheet. The host copies the incoming document into its cache, exposes
    it through the loopback server and hands it to the web app, which imports it through
    the normal pipeline: `window.__koodoNative.openLocalFile(url, name)` (registered by
    `src/components/importLocal`; contract in `src/utils/android/nativeBridge.js`).
    Several components contribute hooks to `window.__koodoNative` via
    `registerHostHooks` / `unregisterHostHooks`.

   > **Scope note** — the Android host embeds the shared web build in a WebView and
   > bridges to the reading engine's existing `ReactNativeWebView` surface (book pick,
   > errors). Desktop-only native features (e.g. `better-sqlite3`, cloud-sync plugins,
   > native OCR) are not available inside the WebView build.

## Translation

### Edit current language

1. Select your target language from the following list.

2. Click the view button to examine the source file. The untranslated terms are listed at the bottom of each file.

3. Translate the terms to your target language based on the given English reference

4. Submit the translation file or just translation snippets based on the amount of your translation to [this link](https://github.com/koodo-reader/koodo-reader/issues/new?assignees=&labels=submit+translation&projects=&template=submit_translation.yml). Pull request is also welcomed.

| Language(A-Z)   | Code  | View                                    |
| --------------- | ----- | --------------------------------------- |
| Amharic         | am    | [View](./src/assets/locales/am.json)    |
| Arabic          | ar    | [View](./src/assets/locales/ar.json)    |
| Armenian        | hy    | [View](./src/assets/locales/hy.json)    |
| Bengali         | bn    | [View](./src/assets/locales/bn.json)    |
| Bulgarian       | bg    | [View](./src/assets/locales/bg.json)    |
| Chinese (CN)    | zh-CN | [View](./src/assets/locales/zh-CN.json) |
| Chinese (MO)    | zh-MO | [View](./src/assets/locales/zh-MO.json) |
| Chinese (TW)    | zh-TW | [View](./src/assets/locales/zh-TW.json) |
| Czech           | cs    | [View](./src/assets/locales/cs.json)    |
| Danish          | da    | [View](./src/assets/locales/da.json)    |
| Dutch           | nl    | [View](./src/assets/locales/nl.json)    |
| English         | en    | [View](./src/assets/locales/en.json)    |
| Finnish         | fi    | [View](./src/assets/locales/fi.json)    |
| French          | fr    | [View](./src/assets/locales/fr.json)    |
| German          | de    | [View](./src/assets/locales/de.json)    |
| Greek           | el    | [View](./src/assets/locales/el.json)    |
| Hindi           | hi    | [View](./src/assets/locales/hi.json)    |
| Hungarian       | hu    | [View](./src/assets/locales/hu.json)    |
| Indonesian      | id    | [View](./src/assets/locales/id.json)    |
| Interlingue     | ie    | [View](./src/assets/locales/ie.json)    |
| Irish           | ga    | [View](./src/assets/locales/ga.json)    |
| Italian         | it    | [View](./src/assets/locales/it.json)    |
| Japanese        | ja    | [View](./src/assets/locales/ja.json)    |
| Korean          | ko    | [View](./src/assets/locales/ko.json)    |
| Persian         | fa    | [View](./src/assets/locales/fa.json)    |
| Polish          | pl    | [View](./src/assets/locales/pl.json)    |
| Portuguese      | pt    | [View](./src/assets/locales/pt.json)    |
| Portuguese (BR) | pt-BR | [View](./src/assets/locales/pt-BR.json) |
| Romanian        | ro    | [View](./src/assets/locales/ro.json)    |
| Russian         | ru    | [View](./src/assets/locales/ru.json)    |
| Slovenian       | sl    | [View](./src/assets/locales/sl.json)    |
| Spanish         | es    | [View](./src/assets/locales/es.json)    |
| Swedish         | sv    | [View](./src/assets/locales/sv.json)    |
| Tamil           | ta    | [View](./src/assets/locales/ta.json)    |
| Thai            | th    | [View](./src/assets/locales/th.json)    |
| Tagalog         | tl    | [View](./src/assets/locales/tl.json)    |
| Tibetan         | bo    | [View](./src/assets/locales/bo.json)    |
| Turkish         | tr    | [View](./src/assets/locales/tr.json)    |
| Ukrainian       | uk    | [View](./src/assets/locales/uk.json)    |
| Vietnamese      | vi    | [View](./src/assets/locales/vi.json)    |

### Add new language

1. If you can't find your target language from the above list, download the English source file from [this link](./src/assets/locales/en.json).

2. When you're finished translating, submit the source file to [this link](https://github.com/koodo-reader/koodo-reader/issues/new?assignees=&labels=submit+translation&projects=&template=submit_translation.yml). Pull requests are also welcome.