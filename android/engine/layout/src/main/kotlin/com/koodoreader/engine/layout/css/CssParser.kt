package com.koodoreader.engine.layout.css

object CssParser {

    private val blockPattern = Regex("""([^{}]+)\{([^{}]*)}""")

    fun parse(text: String): List<CssRule> = buildList {
        for (match in blockPattern.findAll(text)) {
            val declarations = match.groupValues[2].split(';').mapNotNull { declaration ->
                val colon = declaration.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                val key = declaration.substring(0, colon).trim().lowercase()
                val value = declaration.substring(colon + 1).trim()
                if (key.isEmpty() || value.isEmpty()) return@mapNotNull null
                key to value
            }.toMap()
            if (declarations.isNotEmpty()) {
                add(CssRule(match.groupValues[1].trim(), declarations))
            }
        }
    }
}
