/**
 * Batch font export: package multiple font files (ArrayBuffer) into a single
 * zip using fflate (already a project dependency). Pure: no DOM/IPC access,
 * so it is unit-testable with fake buffers.
 */
import { zipSync } from "fflate";

export interface FontZipFile {
  /** File name inside the zip (e.g. "SourceHanSans-Regular.ttf") */
  name: string;
  buffer: ArrayBuffer | Uint8Array;
}

/**
 * Build a zip archive containing the given font files.
 * Throws Error on empty input, missing buffer, or invalid names (duplicate
 * names are deduped by appending a numeric suffix).
 */
export function buildFontZip(files: FontZipFile[]): Uint8Array {
  if (!Array.isArray(files) || files.length === 0) {
    throw new Error("No fonts to package");
  }
  const used = new Set<string>();
  const output: Record<string, Uint8Array> = {};
  for (const file of files) {
    if (!file || typeof file !== "object") {
      throw new Error("Invalid font entry");
    }
    const rawName = String(file.name || "").trim();
    if (!rawName) {
      throw new Error("Font name is empty");
    }
    if (!(file.buffer instanceof ArrayBuffer) && !(file.buffer instanceof Uint8Array)) {
      throw new Error(`Missing font data for ${rawName}`);
    }
    const bytes =
      file.buffer instanceof ArrayBuffer ? new Uint8Array(file.buffer) : file.buffer;
    if (bytes.byteLength === 0) {
      throw new Error(`Font "${rawName}" is empty`);
    }
    let name = rawName;
    let suffix = 1;
    while (used.has(name)) {
      // Insert the counter BEFORE the extension: "A.ttf (2)" would put the
      // extension in the middle and the system would not recognise the file
      // as a font. "A (2).ttf" keeps it usable.
      const dot = rawName.lastIndexOf(".");
      const stem = dot > 0 ? rawName.slice(0, dot) : rawName;
      const ext = dot > 0 ? rawName.slice(dot) : "";
      name = `${stem} (${++suffix})${ext}`;
    }
    used.add(name);
    output[name] = bytes;
  }
  return zipSync(output);
}

/** Convert a zip Uint8Array to a Blob for download (browser/DOM only). */
export function fontZipBlob(zip: Uint8Array): Blob {
  if (!(zip instanceof Uint8Array) || zip.byteLength === 0) {
    throw new Error("Invalid zip data");
  }
  return new Blob([zip.slice().buffer], { type: "application/zip" });
}
