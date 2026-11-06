package com.koodoreader.reader.shell

import com.koodoreader.core.sync.LocalDirectorySyncTarget
import com.koodoreader.core.sync.S3SyncTarget
import com.koodoreader.core.sync.SyncTarget
import com.koodoreader.core.sync.WebDavSyncTarget
import java.io.File
import org.json.JSONObject

object SyncTransportFactory {

    private const val SNAPSHOT_NAME = "koodo-reader-sync.json"

    fun target(prefs: SyncPrefs): SyncTarget? = when (prefs.kind) {
        SyncPrefs.TargetKind.LOCAL -> {
            val path = prefs.localSnapshotPath.trim()
            if (path.isEmpty()) null else LocalDirectorySyncTarget(File(path, SNAPSHOT_NAME))
        }
        SyncPrefs.TargetKind.WEBDAV -> {
            val url = prefs.webDavUrl.trim()
            if (url.isEmpty()) null else WebDavSyncTarget(url, prefs.webDavUser, prefs.webDavPassword)
        }
        SyncPrefs.TargetKind.S3 -> {
            val url = prefs.s3Url.trim()
            if (url.isEmpty()) null else S3SyncTarget(url, parseHeaders(prefs.s3HeadersJson))
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap {
                o.keys().forEach { k -> put(k, o.optString(k, "")) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
