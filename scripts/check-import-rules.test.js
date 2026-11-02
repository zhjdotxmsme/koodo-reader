'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');

function write(root, name, content) {
  const file = path.join(root, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

test('derived registry assignments are parsed before looking for literal calls', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'check-import-rules-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));

  const script = path.join(root, 'scripts', 'check-import-rules.js');
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.copyFileSync(path.join(__dirname, 'check-import-rules.js'), script);

  const importer = 'android/core/importer/src/main/kotlin/com/koodoreader/core/importer';
  write(root, `${importer}/BookRules.kt`, `
object BookRules {
    const val FOLDER_EVENT: String = "folder-picked"
    const val MAX_FILES: Int = 1000
    const val FOLDER_DEPTH: Int = 2
    val BOOK_EXTENSIONS: List<String> = BookFormat.entries.map { it.extension }
    val MIME_BY_EXT: Map<String, String> = BookFormat.entries.associate { it.extension to it.mime }
}
`);
  write(root, `${importer}/BookFormat.kt`, `
enum class BookFormat(val extension: String, val mime: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf");
}
`);
  write(root, 'src/utils/common.ts', 'export const supportedFormats = [".epub", ".pdf"];\n');
  write(root, 'src/utils/android/folderBridge.js', `
module.exports = {
  BOOK_EXTENSIONS: ['epub', 'pdf'],
  MIME_BY_EXT: { epub: 'application/epub+zip', pdf: 'application/pdf' },
  MAX_FILES: 1000,
  FOLDER_DEPTH: 2,
  FOLDER_EVENT: 'folder-picked',
};
`);

  const result = spawnSync(process.execPath, [script], { encoding: 'utf8' });
  assert.strictEqual(result.status, 0, result.stderr);
});
