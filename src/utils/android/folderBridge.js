/**
 * Android folder-bridge core logic ("select folder / bulk library import").
 *
 * Pure, side-effect-free and dependency-free, so it can be:
 *   - consumed by `MainActivity.kt` (Kotlin side only enumerates; this module
 *     is the SINGLE SOURCE OF TRUTH for the protocol and book-file rules),
 *   - consumed by the injected JS shim (`window.AndroidBridge` helpers), and
 *   - unit-tested by Jest under `react-scripts test`.
 *
 * Protocol (Native -> Web), see MainActivity.deliverFolderResult:
 *   1. `window.ReactNativeWebView.onFolderPicked(payloadJson)` and
 *   2. a `document` "message" event whose `.data` is the same JSON string
 *      (engine style: `JSON.parse(event.data)`).
 *
 *   payload = {
 *     event: "folder-picked",
 *     folder: string,           // the tree URI (content://...)
 *     count: number,            // files.length
 *     files: [ { name, uri, size, mime }, ... ]   // all files (capped)
 *   }
 *
 * @module utils/android/folderBridge
 */

"use strict";

/** Name of the folder-picked event (used in `payload.event`). */
const FOLDER_EVENT = "folder-picked";

/**
 * Book extensions the reader supports (lower-case).
 * @readonly {string[]}
 */
const BOOK_EXTENSIONS = [
  "epub",
  "pdf",
  "mobi",
  "azw3",
  "azw",
  "txt",
  "fb2",
  "cbz",
  "cbr",
  "cbt",
  "cb7",
  "md",
  "docx",
  "html",
  "htm",
  "xhtml",
  "mhtml",
];

/** Canonical MIME type per book extension. @readonly {Object<string,string>} */
const MIME_BY_EXT = {
  epub: "application/epub+zip",
  pdf: "application/pdf",
  mobi: "application/x-mobipocket-ebook",
  azw3: "application/vnd.amazon.ebook",
  azw: "application/vnd.amazon.ebook",
  txt: "text/plain",
  fb2: "application/x-fictionbook+xml",
  cbz: "application/x-cbz",
  cbr: "application/x-cbr",
  cbt: "application/x-cbt",
  cb7: "application/x-cb7",
  md: "text/markdown",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  html: "text/html",
  htm: "text/html",
  xhtml: "application/xhtml+xml",
  mhtml: "message/rfc822",
};

/**
 * Maximum number of files enumerated / delivered. Keep in sync with
 * `MainActivity.MAX_FILES` (Kotlin).
 * @readonly {number}
 */
const MAX_FILES = 1000;

/** Directory depth (levels below the root) to enumerate. Keep in sync with
 * `MainActivity.FOLDER_DEPTH` (Kotlin). @readonly {number}
 */
const FOLDER_DEPTH = 2;

/**
 * Typed error for folder-bridge failures.
 * @extends Error
 */
class FolderBridgeError extends Error {
  /**
   * @param {string} code stable machine-readable code
   * @param {string} message human-readable, actionable description
   * @param {Object} [extra] structured detail
   */
  constructor(code, message, extra) {
    super(message);
    this.name = "FolderBridgeError";
    this.code = code;
    if (extra !== undefined) {
      this.detail = extra;
    }
  }
}

const ERROR_CODES = {
  FOLDER_LIST_INVALID: "FOLDER_LIST_INVALID",
  FOLDER_ENTRY_INVALID: "FOLDER_ENTRY_INVALID",
  FOLDER_PAYLOAD_INVALID: "FOLDER_PAYLOAD_INVALID",
};

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/**
 * Lower-cased extension of a file name (without the dot), or "" when there is
 * none. "archive.tar.epub" -> "epub"; "readme" -> "".
 *
 * @param {unknown} name
 * @returns {string}
 */
function getExt(name) {
  if (typeof name !== "string") return "";
  const idx = name.lastIndexOf(".");
  if (idx <= 0 || idx === name.length - 1) return "";
  return name.slice(idx + 1).toLowerCase();
}

/**
 * Whether a file name is a supported book (case-insensitive).
 * @param {unknown} name
 * @returns {boolean}
 */
function isBookName(name) {
  return BOOK_EXTENSIONS.includes(getExt(name));
}

/**
 * Canonical MIME type for a file name's extension ("" if not a known book).
 * @param {unknown} name
 * @returns {string}
 */
function mimeForExt(name) {
  return MIME_BY_EXT[getExt(name)] || "";
}

/**
 * Normalize one raw folder entry to `{ name, uri, size, mime }`.
 * Invalid entries return null (callers filter them out).
 *
 * @param {unknown} file
 * @returns {{name:string, uri:string, size:(number|null), mime:string}|null}
 */
function normalizeEntry(file) {
  if (!isPlainObject(file)) return null;
  if (typeof file.name !== "string" || file.name.length === 0) return null;
  const uri = typeof file.uri === "string" ? file.uri : "";
  const size =
    typeof file.size === "number" && Number.isFinite(file.size) && file.size >= 0
      ? file.size
      : null;
  const mime =
    MIME_BY_EXT[getExt(file.name)] ||
    (typeof file.mime === "string" && file.mime) ||
    "";
  return { name: file.name, uri, size, mime };
}

/**
 * Filter a raw folder file list down to supported books, normalize each entry,
 * and cap the result at {@link MAX_FILES}. Pure.
 *
 * Non-book / invalid entries are dropped. Order is preserved.
 *
 * @param {unknown} files raw list from the host: `{name, uri, size?, mime?}[]`
 * @returns {{name:string, uri:string, size:(number|null), mime:string}[]}
 * @throws {FolderBridgeError} FOLDER_LIST_INVALID when `files` is not an array
 */
function filterBooks(files) {
  if (!Array.isArray(files)) {
    throw new FolderBridgeError(
      ERROR_CODES.FOLDER_LIST_INVALID,
      "filterBooks(files) expects a folder file list array",
      { given: typeof files }
    );
  }
  const books = [];
  for (const file of files) {
    const name = isPlainObject(file) ? file.name : null;
    if (!isBookName(name)) continue;
    const norm = normalizeEntry(file);
    if (norm && books.length < MAX_FILES) {
      books.push(norm);
    }
  }
  return books;
}

/**
 * Build the canonical `folder-picked` payload JSON string for the native host.
 * Pure. Throws a typed error on invalid input.
 *
 * @param {{folder?: string, files?: unknown}} input
 * @param {string} [input.folder] folder (tree) URI
 * @param {Array<object>} [input.files] raw entries (will be filtered/normalized)
 * @returns {string} JSON string
 * @throws {FolderBridgeError} FOLDER_PAYLOAD_INVALID on invalid files
 */
function buildPayload(input) {
  const cfg = isPlainObject(input) ? input : {};
  if (!Array.isArray(cfg.files)) {
    throw new FolderBridgeError(
      ERROR_CODES.FOLDER_PAYLOAD_INVALID,
      "buildPayload expects { folder?, files: entry[] }",
      { given: cfg && typeof cfg.files }
    );
  }
  const books = filterBooks(cfg.files);
  const folder = typeof cfg.folder === "string" ? cfg.folder : "";
  return JSON.stringify({
    event: FOLDER_EVENT,
    folder,
    count: books.length,
    files: books,
  });
}

module.exports = {
  FOLDER_EVENT,
  BOOK_EXTENSIONS,
  MIME_BY_EXT,
  MAX_FILES,
  FOLDER_DEPTH,
  ERROR_CODES,
  FolderBridgeError,
  getExt,
  isBookName,
  mimeForExt,
  normalizeEntry,
  filterBooks,
  buildPayload,
};
