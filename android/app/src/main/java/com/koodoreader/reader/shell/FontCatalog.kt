package com.koodoreader.reader.shell

import androidx.annotation.FontRes
import com.koodoreader.reader.R

/**
 * Fonts bundled with the APK (res/font). These are an Android-native extra —
 * the desktop has no counterpart — so keys are native-only ("bundled:*").
 * The P2 reader settings UI lists them above system/custom fonts; selection
 * resolution goes through ResourcesCompat.getFont (works back to minSdk 24;
 * variable fonts render their default instance below API 26).
 *
 * Choice rationale: reading-grade typefaces under SIL OFL, size-budgeted —
 *  - LXGW WenKai Lite (霞鹜文楷)：中文阅读经典，13.2 MB
 *  - Inter：西文/正文，0.8 MB（可变字体）
 */
object FontCatalog {

    data class BundledFont(
        /** Stable key used by reader settings (persisted). */
        val key: String,
        /** Display name — shown as-is (font names are not translated). */
        val label: String,
        @FontRes val resId: Int,
    )

    val bundled: List<BundledFont> = listOf(
        BundledFont("bundled:lxgw_wenkai_lite", "霞鹜文楷", R.font.lxgw_wenkai_lite),
        BundledFont("bundled:inter", "Inter", R.font.inter_variable),
    )

    fun byKey(key: String): BundledFont? = bundled.firstOrNull { it.key == key }
}
