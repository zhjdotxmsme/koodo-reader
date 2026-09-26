package com.koodoreader.core.archive

/**
 * Typed archive errors — callers NEVER see a raw java.util.zip.IOException
 * (mirrors the "永不抛 / parseOrThrow 类型化异常" convention of engine/mobi).
 */
open class ArchiveException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** [file] exists but is not a readable zip (bad magic, truncated, corrupt central directory). */
class ArchiveOpenException(file: String, cause: Throwable? = null) :
    ArchiveException("not a readable zip archive: $file", cause)

/** Entry [name] is absent — exact AND case-insensitive lookup both missed. */
class ArchiveEntryNotFoundException(name: String) :
    ArchiveException("archive entry not found: $name")
