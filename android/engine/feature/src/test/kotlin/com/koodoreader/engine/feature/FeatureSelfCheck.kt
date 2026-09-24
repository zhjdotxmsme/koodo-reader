package com.koodoreader.engine.feature

import kotlin.system.exitProcess

/**
 * Standalone smoke check, mirroring `:engine:gesture`'s `GestureSelfCheck`.
 *
 * Run with `gradle :engine:feature:test --tests '*FeatureSelfCheck*'` is NOT how
 * this works — it is a plain `main()` so it can also be executed from an IDE
 * without the JUnit platform. The real assertions live in the `*Test` classes.
 */
fun main() {
    var failures = 0
    fun check(label: String, condition: Boolean) {
        if (condition) {
            println("  OK    $label")
        } else {
            println("  FAIL  $label")
            failures++
        }
    }

    println("== BionicReading ==")
    val runs = BionicReading.split("hello world")
    check("4 runs for 'hello world'", runs.size == 4)
    check("prefix emphasised", runs[0] == BionicRun("hel", true))
    check(
        "lossless",
        listOf("", "a", "hello world", "\u4f60\u597d\u3002").all { sample ->
            BionicReading.split(sample).joinToString("") { it.text } == sample
        },
    )

    println("")
    println("== TextRuleEngine ==")
    val engine = TextRuleEngine()
    check(
        "plain replace",
        engine.apply("a b a", listOf(TextRule("1", "a", "c"))).text == "c b c",
    )
    check(
        "regex groups",
        engine.apply(
            "2024-01",
            listOf(TextRule("2", "(\\d+)-(\\d+)", "\$2/\$1", isRegex = true)),
        ).text == "01/2024",
    )
    check(
        "invalid regex warned",
        engine.apply("x", listOf(TextRule("3", "[", isRegex = true))).warningCount == 1,
    )

    println("")
    println("== TextRuleJson ==")
    val rules = listOf(TextRule("1", "a", "b"), TextRule("2", "c", "d", isRegex = true))
    check("array round trip", TextRuleJson.importRules(TextRuleJson.exportRules(rules)).rules == rules)
    check("config round trip", TextRuleJson.importConfig(TextRuleJson.exportConfig(rules)).rules == rules)

    println("")
    println("== SpeedReading ==")
    val chunks = SpeedReading().chunks("hello world")
    check("2 chunks", chunks.size == 2)
    check("200ms dwell", chunks.all { it.durationMs == 200 })
    check("sentence pause", SpeedReading().durationFor("end.") == 400)

    println("")
    println("== ParagraphMode ==")
    val paragraphs = ParagraphMode.split("One\n\nTwo")
    check("2 paragraphs", paragraphs == listOf("One", "Two"))
    val mode = ParagraphMode(paragraphs)
    check("next works", mode.next() && mode.current == "Two")
    check("stops at end", !mode.next())

    println("")
    println("== ReadingRuler ==")
    val area = ReadingRuler.resolve(0f, 600f)
    check("centred", area.centerY == 300f && area.height == 72f)
    check("hit test", area.contains(300f) && !area.contains(100f))

    println("")
    println("== SelectionAutoTurn ==")
    val next = SelectionAutoTurn.decide(
        SelectionAutoTurnInput(200f, 780f, 400f, 800f, 700f, 780f),
    )
    check("bottom edge turns next", next.turn && next.direction == TurnDirection.NEXT)
    val idle = SelectionAutoTurn.decide(
        SelectionAutoTurnInput(200f, 400f, 400f, 800f, 300f, 400f),
    )
    check("centre is idle", !idle.turn)

    println("")
    if (failures == 0) {
        println("ALL CHECKS PASSED")
    } else {
        println("$failures CHECK(S) FAILED")
        exitProcess(1)
    }
}
