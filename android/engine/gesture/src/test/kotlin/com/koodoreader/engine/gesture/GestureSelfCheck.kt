package com.koodoreader.engine.gesture

import kotlin.math.abs
import kotlin.system.exitProcess

fun main() {
    var failures = 0
    fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) { println("  OK  ") }
        else { println("  FAIL  "); failures++ }
    }

    println("== FlingPhysics ==")
    val p = FlingPhysics()
    check("tau == 341.4ms", p.timeConstantMs == 341.4f)
    check("dist(1000) > 0", p.totalDistance(1000f) > 0f)
    check("dist(2000) > dist(1000)", p.totalDistance(2000f) > p.totalDistance(1000f))
    check("dist(0) == 0", p.totalDistance(0f) == 0f)
    check("dist(10) == 0", p.totalDistance(10f) == 0f)
    check("isFling(10) == false", !p.isFling(10f))
    check("isFling(50) == true", p.isFling(50f))
    val total = p.totalDistance(1000f)
    val d10tau = p.displacement(1000f, p.timeConstantMs * 10f)
    check("disp(10*tau) is close to total", abs(d10tau - total) < total * 0.01f)
    check("landing forward", p.predictLanding(0f, 1000f, 0f, 5000f) > 0f)
    check("landing backward", p.predictLanding(1000f, -1000f, 0f, 1000f) < 1000f)
    check("landing clamped", p.predictLanding(0f, 100000f, 0f, 100f) == 100f)

    println("")
    println("== OverscrollModel ==")
    val om = OverscrollModel()
    check("rubberband < raw", om.displacement(200f, 400f) < 200f)
    check("rubberband saturates", om.displacement(40000f, 400f) < 400f)
    check("monotonic", om.displacement(10f, 400f) < om.displacement(50f, 400f))

    println("")
    println("== TapZone ==")
    val rule = TapControlRule()
    check("left = PREV", resolveTapAction(10f, 10f, 300f, 600f, rule) == TapAction.PREV_PAGE)
    check("right = NEXT", resolveTapAction(290f, 10f, 300f, 600f, rule) == TapAction.NEXT_PAGE)
    check("center = NONE", resolveTapAction(150f, 300f, 300f, 600f, rule) == TapAction.NONE)

    println("")
    println("== GestureEngine ==")
    val e = GestureEngine(ReaderConfig(
        viewportWidthPx = 400f, viewportHeightPx = 700f, totalPages = 10,
        mode = GestureMode.PAGE_TURN,
    ))
    check("tap left = PREV", e.onTap(10f, 350f) == TapAction.PREV_PAGE)
    check("tap right = NEXT", e.onTap(390f, 350f) == TapAction.NEXT_PAGE)
    e.setCurrentPage(4)
    val r1 = e.onTouchUp(50f, 350f, velocityX = -3000f, velocityY = 0f, 100L)
    check("forward fling = PageTurn", r1 is GestureResult.PageTurn)
    val r2 = e.onTouchUp(200f, 350f, velocityX = 0f, velocityY = 0f, 50L)
    check("no motion = NoOp", r2 is GestureResult.NoOp)

    if (failures > 0) {
        println("")
        println("FAIL  check(s)")
        exitProcess(1)
    }
    println("")
    println("PASS: all self-checks passed")
}