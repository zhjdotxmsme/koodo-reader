/**
 * Type definitions for `src/utils/android/folderBridge.js` — Android
 * folder-bridge core (SAF folder picking protocol + book-file rules).
 *
 * Single source of truth shared by:
 *   - `MainActivity.kt` (Kotlin host enumerates files; JS owns the rules),
 *   - the injected JS shim (`window.AndroidBridge.pickFolder/listFolder`),
 *   - the consumer side (engine `ReactNativeWebView` + document message event).
 */

/** Name of the folder-picked event (used in `payload.event`). */
export const FOLDER_EVENT: string;

/** Book extensions the reader supports (lower-case). */
export const BOOK_EXTENSIONS: string[];

/** Canonical MIME type per book extension. */
export const MIME_BY_EXT: Record<string, string>;

/** Maximum number of files enumerated / delivered (kept in sync with Kotlin). */
export const MAX_FILES: number;

/** Directory depth (levels below the root) to enumerate (kept in sync with Kotlin). */
export const FOLDER_DEPTH: number;

/** Stable machine-readable error codes. */
export const ERROR_CODES: {
  FOLDER_LIST_INVALID: string;
  FOLDER_ENTRY_INVALID: string;
  FOLDER_PAYLOAD_INVALID: string;
};

/** Typed error for folder-bridge failures. */
export class FolderBridgeError extends Error {
  code: string;
  detail?: unknown;
  constructor(code: string, message: string, extra?: unknown);
}

/** A raw folder entry as produced by the Android host. */
export interface FolderFileRaw {
  name: string;
  uri: string;
  size?: number | null;
  mime?: string;
}

/** A normalized book file entry. */
export interface BookFile {
  name: string;
  uri: string;
  size: number | null;
  mime: string;
}

/** The canonical `folder-picked` payload (Native -> Web). */
export interface FolderPickedPayload {
  event: string;
  folder: string;
  count: number;
  files: BookFile[];
}

export function getExt(name: unknown): string;
export function isBookName(name: unknown): boolean;
export function mimeForExt(name: unknown): string;
export function normalizeEntry(file: unknown): BookFile | null;
/**
 * Filter a raw folder file list to supported books, normalize each entry and
 * cap the result at MAX_FILES. Throws FolderBridgeError on a non-array.
 */
export function filterBooks(files: unknown): BookFile[];
/** Build the canonical `folder-picked` payload JSON string. */
export function buildPayload(input: { folder?: string; files?: unknown }): string;

export default {
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
