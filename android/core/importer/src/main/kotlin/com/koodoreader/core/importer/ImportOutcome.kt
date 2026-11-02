package com.koodoreader.core.importer

sealed interface ImportOutcome {
    data class Success(val record: BookRecord, val duplicate: Boolean) : ImportOutcome
    data class Failure(val reason: ImportFailure) : ImportOutcome
}

sealed interface ImportFailure {
    data class Unsupported(val reason: String) : ImportFailure
    data object Unknown : ImportFailure
    data class InsufficientSpace(val required: Long, val available: Long) : ImportFailure
    data class Io(val message: String) : ImportFailure
}
