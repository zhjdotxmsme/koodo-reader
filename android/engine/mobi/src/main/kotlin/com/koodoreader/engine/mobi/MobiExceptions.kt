package com.koodoreader.engine.mobi

/**
 * Typed failures for the native MOBI/AZW/AZW3 engine (P4).
 *
 * The public entry point [MobiParser.parse] degrades to `null` by default so a
 * single bad book can never crash the import/reader pipeline; passing
 * `strict = true` (or calling [MobiParser.parseOrThrow]) surfaces these types
 * instead, which is what the unit tests exercise.
 */
open class KoodoMobiException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The container is not a parsable PalmDB / MOBI file: wrong type+creator,
 * truncated header, record table pointing outside the file, missing MOBI
 * header, a text record that will not decompress, …
 */
class MalformedMobiException(message: String, cause: Throwable? = null) :
    KoodoMobiException(message, cause)

/** `encryptionType != 0` — DRM protected, the text records are encrypted. */
class DrmProtectedException(val encryptionType: Int) :
    KoodoMobiException("MobiCapture: DRM protected MOBI (encryptionType=$encryptionType)")

/**
 * Known-but-unimplemented compression, i.e. HUFF/CDIC (17480). PalmDOC (2) and
 * uncompressed (1) are implemented.
 */
class UnsupportedCompressionException(val type: Int) :
    KoodoMobiException(
        if (type == MobiCompression.HUFF_CDIC) {
            "MobiCapture: HUFF/CDIC compressed MOBI is not supported yet (type=$type)"
        } else {
            "MobiCapture: unknown MOBI compression type $type"
        }
    )
