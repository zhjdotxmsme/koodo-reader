package com.koodoreader.reader.reader

/** Drag-selection anchors in chapter-relative character offsets. */
data class SelectionState(val start: Int = -1, val end: Int = -1, val active: Boolean = false)

/**
 * Selection handle state machine. Handles may be dragged in either direction;
 * [finish] normalises start/end so stored annotations always map forward
 * ranges onto the original text offsets.
 */
class SelectionController {
    var state = SelectionState()
        private set

    fun start(offset: Int) = apply { state = SelectionState(offset, offset, true) }

    fun moveStart(offset: Int) = apply { state = state.copy(start = offset) }

    fun moveEnd(offset: Int) = apply { state = state.copy(end = offset) }

    fun finish(): SelectionState {
        val current = state
        val normalised = current.copy(
            start = minOf(current.start, current.end),
            end = maxOf(current.start, current.end),
            active = false,
        )
        state = normalised
        return normalised
    }

    fun cancel() {
        state = SelectionState()
    }
}
