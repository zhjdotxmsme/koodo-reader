package com.koodoreader.engine.feature

import kotlin.math.abs

/** Page-turn direction produced by the selection drag. */
enum class TurnDirection { NONE, NEXT, PREV }

/** Why a decision came out the way it did (telemetry / debugging aid). */
enum class TurnReason {
    /** Auto-turn disabled by config. */
    DISABLED,

    /** Viewport has no extent — cannot compute edge distances. */
    INVALID_VIEWPORT,

    /** Selection or pointer reached the trailing edge → next page. */
    EDGE_END,

    /** Selection or pointer reached the leading edge → previous page. */
    EDGE_START,

    /** Not near an edge, but the drag points towards the end of the text. */
    DRAG_TOWARD_END,

    /** Not near an edge, but the drag points towards the start of the text. */
    DRAG_TOWARD_START,

    /** Nothing to do. */
    NONE,
}

/**
 * Auto-turn tuning.
 *
 * @param enabled         master switch (desktop: selection auto-turn setting).
 * @param edgeThresholdPx distance to the viewport edge that arms a page turn.
 * @param minDragPx       minimum vertical drag that counts as a direction hint.
 */
data class SelectionAutoTurnConfig(
    val enabled: Boolean = true,
    val edgeThresholdPx: Float = 48f,
    val minDragPx: Float = 24f,
)

/**
 * One sample of the selection drag.
 *
 * @param pointerX        current pointer x in px.
 * @param pointerY        current pointer y in px (y grows downward).
 * @param viewportWidth   visible width in px.
 * @param viewportHeight  visible height in px.
 * @param selectionTop    top of the current selection in px.
 * @param selectionBottom bottom of the current selection in px.
 * @param dragDx          horizontal drag delta since the previous sample.
 * @param dragDy          vertical drag delta since the previous sample
 *                        (negative = dragging up / extending towards the end).
 */
data class SelectionAutoTurnInput(
    val pointerX: Float,
    val pointerY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val selectionTop: Float,
    val selectionBottom: Float,
    val dragDx: Float = 0f,
    val dragDy: Float = 0f,
)

/** Outcome of [SelectionAutoTurn.decide]. */
data class SelectionAutoTurnDecision(
    val turn: Boolean,
    val direction: TurnDirection,
    val reason: TurnReason,
) {
    companion object {
        val IDLE = SelectionAutoTurnDecision(false, TurnDirection.NONE, TurnReason.NONE)
    }
}

/**
 * "Selection auto turn" (选中文本自动翻页): while the user drags a selection
 * handle, decide whether the page should flip and in which direction.
 *
 * Priority order (deterministic, so it is trivially testable):
 *  1. disabled → no turn;
 *  2. degenerate viewport → no turn;
 *  3. pointer *or* selection end within `edgeThresholdPx` of the bottom edge →
 *     [TurnDirection.NEXT] (ties resolve towards the nearer edge);
 *  4. …of the top edge → [TurnDirection.PREV];
 *  5. otherwise the drag direction may still trigger a turn once it exceeds
 *     `minDragPx` — dragging up extends the selection towards the end of the
 *     text, so it turns forward, dragging down turns back;
 *  6. otherwise no turn.
 *
 * Thresholds are inclusive: a distance exactly equal to the threshold arms the
 * turn. Pure function — no state, no clock.
 *
 * Reference: kookit `selectionAutoTurn.ts` (docs/android-native-migration.md, P6).
 */
object SelectionAutoTurn {

    fun decide(
        input: SelectionAutoTurnInput,
        config: SelectionAutoTurnConfig = SelectionAutoTurnConfig(),
    ): SelectionAutoTurnDecision {
        if (!config.enabled) {
            return SelectionAutoTurnDecision(false, TurnDirection.NONE, TurnReason.DISABLED)
        }
        if (input.viewportWidth <= 0f || input.viewportHeight <= 0f) {
            return SelectionAutoTurnDecision(false, TurnDirection.NONE, TurnReason.INVALID_VIEWPORT)
        }

        val threshold = config.edgeThresholdPx.coerceAtLeast(0f)

        // The "active" end of the selection is whichever of the pointer and the
        // selection bottom sits lowest (dragging a handle is what extends it).
        val lowest = maxOf(input.pointerY, input.selectionBottom)
        val highest = minOf(input.pointerY, input.selectionTop)

        val bottomDistance = input.viewportHeight - lowest
        val topDistance = highest

        val bottomArmed = bottomDistance <= threshold
        val topArmed = topDistance <= threshold

        if (bottomArmed && (!topArmed || bottomDistance <= topDistance)) {
            return SelectionAutoTurnDecision(true, TurnDirection.NEXT, TurnReason.EDGE_END)
        }
        if (topArmed) {
            return SelectionAutoTurnDecision(true, TurnDirection.PREV, TurnReason.EDGE_START)
        }

        val dy = input.dragDy
        if (abs(dy) >= config.minDragPx && dy != 0f) {
            return if (dy < 0f) {
                SelectionAutoTurnDecision(true, TurnDirection.NEXT, TurnReason.DRAG_TOWARD_END)
            } else {
                SelectionAutoTurnDecision(true, TurnDirection.PREV, TurnReason.DRAG_TOWARD_START)
            }
        }

        return SelectionAutoTurnDecision.IDLE
    }

    /** Distance from [y] to the nearest viewport edge; `Float.MAX_VALUE` if degenerate. */
    fun edgeDistance(y: Float, viewportHeight: Float): Float {
        if (viewportHeight <= 0f) return Float.MAX_VALUE
        return minOf(y, viewportHeight - y)
    }
}
