package com.koodoreader.engine.mobi

/**
 * Content sniffing for MOBI image records. MOBI stores raw image bytes with no
 * file names, so the format has to come from magic bytes.
 */
object ImageSniffer {

    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val GIF = "image/gif"
    const val BINARY = "application/octet-stream"

    /** `null` when the bytes are not a recognised image. */
    fun mediaType(bytes: ByteArray): String? = when {
        isJpeg(bytes) -> JPEG
        isPng(bytes) -> PNG
        isGif(bytes) -> GIF
        else -> null
    }

    fun isImage(bytes: ByteArray): Boolean = mediaType(bytes) != null

    fun extension(mediaType: String): String = when (mediaType) {
        JPEG -> "jpeg"
        PNG -> "png"
        GIF -> "gif"
        else -> "bin"
    }

    fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    fun isPng(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() &&
            b[7] == 0x0A.toByte()

    fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte() &&
            (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) &&
            b[5] == 'a'.code.toByte()

    /** Non-image record markers that live next to the images and must be skipped. */
    fun isAuxiliaryRecord(b: ByteArray): Boolean =
        startsWith(b, "FLIS") || startsWith(b, "FCIS") || startsWith(b, "FDST") ||
            startsWith(b, "DATP") || startsWith(b, "SRCS") || startsWith(b, "CMET") ||
            startsWith(b, "FONT") || startsWith(b, "RESC") || startsWith(b, "BOUNDARY")

    private fun startsWith(b: ByteArray, magic: String): Boolean {
        if (b.size < magic.length) return false
        var i = 0
        while (i < magic.length) {
            if (b[i] != magic[i].code.toByte()) return false
            i++
        }
        return true
    }
}
