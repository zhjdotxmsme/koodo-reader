package com.koodoreader.engine.layout.css

data class CssRule(
    val selector: String,
    val declarations: Map<String, String>,
)
