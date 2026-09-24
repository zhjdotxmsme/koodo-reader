package com.koodoreader.engine.gesture

/**
 * Reader interaction mode, mirroring the desktop kookit `readerMode`.
 *
 * | kookit enum  | [GestureMode]  | description                          |
 * |--------------|----------------|--------------------------------------|
 * | `"single"`   | [PAGE_TURN]    | One column per page, horizontal nav |
 * | `"double"`   | [DOUBLE_PAGE]  | Two columns (desktop wide mode)     |
 * | `"scroll"`   | [SCROLL]       | Continuous vertical scrolling        |
 *
 * The Compose layer maps [mode] to the gesture geometry:
 *  - [PAGE_TURN]   → horizontal drag, snap to page boundaries
 *  - [DOUBLE_PAGE] → horizontal drag, snap to 2-page spreads
 *  - [SCROLL]      → vertical drag, continuous scroll offset
 */
enum class GestureMode {
    /** Single-column page turning (the phone default). */
    PAGE_TURN,

    /** Two-page spread (desktop wide mode, `column-count: 2`). */
    DOUBLE_PAGE,

    /** Continuous vertical scroll (mobile reading style). */
    SCROLL,
}
