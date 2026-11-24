import { unzipSync } from "fflate";
import { buildFontZip, fontZipBlob } from "./fontZip";

function fakeBuffer(chars: string): ArrayBuffer {
  const bytes = new Uint8Array(chars.length);
  for (let i = 0; i < chars.length; i++) {
    bytes[i] = chars.charCodeAt(i);
  }
  return bytes.buffer;
}

/**
 * Read the entry names out of a zip archive. The point of the test is the
 * NAME of each packaged file, so the archive is unpacked with the same
 * library that produced it.
 */
function listZipEntryNames(zip: Uint8Array): string[] {
  return Object.keys(unzipSync(zip));
}

describe("buildFontZip (A8)", () => {
  it("packages multiple fonts into a zip with a valid signature", () => {
    const zip = buildFontZip([
      { name: "A.ttf", buffer: fakeBuffer("fontdataA") },
      { name: "B.otf", buffer: fakeBuffer("fontdataB") },
    ]);
    expect(zip.byteLength).toBeGreaterThan(0);
    // ZIP local file header signature: 50 4b 03 04
    expect(zip[0]).toBe(0x50);
    expect(zip[1]).toBe(0x4b);
    expect(zip[2]).toBe(0x03);
    expect(zip[3]).toBe(0x04);
  });

  it("accepts Uint8Array buffers", () => {
    const zip = buildFontZip([
      { name: "A.ttf", buffer: new Uint8Array([1, 2, 3]) },
    ]);
    expect(zip.byteLength).toBeGreaterThan(0);
  });

  it("deduplicates identical names", () => {
    const zip = buildFontZip([
      { name: "dup.ttf", buffer: fakeBuffer("one") },
      { name: "dup.ttf", buffer: fakeBuffer("two") },
    ]);
    expect(zip.byteLength).toBeGreaterThan(0);
  });

  it("keeps the extension at the end when deduplicating", () => {
    // "dup.ttf (2)" would put the extension in the middle and the system
    // would not recognise the file as a font.
    const zip = buildFontZip([
      { name: "dup.ttf", buffer: fakeBuffer("one") },
      { name: "dup.ttf", buffer: fakeBuffer("two") },
    ]);
    const names = listZipEntryNames(zip);
    expect(names).toContain("dup.ttf");
    expect(names).toContain("dup (2).ttf");
    expect(names.every((n) => n.endsWith(".ttf"))).toBe(true);
  });

  it("keeps every entry name unique", () => {
    const zip = buildFontZip([
      { name: "a.ttf", buffer: fakeBuffer("1") },
      { name: "a.ttf", buffer: fakeBuffer("2") },
      { name: "a.ttf", buffer: fakeBuffer("3") },
    ]);
    const names = listZipEntryNames(zip);
    expect(new Set(names).size).toBe(names.length);
  });

  it("throws on empty input and invalid entries", () => {
    expect(() => buildFontZip([])).toThrow("No fonts to package");
    expect(() =>
      buildFontZip([{ name: "", buffer: fakeBuffer("x") } as any])
    ).toThrow("Font name is empty");
    expect(() => buildFontZip([{ name: "a.ttf", buffer: null as any }])).toThrow(
      "Missing font data"
    );
    expect(() =>
      buildFontZip([{ name: "empty.ttf", buffer: new Uint8Array(0) as any }])
    ).toThrow("is empty");
  });
});

describe("fontZipBlob", () => {
  it("wraps a zip into a downloadable blob", () => {
    const zip = buildFontZip([{ name: "A.ttf", buffer: fakeBuffer("x") }]);
    const blob = fontZipBlob(zip);
    expect(blob.type).toBe("application/zip");
    expect(blob.size).toBe(zip.byteLength);
  });

  it("rejects empty data", () => {
    expect(() => fontZipBlob(new Uint8Array(0))).toThrow("Invalid zip data");
  });
});
