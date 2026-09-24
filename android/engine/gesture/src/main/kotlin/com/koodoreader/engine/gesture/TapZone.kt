package com.koodoreader.engine.gesture

/**
 * 3×3 tap-zone grid, mirroring the desktop kookit `getTouchAction`.
 *
 * The viewport is divided into 3 columns × 3 rows (9 equal zones):
 *
 *   ┌─────────┬─────────┬─────────┐
 *   │  1      │  2      │  3      │
 *   ├─────────┼─────────┼─────────┤
 *   │  4      │  5      │  6      │
 *   ├─────────┼─────────┼─────────┤
 *   │  7      │  8      │  9      │
 *   └─────────┴─────────┴─────────┘
 *
 * Zone index = row × 3 + col + 1  (1-based, left-to-right, top-to-bottom).
 */
enum class TapZone(val x: Int, val y: Int, val index: Int) {
    TOP_LEFT       (0, 0, 1),
    TOP_CENTER     (1, 0, 2),
    TOP_RIGHT      (2, 0, 3),
    MID_LEFT       (0, 1, 4),
    CENTER         (1, 1, 5),
    MID_RIGHT      (2, 1, 6),
    BOTTOM_LEFT    (0, 2, 7),
    BOTTOM_CENTER  (1, 2, 8),
    BOTTOM_RIGHT   (2, 2, 9),
}

/**
 * A tap-control rule defines which screen zone triggers which action.
 *
 * Mirrors the desktop `touchControlRule` object shape:
 * ```json
 * {
 *   "touchControlA": "left",
 *   "touchControlB": "right",
 *   "touchControlC": "none",
 *   "layout": { "A": {"area": [1,2,3,4,5,6,7,8,9]},
 *               "B": {"area": []},
 *               "C": {"area": []} }
 * }
 * ```
 *
 * In the standard desktop config:
 *  - Zone 1-4 (left third of the screen) → **PREV_PAGE**
 *  - Zone 6-9 (right third)             → **NEXT_PAGE**
 *  - Zone 2,5,8 (center column)         → **CENTER** (no page turn)
 */
enum class TapAction {
    /** Page previous (tap left area). */
    PREV_PAGE,

    /** Page next (tap right area). */
    NEXT_PAGE,

    /** No page-turn action (center area — reserved for menu/selection). */
    NONE,
}

data class TapControlRule(
    val prevAction: TapAction = TapAction.PREV_PAGE,
    val nextAction: TapAction = TapAction.NEXT_PAGE,
    val zonePrev: Set<Int> = defaultPrevZones(),
    val zoneNext: Set<Int> = defaultNextZones(),
) {
    companion object {
        /** 1,2,3,4,7 (left + top-left + bottom-left columns) → prev page. */
        fun defaultPrevZones(): Set<Int> = setOf(1, 2, 3, 4, 7)

        /** 6,8,9 (right + top-right + bottom-right columns) → next page. */
        fun defaultNextZones(): Set<Int> = setOf(6, 8, 9)
    }

    /** [0,1] for prev, [2] for next, [-1] for none (matches kookit 0/1/-1). */
    fun pageTurnCode(action: TapAction): Int = when (action) {
        TapAction.PREV_PAGE -> 0
        TapAction.NEXT_PAGE -> 1
        TapAction.NONE      -> -1
    }
}

/**
 * Resolve which [TapAction] a tap at (tapX, tapY) triggers.
 *
 * @param tapX   Tap X coordinate in px (0…viewportWidth).
 * @param tapY   Tap Y coordinate in px (0…viewportHeight).
 * @param vpW    Viewport width in px.
 * @param vpH    Viewport height in px.
 * @param rule   The [TapControlRule] to apply.
 */
fun resolveTapAction(
    tapX: Float, tapY: Float,
    vpW: Float, vpH: Float,
    rule: TapControlRule = TapControlRule(),
): TapAction {
    if (vpW <= 0f || vpH <= 0f) return TapAction.NONE
    val col = ((tapX / vpW) * 3).toInt().coerceIn(0, 2)
    val row = ((tapY / vpH) * 3).toInt().coerceIn(0, 2)
    val zoneIndex = row * 3 + col + 1

    return when (zoneIndex) {
        in rule.zonePrev -> rule.prevAction
        in rule.zoneNext -> rule.nextAction
        else             -> TapAction.NONE
    }
}

/** Convenience: resolve and convert to a pageTurn direction code. */
fun tapPageTurnCode(
    tapX: Float, tapY: Float,
    vpW: Float, vpH: Float,
    rule: TapControlRule = TapControlRule(),
): Int {
    val action = resolveTapAction(tapX, tapY, vpW, vpH, rule)
    return rule.pageTurnCode(action)
}
