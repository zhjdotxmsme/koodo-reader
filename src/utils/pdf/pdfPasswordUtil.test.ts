import {
  extractPdfPasswordFromDescription,
  getPdfPassword,
  migratePdfPasswordOutOfDescription,
  rememberPdfPassword,
  removePdfPassword,
  stripPdfPasswordFromDescription,
} from "./pdfPasswordUtil";

describe("pdfPasswordUtil (D1)", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("stores and retrieves a password per book", () => {
    rememberPdfPassword("book-a", "secret-密码");
    expect(getPdfPassword("book-a")).toBe("secret-密码");
    // another book is unaffected
    expect(getPdfPassword("book-b")).toBeNull();
  });

  it("does not store the value in plaintext", () => {
    rememberPdfPassword("book-a", "plainsecret");
    const raw = localStorage.getItem("koodo:pdfPwd:book-a") || "";
    expect(raw).not.toContain("plainsecret");
    expect(raw.length).toBeGreaterThan(0);
  });

  it("removes a stored password and reports existence", () => {
    expect(removePdfPassword("book-a")).toBe(false);
    rememberPdfPassword("book-a", "x");
    expect(removePdfPassword("book-a")).toBe(true);
    expect(getPdfPassword("book-a")).toBeNull();
  });

  it("supports unicode round-trips", () => {
    const unicode = "pässwörd·密码·密码";
    rememberPdfPassword("u1", unicode);
    expect(getPdfPassword("u1")).toBe(unicode);
  });

  it("rejects empty book keys and oversized values", () => {
    expect(() => rememberPdfPassword("", "x")).toThrow("bookKey is required");
    expect(() => rememberPdfPassword("\u0000\u0001", "x")).toThrow();
    expect(() =>
      rememberPdfPassword("ok", "a".repeat(3000))
    ).toThrow("too long");
  });

  it("degrades safely for bad lookups", () => {
    expect(getPdfPassword("")).toBeNull();
    // corrupt value falls back to the raw string, never throws
    localStorage.setItem("koodo:pdfPwd:corrupt", "!notbase64!");
    expect(() => getPdfPassword("corrupt")).not.toThrow();
  });

  describe("description migration (password out of the synced field)", () => {
    it("extracts the password the engine embedded in the metadata", () => {
      expect(
        extractPdfPasswordFromDescription("intro\nprotected PDF: #secret#")
      ).toBe("secret");
      expect(
        extractPdfPasswordFromDescription("protected PDF: #p@ss#word#")
      ).toBe("p@ss");
    });

    it("returns null when there is no password marker", () => {
      expect(extractPdfPasswordFromDescription("just a description")).toBeNull();
      expect(extractPdfPasswordFromDescription("")).toBeNull();
      expect(extractPdfPasswordFromDescription(undefined)).toBeNull();
    });

    it("removes the marker but keeps the rest of the description", () => {
      expect(
        stripPdfPasswordFromDescription("intro\nprotected PDF: #secret#")
      ).toBe("intro");
      expect(
        stripPdfPasswordFromDescription("scanned PDF\nprotected PDF: #s#")
      ).toContain("scanned PDF");
    });

    it("moves the password into the local store and cleans the description", () => {
      const cleaned = migratePdfPasswordOutOfDescription(
        "book-a",
        "intro\nprotected PDF: #secret#"
      );

      expect(cleaned).toBe("intro");
      expect(cleaned).not.toContain("secret");
      expect(getPdfPassword("book-a")).toBe("secret");
    });

    it("leaves a description without a marker untouched", () => {
      expect(migratePdfPasswordOutOfDescription("book-a", "plain")).toBe("plain");
      expect(getPdfPassword("book-a")).toBeNull();
    });

    it("keeps the password in place when the local store rejects it", () => {
      // An over-long book key makes the store throw; the description must
      // keep the password so it is not lost.
      const longKey = "k".repeat(600);
      const description = "intro\nprotected PDF: #secret#";
      const result = migratePdfPasswordOutOfDescription(longKey, description);
      expect(result).toBe(description);
    });
  });
});
