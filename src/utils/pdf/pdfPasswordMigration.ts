/**
 * One-time migration: move PDF passwords out of the synced `books.description`
 * field into the local-only store.
 *
 * The reading engine embeds a protected PDF's password into the book's
 * metadata (`"...\nprotected PDF: #<password>#"`), and that metadata lives in
 * the `books` table — a row that is uploaded to the cloud with every sync.
 * Books imported before the local store existed therefore still carry their
 * password in a synced field.
 *
 * This module walks the library once, moves each such password into the
 * local store and rewrites the description. It is intentionally conservative:
 * if the local store cannot be written, the description is left untouched so
 * the password is never lost.
 */

import Book from "../../models/Book";
import DatabaseService from "../storage/databaseService";
import { migratePdfPasswordOutOfDescription } from "../pdf/pdfPasswordUtil";

/** Result of one migration pass, for reporting to the caller. */
export interface PdfPasswordMigrationResult {
  /** How many books still carried a password marker. */
  scanned: number;
  /** How many books were rewritten (password moved to the local store). */
  migrated: number;
  /** Books that could not be migrated (storage failure) — password kept in place. */
  failed: number;
}

/**
 * Migrate every protected PDF in the library.
 *
 * Never throws: a failure on one book must not stop the sweep, and a failure
 * of the whole sweep is reported through the result object.
 */
export async function migrateLibraryPdfPasswords(): Promise<PdfPasswordMigrationResult> {
  const result: PdfPasswordMigrationResult = {
    scanned: 0,
    migrated: 0,
    failed: 0,
  };
  try {
    const keys = await DatabaseService.getAllRecordKeys("books");
    for (const key of keys || []) {
      let book: Book | null = null;
      try {
        book = await DatabaseService.getRecord(key, "books");
      } catch {
        continue;
      }
      if (!book || book.format !== "PDF" || !book.description) {
        continue;
      }
      if (!book.description.includes("protected PDF: #")) {
        continue;
      }
      result.scanned++;
      const cleaned = migratePdfPasswordOutOfDescription(key, book.description);
      if (cleaned === book.description) {
        // The local store refused the write — keep the password where it is.
        result.failed++;
        continue;
      }
      try {
        await DatabaseService.updateRecord(
          { ...book, description: cleaned },
          "books"
        );
        result.migrated++;
      } catch {
        result.failed++;
      }
    }
  } catch {
    // A failure to enumerate the library leaves the result as-is.
  }
  return result;
}
