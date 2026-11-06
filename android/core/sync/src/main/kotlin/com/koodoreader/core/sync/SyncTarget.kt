package com.koodoreader.core.sync

import java.io.InputStream

/** Transport boundary for a single sync snapshot blob (local file, WebDAV, S3, …). */
interface SyncTarget {
    fun read(): InputStream?

    fun write(snapshot: ByteArray)
}
