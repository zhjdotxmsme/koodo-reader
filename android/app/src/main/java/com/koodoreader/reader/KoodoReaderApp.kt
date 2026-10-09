package com.koodoreader.reader

import android.app.Application
import com.koodoreader.feature.crash.CrashMonitoring
import com.koodoreader.reader.zhconvert.ZhConvertBridge

/**
 * Application entry point. WebView shell host for the webview track; the
 * native track adds startup hooks:
 *  - registering the device-side SQLite engine (framework sqlite) for the
 *    desktop .db bridge — sqlite-jdbc's natives don't exist on Android, so it
 *    must never run there;
 *  - the P6 简繁转换 bridge: binds the pure-JVM `:core:locale`
 *    ZhConvertSettingsStore port to DataStore and installs the layout-pipeline
 *    text transform hook (docs/p6-zh-locale-design.md §4.1).
 */
class KoodoReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogWriter.install(this)
        AndroidDesktopDb.install()
        ZhConvertBridge.install(this)
        // Process-wide uncaught-exception handler + breadcrumb ring (redaction-only,
        // NoopCrashBackend by default — no data leaves the device until a real backend
        // is attached with user consent; see :feature:crash CrashMonitoring docs).
        CrashMonitoring.install()
        CrashMonitoring.breadcrumb("app", "onCreate")
    }
}
