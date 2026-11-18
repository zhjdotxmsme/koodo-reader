package com.koodoreader.reader.metadata

import com.koodoreader.feature.translate.JsonValue
import com.koodoreader.feature.translate.MiniJson
import java.io.File
import java.security.MessageDigest

/** 磁盘缓存公开元数据检索结果，减轻豆瓣等平台频率压力。 */
object MetadataSearchCache {

    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    fun read(cacheDir: File?, request: MetadataSearchRequest): List<FetchedBookMetadata>? {
        if (cacheDir == null) return null
        val file = entryFile(cacheDir, request) ?: return null
        if (!file.isFile) return null
        val parsed = MiniJson.parse(file.readText()) as? JsonValue.JsonObject ?: return null
        val savedAt = (parsed.fields["t"] as? JsonValue.JsonNumber)?.raw?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > TTL_MS) {
            file.delete()
            return null
        }
        val items = parsed.fields["items"] as? JsonValue.JsonArray ?: return null
        return items.items.mapNotNull { decodeItem(it as? JsonValue.JsonObject) }
    }

    fun write(cacheDir: File?, request: MetadataSearchRequest, items: List<FetchedBookMetadata>) {
        if (cacheDir == null || items.isEmpty()) return
        cacheDir.mkdirs()
        val file = entryFile(cacheDir, request) ?: return
        file.writeText(encode(savedAt = System.currentTimeMillis(), items = items))
    }

    private fun entryFile(cacheDir: File, request: MetadataSearchRequest): File? {
        val key = cacheKey(request)
        return File(cacheDir, "$key.json")
    }

    internal fun cacheKey(request: MetadataSearchRequest): String {
        val raw = buildString {
            append(request.normalizedIsbn().orEmpty())
            append('|')
            append(request.title.trim().lowercase())
            append('|')
            append(request.author.trim().lowercase())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeItem(obj: JsonValue.JsonObject?): FetchedBookMetadata? {
        if (obj == null) return null
        val name = obj.stringField("name")?.trim().orEmpty()
        if (name.isEmpty()) return null
        return FetchedBookMetadata(
            name = name,
            author = obj.stringField("author").orEmpty(),
            publisher = obj.stringField("publisher").orEmpty(),
            description = obj.stringField("description").orEmpty(),
            coverUrl = obj.stringField("coverUrl").orEmpty(),
            isbn = obj.stringField("isbn").orEmpty(),
            source = obj.stringField("source").orEmpty(),
        )
    }

    private fun encode(savedAt: Long, items: List<FetchedBookMetadata>): String {
        val sb = StringBuilder()
        sb.append("{\"t\":").append(savedAt).append(",\"items\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append(jsonField("name", item.name)).append(',')
            sb.append(jsonField("author", item.author)).append(',')
            sb.append(jsonField("publisher", item.publisher)).append(',')
            sb.append(jsonField("description", item.description)).append(',')
            sb.append(jsonField("coverUrl", item.coverUrl)).append(',')
            sb.append(jsonField("isbn", item.isbn)).append(',')
            sb.append(jsonField("source", item.source))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun jsonField(name: String, value: String): String =
        "\"$name\":${MiniJson.write(value)}"
}