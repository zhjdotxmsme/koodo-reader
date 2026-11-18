package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.JsonValue

internal fun JsonValue.JsonObject.stringField(key: String): String? =
    (fields[key] as? JsonValue.JsonString)?.value

internal fun JsonValue.JsonObject.intField(key: String): Int? =
    when (val v = fields[key]) {
        is JsonValue.JsonNumber -> v.raw.toIntOrNull()
        is JsonValue.JsonString -> v.value.toIntOrNull()
        else -> null
    }

internal fun JsonValue.JsonObject.stringListField(key: String): List<String> {
    val raw = fields[key] ?: return emptyList()
    return when (raw) {
        is JsonValue.JsonArray ->
            raw.items.mapNotNull { (it as? JsonValue.JsonString)?.value }
        is JsonValue.JsonString -> listOf(raw.value)
        else -> emptyList()
    }
}