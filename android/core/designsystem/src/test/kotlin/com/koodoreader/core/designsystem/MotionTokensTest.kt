package com.koodoreader.core.designsystem

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionTokensTest {

    /**
     * Regression: the double-back acceptance window used to be 2000ms while
     * the exit hint's SnackbarDuration.Short stays visible for ~4000ms, so a
     * second back arriving while the prompt was still on screen fell outside
     * the window and re-queued the hint instead of exiting — the user saw the
     * prompt again on every swipe and only two quick swipes in a row exited.
     * The window must cover the prompt's full visible time.
     */
    @Test
    fun `back-exit window covers the hint snackbar visible duration`() {
        assertTrue(
            MotionTokens.BACK_EXIT >= SNACKBAR_SHORT_VISIBLE_MS,
            "BACK_EXIT=${MotionTokens.BACK_EXIT} must be >= $SNACKBAR_SHORT_VISIBLE_MS",
        )
    }

    @Test
    fun `duration scale stays ascending`() {
        assertTrue(
            MotionTokens.ALL.zipWithNext().all { (a, b) -> a < b },
            MotionTokens.ALL.toString(),
        )
    }

    private companion object {
        /** Visible time of androidx.compose.material3.SnackbarDuration.Short. */
        const val SNACKBAR_SHORT_VISIBLE_MS = 4000
    }
}
