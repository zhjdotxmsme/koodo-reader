package com.koodoreader.reader.shell

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.koodoreader.core.data.KoodoDatabaseProvider
import com.koodoreader.core.data.entity.BookEntity
import com.koodoreader.core.importer.BookRecord
import com.koodoreader.core.importer.BookSource
import com.koodoreader.core.importer.ImportFailure
import com.koodoreader.core.importer.ImportOutcome
import com.koodoreader.core.importer.ImportPipeline
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface ExternalOpenState {
    data object Importing : ExternalOpenState
    data class Open(val bookKey: String) : ExternalOpenState
    data class Failed(val message: String) : ExternalOpenState
}

class ExternalBookOpener(private val app: Application) {

    fun open(uri: Uri): Flow<ExternalOpenState> = open {
        try {
            import(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportOutcome.Failure(
                ImportFailure.Io(e.message?.take(200) ?: e::class.simpleName ?: "error"),
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun import(uri: Uri): ImportOutcome {
        val resolver = app.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
        val source = BookSource(
            name = name,
            openStream = {
                resolver.openInputStream(uri) ?: throw IOException("Cannot read $name")
            },
            mime = runCatching { resolver.getType(uri) }.getOrNull(),
        )
        val dao = KoodoDatabaseProvider.get(app).bookDao()
        val booksDir = File(app.filesDir, "books")
        return importBook(
            source = source,
            booksDir = booksDir,
            existingBook = { md5 -> dao.getByMd5(md5).firstOrNull()?.toRecord() },
            onNewBook = { record -> dao.upsert(record.toEntity()) },
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        internal suspend fun importBook(
            source: BookSource,
            booksDir: File,
            existingBook: suspend (String) -> BookRecord? = { null },
            onNewBook: suspend (BookRecord) -> Unit = {},
        ): ImportOutcome {
            val outcome = ImportPipeline().importBook(source, booksDir, existingBook)
            if (outcome is ImportOutcome.Success && !outcome.duplicate) {
                try {
                    onNewBook(outcome.record)
                } catch (e: Exception) {
                    outcome.record.path?.let { path ->
                        val file = File(path)
                        if (file.exists() && !file.delete()) {
                            e.addSuppressed(IOException("Cannot remove uncommitted book: $path"))
                        }
                    }
                    throw e
                }
            }
            return outcome
        }

        internal fun open(importBook: suspend () -> ImportOutcome): Flow<ExternalOpenState> = flow {
            emit(ExternalOpenState.Importing)
            emit(stateAfter(importBook()))
        }

        fun stateAfter(result: ImportOutcome): ExternalOpenState = when (result) {
            is ImportOutcome.Success -> ExternalOpenState.Open(result.record.key)
            is ImportOutcome.Failure -> ExternalOpenState.Failed(result.reason.userMessage)
        }
    }
}

private val ImportFailure.userMessage: String
    get() = when (this) {
        is ImportFailure.Unsupported -> "Unsupported book format"
        ImportFailure.Unknown -> "Unknown book format"
        is ImportFailure.InsufficientSpace -> "Not enough storage to import this book"
        is ImportFailure.Io -> "Could not import book: $message"
    }

private fun BookRecord.toEntity(): BookEntity = BookEntity(
    key = key,
    name = name,
    author = author,
    description = description,
    md5 = md5,
    cover = cover,
    format = format,
    publisher = publisher,
    size = size,
    page = page,
    path = path,
    charset = charset,
)

private fun BookEntity.toRecord(): BookRecord = BookRecord(
    key = key,
    name = name,
    author = author,
    description = description,
    md5 = checkNotNull(md5) { "MD5 query returned a row without MD5" },
    cover = cover,
    format = format,
    publisher = publisher,
    size = size,
    page = page,
    path = path,
    charset = charset,
)
