package com.koodoreader.feature.stats

/**
 * Read-only annotation events for stats aggregation (Room adapter lives in :app).
 */
fun interface AnnotationStatsStore {
    suspend fun events(): List<AnnotationEvent>
}
