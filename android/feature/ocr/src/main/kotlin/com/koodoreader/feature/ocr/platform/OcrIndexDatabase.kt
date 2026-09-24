// feature/ocr — Android-only persistence host for the OCR page index.
//
// Own private database today (same reasoning as feature/stats): :core:data owns
// `koodo.db`, and registering a table from a feature module there would need
// :core:data → :feature:ocr (a dependency cycle) or a main-thread schema bump.
// docs/p6-stats-ocr-design.md §4.2 contains the exact patch for the latter.
package com.koodoreader.feature.ocr.platform

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.koodoreader.feature.ocr.InMemoryOcrIndexStore
import com.koodoreader.feature.ocr.InMemoryOnDemandDownloader
import com.koodoreader.feature.ocr.OcrEngine
import com.koodoreader.feature.ocr.OcrIndexStore
import com.koodoreader.feature.ocr.OcrPageDao
import com.koodoreader.feature.ocr.OcrPageEntity
import com.koodoreader.feature.ocr.OcrSearchRepository
import com.koodoreader.feature.ocr.OnDemandDownloader
import com.koodoreader.feature.ocr.RoomOcrIndexStore

@Database(
    entities = [OcrPageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OcrIndexDatabase : RoomDatabase() {
    abstract fun ocrPageDao(): OcrPageDao

    companion object {
        const val NAME = "koodo-ocr-index.db"

        @Volatile
        private var instance: OcrIndexDatabase? = null

        fun get(context: Context): OcrIndexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OcrIndexDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}

/**
 * Wires the pure loop to Android. The repository is the object the reader holds
 * for the lifetime of a scanned document: index pages while the user reads,
 * search the index afterwards.
 */
object OcrWiring {

    fun store(context: Context): OcrIndexStore =
        RoomOcrIndexStore(OcrIndexDatabase.get(context).ocrPageDao())

    fun downloader(context: Context): OnDemandDownloader =
        MlKitModelDownloader(context)

    /**
     * @param persistentIndex false keeps the index in memory only (used by the
     *        "do not store OCR text" privacy switch and by tests/previews).
     */
    fun repository(
        context: Context,
        engine: OcrEngine = MlKitOcrProvider(),
        persistentIndex: Boolean = true,
        usePlayServices: Boolean = true,
    ): OcrSearchRepository = OcrSearchRepository(
        engine = engine,
        store = if (persistentIndex) store(context) else InMemoryOcrIndexStore(),
        downloader = if (usePlayServices) downloader(context) else InMemoryOnDemandDownloader(),
    )
}
