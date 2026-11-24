/**
 * PDF "remember password" support.
 *
 * Passwords are stored per book key in localStorage. Values are base64
 * obfuscated (NOT encryption — documented as such) to avoid plaintext
 * scanning of the local store; the key itself is never logged. The API is
 * synchronous and defensive: malformed keys/data degrade to "no password".
 */

const KEY_PREFIX = "koodo:pdfPwd:";
const MAX_BOOK_KEY_LENGTH = 512;
const MAX_PASSWORD_LENGTH = 2048;

function sanitizeBookKey(bookKey: string): string {
  const key = String(bookKey || "").replace(/[\u0000-\u001f\u007f]/g, "");
  if (!key) {
    throw new Error("bookKey is required");
  }
  if (key.length > MAX_BOOK_KEY_LENGTH) {
    throw new Error("bookKey is too long");
  }
  return key;
}

function encode(value: string): string {
  try {
    // encodeURIComponent -> pure ASCII -> btoa-safe for any unicode
    return btoa(encodeURIComponent(value));
  } catch {
    return value;
  }
}

function decode(value: string): string {
  try {
    return decodeURIComponent(atob(value));
  } catch {
    // legacy/plain value
    return value;
  }
}

/** Store the PDF password for a book (max 2048 chars). Returns the key. */
export function rememberPdfPassword(bookKey: string, password: string): string {
  const key = sanitizeBookKey(bookKey);
  const pwd = String(password || "");
  if (pwd.length > MAX_PASSWORD_LENGTH) {
    throw new Error("Password is too long");
  }
  const fullKey = KEY_PREFIX + key;
  try {
    if (pwd === "") {
      localStorage.removeItem(fullKey);
    } else {
      localStorage.setItem(fullKey, encode(pwd));
    }
  } catch {
    throw new Error("Failed to store PDF password");
  }
  return key;
}

/** Retrieve the remembered password, or null when absent/unreadable. */
export function getPdfPassword(bookKey: string): string | null {
  let key: string;
  try {
    key = sanitizeBookKey(
      String(bookKey || "").replace(/[\u0000-\u001f\u007f]/g, "")
    );
  } catch {
    return null;
  }
  try {
    const raw = localStorage.getItem(KEY_PREFIX + key);
    if (!raw) {
      return null;
    }
    const value = decode(raw);
    return value.length > 0 ? value : null;
  } catch {
    return null;
  }
}

/** Remove the remembered password. Returns true if a value was present. */
export function removePdfPassword(bookKey: string): boolean {
  let key: string;
  try {
    key = sanitizeBookKey(bookKey);
  } catch {
    return false;
  }
  const fullKey = KEY_PREFIX + key;
  try {
    const existed = localStorage.getItem(fullKey) !== null;
    localStorage.removeItem(fullKey);
    return existed;
  } catch {
    return false;
  }
}

/**
 * The marker the reading engine appends to a protected PDF's metadata:
 * `description` ends up as `"...\nprotected PDF: #<password>#"`.
 */
const PROTECTED_PDF_PATTERN = /protected PDF: #(.+?)#/;

/**
 * Extract the PDF password the engine embedded in a book description.
 * Returns null when the description carries no password marker.
 */
export function extractPdfPasswordFromDescription(
  description: string | undefined | null
): string | null {
  if (!description) {
    return null;
  }
  const match = PROTECTED_PDF_PATTERN.exec(description);
  return match ? match[1] : null;
}

/**
 * Remove the password marker from a book description, leaving the rest
 * (e.g. the "scanned PDF" note) intact.
 */
export function stripPdfPasswordFromDescription(
  description: string | undefined | null
): string {
  if (!description) {
    return "";
  }
  return description
    .replace(PROTECTED_PDF_PATTERN, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/**
 * Move a PDF password out of a book description and into the local store.
 *
 * The reading engine has no password UI of its own — it prompts through
 * `window.prompt` and then embeds the answer in the book's metadata, which
 * lands in the `books` table. That row is synced to the cloud, so the
 * password travelled with it. This helper moves it into the local-only store
 * instead and returns the cleaned description.
 *
 * Returns the cleaned description; the password is stored as a side effect
 * (a storage failure keeps the password in place rather than losing it).
 */
export function migratePdfPasswordOutOfDescription(
  bookKey: string,
  description: string | undefined | null
): string {
  const password = extractPdfPasswordFromDescription(description);
  if (!password) {
    return description || "";
  }
  const cleaned = stripPdfPasswordFromDescription(description);
  try {
    rememberPdfPassword(bookKey, password);
    return cleaned;
  } catch {
    // Could not persist — keep the original description so the password is
    // not lost (it still works, it is just still in the synced field).
    return description || "";
  }
}
