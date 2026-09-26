package com.koodoreader.engine.htmlbook

import com.koodoreader.engine.layout.TextBlock
import java.util.Base64

/**
 * MHTML（MIME HTML，`.mht`/`.mhtml`）→ [TextBlock]（P5.5c，ADR-006）。
 *
 * MHTML 是 MIME `multipart/related` 容器：头部给 boundary，各 part 用
 * `Content-Type` / `Content-Transfer-Encoding` 描述。原生侧只取
 * **第一个 `text/html` part** 作为正文（图片等资源在纯文本阅读里不需要），
 * 因此这里是一个几十行的手写 MIME 拆包 + 传输编码解码，而不是引第三方
 * MIME 库（ADR-006「零外部运行时依赖」）。
 *
 * 字节口径：先用 Latin-1 做**保字节**扫描（boundary/头部），只有 html
 * part 的正文才按其 `charset` 解码——避免在边界扫描阶段破坏二进制。
 */
object MhtmlDocument {

    class MhtmlFormatException(message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    /** 一个 MIME part：小写化的头部表 + 解码后的正文字节。 */
    data class Part(val headers: Map<String, String>, val body: ByteArray) {
        val contentType: String get() = headers["content-type"].orEmpty()

        override fun equals(other: Any?): Boolean =
            other is Part && headers == other.headers && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * headers.hashCode() + body.contentHashCode()
    }

    /** MHTML 文档 → 块（供阅读器 session 消费）。 */
    fun blocks(mhtml: ByteArray, spineIndex: Int = 1): List<TextBlock> =
        HtmlDocument.blocks(html(mhtml), spineIndex)

    /** 取出正文 HTML 文本（按 part 的 charset 解码）。 */
    fun html(mhtml: ByteArray): String {
        val part = htmlPart(mhtml)
            ?: throw MhtmlFormatException("MHTML has no text/html part")
        return part
    }

    /** 取出正文 HTML part 的文本；无 html part 返回 null。 */
    fun htmlPart(mhtml: ByteArray): String? {
        val raw = String(mhtml, Charsets.ISO_8859_1)
        val boundary = boundaryOf(raw)
            ?: throw MhtmlFormatException("MHTML without a multipart boundary")
        val html = parts(raw, boundary).firstOrNull { it.contentType.startsWith("text/html") }
            ?: return null
        val charset = charsetOf(html.contentType) ?: "UTF-8"
        return com.koodoreader.engine.text.TextDecoder.decodeWith(charset, html.body)
    }

    /** 解析所有 part（保字节；正文按传输编码解码）。 */
    fun parts(mhtml: ByteArray): List<Part> {
        val raw = String(mhtml, Charsets.ISO_8859_1)
        val boundary = boundaryOf(raw)
            ?: throw MhtmlFormatException("MHTML without a multipart boundary")
        return parts(raw, boundary)
    }

    // ------------------------------------------------------------- internals

    /** `boundary=xxx` / `boundary="xxx"`（大小写不敏感）。 */
    private fun boundaryOf(raw: String): String? =
        Regex("""boundary\s*=\s*"?([^"\s;]+)"?""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() }

    private fun parts(raw: String, boundary: String): List<Part> {
        // 分隔符可带 CRLF 前缀；首个 part 前是 preamble（丢弃）。
        val marker = "--$boundary"
        val out = ArrayList<Part>()
        var idx = raw.indexOf(marker)
        if (idx < 0) throw MhtmlFormatException("MHTML boundary marker not found: $boundary")
        while (true) {
            idx += marker.length
            // 结束标记 "--boundary--"
            if (raw.startsWith("--", idx)) break
            // 跳过该行的剩余（CRLF）
            val bodyStart = raw.indexOf('\n', idx)
            if (bodyStart < 0) break
            val next = raw.indexOf(marker, bodyStart)
            val chunk = if (next < 0) raw.substring(bodyStart + 1) else raw.substring(bodyStart + 1, next)
            if (chunk.isNotBlank()) out.add(parsePart(chunk))
            if (next < 0) break
            idx = next
        }
        return out
    }

    private fun parsePart(chunk: String): Part {
        val headerEnd = findHeaderEnd(chunk)
        val hasBody = headerEnd.first >= 0
        val headerText = if (!hasBody) chunk else chunk.substring(0, headerEnd.second)
        val bodyText = if (!hasBody) "" else chunk.substring(headerEnd.first)
        val headers = HashMap<String, String>()
        var lastKey: String? = null
        for (line in headerText.split("\n")) {
            val clean = line.trimEnd('\r')
            if (clean.isEmpty()) continue
            if (clean[0] == ' ' || clean[0] == '\t') {
                // 折行续接
                lastKey?.let { headers[it] = (headers[it] ?: "") + " " + clean.trim() }
                continue
            }
            val colon = clean.indexOf(':')
            if (colon <= 0) continue
            val key = clean.substring(0, colon).trim().lowercase()
            headers[key] = clean.substring(colon + 1).trim()
            lastKey = key
        }
        val body = decodeBody(bodyText, headers["content-transfer-encoding"].orEmpty())
        return Part(headers, body)
    }

    /** 头体分隔（CRLFCRLF 或 LFLF）；返回 (bodyStart, headerEndExclusive)。 */
    private fun findHeaderEnd(chunk: String): Pair<Int, Int> {
        val crlf = chunk.indexOf("\r\n\r\n")
        val lf = chunk.indexOf("\n\n")
        return when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> (crlf + 4) to crlf
            lf >= 0 -> (lf + 2) to lf
            else -> -1 to -1
        }
    }

    private fun decodeBody(bodyText: String, encoding: String): ByteArray = when (encoding.lowercase()) {
        "base64" -> runCatching {
            Base64.getMimeDecoder().decode(bodyText.replace(Regex("\\s"), ""))
        }.getOrElse { bodyText.toByteArray(Charsets.ISO_8859_1) }

        "quoted-printable" -> decodeQuotedPrintable(bodyText)
        // 7bit / 8bit / binary / 空：原样字节
        else -> bodyText.toByteArray(Charsets.ISO_8859_1)
    }

    /** quoted-printable：`=XX` 十六进制字节 + `=` 软换行（行尾）。 */
    internal fun decodeQuotedPrintable(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '=' && i + 1 < text.length && (text[i + 1] == '\r' || text[i + 1] == '\n') -> {
                    // 软换行：吃掉 = 与随后的 CRLF/LF
                    i += if (text[i + 1] == '\r' && i + 2 < text.length && text[i + 2] == '\n') 3 else 2
                }
                c == '=' && i + 2 < text.length -> {
                    val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                    } else {
                        out.write(c.code)
                        i++
                    }
                }
                else -> {
                    out.write(c.code)
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String): String? =
        Regex("""charset\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentType)
            ?.groupValues?.get(1)
}