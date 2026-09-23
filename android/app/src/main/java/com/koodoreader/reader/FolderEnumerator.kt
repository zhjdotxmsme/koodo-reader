package com.koodoreader.reader

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** A regular file discovered under a SAF tree (directories are never returned). */
internal data class FolderFile(
    val name: String,
    val uri: Uri,
    val mime: String?,
    val size: Long?,
)

/**
 * Shared SAF directory enumerator used by BOTH tracks:
 *   - WebView track: `MainActivity.refreshFolderResult` (delivers the raw list
 *     to the page; filtering happens in folderBridge.js there), and
 *   - native track: `shell.LibraryViewModel.importFolder` (filtering happens
 *     in `com.koodoreader.core.importer.BookRules`).
 *
 * Depth/limit constants live in [com.koodoreader.core.importer.BookRules]
 * (Kotlin single source of truth, guarded by scripts/check-import-rules.js).
 */
internal object FolderEnumerator {

    /**
     * Enumerate regular files under the SAF tree (root + [depth]
     * sub-directory levels), capped at [limit]. Directories are never
     * returned; sub-directories are recursed while depth remains.
     */
    fun enumerate(
        resolver: ContentResolver,
        treeUri: Uri,
        depth: Int,
        limit: Int,
    ): List<FolderFile> {
        val files = mutableListOf<FolderFile>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        fun walk(docId: String, remainingDepth: Int) {
            if (files.size >= limit) return
            val authority = treeUri.authority ?: return
            val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, docId)
            val cursor = resolver.query(childrenUri, projection, null, null, null) ?: return
            cursor.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idCol < 0 || nameCol < 0) return@use
                while (c.moveToNext() && files.size < limit) {
                    val id = c.getString(idCol)
                    val name = c.getString(nameCol)
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) {
                        c.getString(sizeCol)?.toLongOrNull()
                    } else {
                        null
                    }
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDirectory) {
                        if (remainingDepth > 0) walk(id, remainingDepth - 1)
                    } else {
                        files.add(FolderFile(name, fileUri, mime, size))
                    }
                }
            }
        }

        walk(DocumentsContract.getDocumentId(treeUri), depth)
        return files
    }
}