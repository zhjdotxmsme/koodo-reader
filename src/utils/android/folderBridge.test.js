/**
 * Unit tests for the Android folder-bridge core (src/utils/android/folderBridge.js).
 *
 * Part of the "Android APK packaging" feature: SAF folder picking + bulk
 * library import into the WebView host.
 *
 * Run via `yarn test` (react-scripts test; roots are limited to src/).
 */

"use strict";

const {
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
} = require("./folderBridge");

describe("constants", () => {
  it("exposes protocol constants", () => {
    expect(FOLDER_EVENT).toBe("folder-picked");
    expect(MAX_FILES).toBe(1000);
    expect(FOLDER_DEPTH).toBe(2);
    expect(ERROR_CODES).toEqual(
      expect.objectContaining({
        FOLDER_LIST_INVALID: "FOLDER_LIST_INVALID",
        FOLDER_ENTRY_INVALID: "FOLDER_ENTRY_INVALID",
        FOLDER_PAYLOAD_INVALID: "FOLDER_PAYLOAD_INVALID",
      })
    );
  });

  it("keeps the book-extension whitelist and MIME map in sync", () => {
    expect(BOOK_EXTENSIONS.length).toBeGreaterThan(10);
    for (const ext of BOOK_EXTENSIONS) {
      expect(typeof ext).toBe("string");
      expect(ext.length).toBeGreaterThan(0);
      expect(MIME_BY_EXT[ext]).toBeTruthy();
    }
  });
});

describe("getExt", () => {
  it("returns the lower-cased last extension", () => {
    expect(getExt("notes.EPUB")).toBe("epub");
    expect(getExt("archive.tar.epub")).toBe("epub");
    expect(getExt("a.b.c.txt")).toBe("txt");
  });

  it("returns '' when there is no extension", () => {
    expect(getExt("readme")).toBe("");
    expect(getExt(".hidden")).toBe("");
    expect(getExt("trailing.")).toBe("");
    expect(getExt(123)).toBe("");
    expect(getExt(null)).toBe("");
  });
});

describe("isBookName", () => {
  it("accepts supported book files (case-insensitive)", () => {
    for (const name of [
      "book.epub",
      "book.pdf",
      "book.MOBI",
      "book.AZW3",
      "story.azw",
      "notes.txt",
      "book.fb2",
      "comic.cbz",
      "comic.cbr",
      "comic.CBT",
      "comic.cb7",
      "doc.md",
      "doc.docx",
      "page.html",
      "page.htm",
      "page.xhtml",
      "page.mhtml",
    ]) {
      expect({ name, ok: isBookName(name) }).toEqual({ name, ok: true });
    }
  });

  it("rejects non-book names", () => {
    for (const name of ["IMG_0001.jpg", "readme", ".hidden", "trailing.", "data.json", "app.exe", null, 42]) {
      expect(isBookName(name)).toBe(false);
    }
  });
});

describe("mimeForExt", () => {
  it("maps known extensions to MIME types", () => {
    expect(mimeForExt("x.epub")).toBe("application/epub+zip");
    expect(mimeForExt("x.cb7")).toBe("application/x-cb7");
    expect(mimeForExt("x.TXT")).toBe("text/plain");
  });

  it("returns '' for unknown names", () => {
    expect(mimeForExt("x.jpg")).toBe("");
    expect(mimeForExt("noext")).toBe("");
    expect(mimeForExt(null)).toBe("");
  });
});

describe("normalizeEntry", () => {
  it("normalizes a raw folder entry", () => {
    const out = normalizeEntry({ name: "book.EPUB", uri: "content://doc/1", size: 1234, mime: "application/octet-stream" });
    expect(out).toEqual({
      name: "book.EPUB",
      uri: "content://doc/1",
      size: 1234,
      mime: "application/epub+zip",
    });
  });

  it("defaults missing size/uri and falls back to the given mime", () => {
    const out = normalizeEntry({ name: "notes.md", uri: undefined, size: undefined, mime: "text/x-md" });
    expect(out).toEqual({ name: "notes.md", uri: "", size: null, mime: "text/markdown" });
    const out2 = normalizeEntry({ name: "mystery.xyz", mime: "application/x-xyz" });
    expect(out2).toEqual({ name: "mystery.xyz", uri: "", size: null, mime: "application/x-xyz" });
  });

  it("rejects invalid entries", () => {
    expect(normalizeEntry(null)).toBeNull();
    expect(normalizeEntry({})).toBeNull();
    expect(normalizeEntry({ name: "" })).toBeNull();
    expect(normalizeEntry({ name: 123 })).toBeNull();
  });
});

describe("filterBooks", () => {
  const mixed = [
    { name: "cover.jpg", uri: "content://a/1", size: 10 },
    { name: "book.AZw3", uri: "content://a/2", size: 20 },
    { name: "a.pdf", uri: "content://a/3" },
    "not-an-object",
    null,
    { name: "", uri: "content://a/4" },
    { name: "comic.cbz", uri: "content://a/5", size: 30, mime: "application/octet-stream" },
  ];

  it("keeps only supported books in order", () => {
    const out = filterBooks(mixed);
    expect(out.map((f) => f.name)).toEqual(["book.AZw3", "a.pdf", "comic.cbz"]);
    expect(out.every((f) => f.mime)).toBe(true);
  });

  it("truncates at MAX_FILES", () => {
    const big = Array.from({ length: MAX_FILES + 10 }, (_, i) => ({ name: `b${i}.txt`, uri: `content://x/${i}` }));
    expect(filterBooks(big)).toHaveLength(MAX_FILES);
  });

  it("throws a typed error on a non-array input", () => {
    const notArray = () => {
      try {
        filterBooks("nope");
        expect.unreachable();
      } catch (e) {
        expect(e).toBeInstanceOf(FolderBridgeError);
        expect(e.code).toBe(ERROR_CODES.FOLDER_LIST_INVALID);
      }
    };
    const notNull = () => {
      try {
        filterBooks(null);
        expect.unreachable();
      } catch (e) {
        expect(e).toBeInstanceOf(FolderBridgeError);
        expect(e.code).toBe(ERROR_CODES.FOLDER_LIST_INVALID);
      }
    };
    notArray();
    notNull();
  });
});

describe("buildPayload", () => {
  it("builds the folder-picked payload JSON", () => {
    const payload = buildPayload({
      folder: "content://doc/tree/x",
      files: [
        { name: "book.epub", uri: "content://doc/1", size: 100 },
        { name: "skip.jpg", uri: "content://doc/2" },
      ],
    });
    const parsed = JSON.parse(payload);
    expect(parsed.event).toBe(FOLDER_EVENT);
    expect(parsed.folder).toBe("content://doc/tree/x");
    expect(parsed.count).toBe(1);
    expect(parsed.files).toEqual([
      { name: "book.epub", uri: "content://doc/1", size: 100, mime: "application/epub+zip" },
    ]);
  });

  it("handles an empty file list", () => {
    const parsed = JSON.parse(buildPayload({ files: [] }));
    expect(parsed.count).toBe(0);
    expect(parsed.files).toEqual([]);
  });

  it("throws a typed error when files is not an array", () => {
    const badInput = (input) => {
      try {
        buildPayload(input);
        expect.unreachable();
      } catch (e) {
        expect(e).toBeInstanceOf(FolderBridgeError);
        expect(e.code).toBe(ERROR_CODES.FOLDER_PAYLOAD_INVALID);
      }
    };
    badInput(undefined);
    badInput({});
    badInput({ files: "nope" });
  });
});
