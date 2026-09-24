package com.koodoreader.reader

import android.app.Application

/**
 * Application entry point. WebView shell host for the webview track; the
 * native track adds one startup hook: registering the device-side SQLite
 * engine (framework sqlite) for the desktop .db bridge — sqlite-jdbc's
 * natives don't exist on Android, so it must never run there.
 */
class KoodoReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidDesktopDb.install()
    }
}
