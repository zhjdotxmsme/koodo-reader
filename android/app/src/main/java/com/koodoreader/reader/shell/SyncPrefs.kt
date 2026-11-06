package com.koodoreader.reader.shell

import android.content.Context

/** Persisted sync transport settings (local folder / WebDAV / S3 presigned URL). */
class SyncPrefs(context: Context) {
    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    enum class TargetKind { LOCAL, WEBDAV, S3 }

    var kind: TargetKind
        get() = TargetKind.entries.getOrElse(sp.getInt(KEY_KIND, 0)) { TargetKind.LOCAL }
        set(v) = sp.edit().putInt(KEY_KIND, v.ordinal).apply()

    var localSnapshotPath: String
        get() = sp.getString(KEY_LOCAL_PATH, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LOCAL_PATH, v).apply()

    var webDavUrl: String
        get() = sp.getString(KEY_WEBDAV_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBDAV_URL, v).apply()

    var webDavUser: String
        get() = sp.getString(KEY_WEBDAV_USER, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBDAV_USER, v).apply()

    var webDavPassword: String
        get() = sp.getString(KEY_WEBDAV_PASS, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBDAV_PASS, v).apply()

    var s3Url: String
        get() = sp.getString(KEY_S3_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_S3_URL, v).apply()

    var s3HeadersJson: String
        get() = sp.getString(KEY_S3_HEADERS, "").orEmpty()
        set(v) = sp.edit().putString(KEY_S3_HEADERS, v).apply()

    companion object {
        private const val NAME = "koodo_sync_prefs"
        private const val KEY_KIND = "kind"
        private const val KEY_LOCAL_PATH = "localPath"
        private const val KEY_WEBDAV_URL = "webdavUrl"
        private const val KEY_WEBDAV_USER = "webdavUser"
        private const val KEY_WEBDAV_PASS = "webdavPass"
        private const val KEY_S3_URL = "s3Url"
        private const val KEY_S3_HEADERS = "s3Headers"
    }
}
