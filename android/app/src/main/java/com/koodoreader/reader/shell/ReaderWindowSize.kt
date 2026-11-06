package com.koodoreader.reader.shell

/** Reader window size classes; 600 dp is the phone/large-screen boundary. */
enum class ReaderWindowSize {
    COMPACT,
    EXPANDED,
    ;

    companion object {
        const val EXPANDED_MIN_DP = 600

        fun fromWidth(widthDp: Int): ReaderWindowSize =
            if (widthDp >= EXPANDED_MIN_DP) EXPANDED else COMPACT
    }
}
