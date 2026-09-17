package com.koodoreader.reader

import android.app.Application

/**
 * Application entry point. Intentionally minimal: the host is a WebView shell
 * and the real work happens in the bundled web build (assets/webapp). Kept as a
 * hook for future native initialisation (logging, crash hooks, OTA checks).
 */
class KoodoReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
