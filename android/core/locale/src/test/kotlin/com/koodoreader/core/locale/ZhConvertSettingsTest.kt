package com.koodoreader.core.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Conversion-dictionary persistence (P6 acceptance item ②): desktop-parity
 * Preferences keys, DataStore-shaped port, repository notifications and the
 * live engine hand-off.
 */
class ZhConvertSettingsTest {

    @Test
    @DisplayName("the mode is stored under the desktop reader-config key")
    fun `desktop key and wire values`() {
        assertEquals("convertChinese", ZhConvertPrefsCodec.KEY_MODE)
        assertEquals(listOf("convertChinese", "zhConvertUserDict", "zhConvertUserDictEnabled"), ZhConvertPrefsCodec.KEYS)

        val encoded = ZhConvertPrefsCodec.encode(ZhConvertSettings(mode = ZhConvertMode.TRADITIONAL))
        assertEquals("Simplified To Traditional", encoded[ZhConvertPrefsCodec.KEY_MODE])
        assertEquals("false", encoded[ZhConvertPrefsCodec.KEY_USER_DICT_ENABLED])
        assertEquals("", encoded[ZhConvertPrefsCodec.KEY_USER_DICT])
    }

    @Test
    @DisplayName("codec round-trips every field")
    fun `codec round trip`() {
        val settings = ZhConvertSettings(
            mode = ZhConvertMode.SIMPLIFIED,
            userDictionary = "计划\t計畫",
            userDictionaryEnabled = true,
        )
        val decoded = ZhConvertPrefsCodec.decode(ZhConvertPrefsCodec.encode(settings))
        assertEquals(settings, decoded)
        assertTrue(decoded.hasUserDictionary)
        assertEquals("Traditional To Simplified", ZhConvertPrefsCodec.toDesktopConfig(settings))
    }

    @Test
    @DisplayName("missing/unknown/blank values degrade to the desktop defaults")
    fun `decode tolerates unknown values`() {
        assertEquals(ZhConvertSettings.DEFAULT, ZhConvertPrefsCodec.decode(emptyMap()))
        assertEquals(ZhConvertMode.AUTO, ZhConvertPrefsCodec.decode(mapOf("convertChinese" to "")).mode)
        assertEquals(ZhConvertMode.AUTO, ZhConvertPrefsCodec.decode(mapOf("convertChinese" to "??")).mode)
        assertEquals("", ZhConvertPrefsCodec.decode(emptyMap()).userDictionary)
        assertFalse(ZhConvertPrefsCodec.decode(emptyMap()).userDictionaryEnabled)

        val enabled = ZhConvertPrefsCodec.decode(mapOf("zhConvertUserDictEnabled" to " TRUE "))
        assertTrue(enabled.userDictionaryEnabled)
        assertFalse(ZhConvertPrefsCodec.decode(mapOf("zhConvertUserDictEnabled" to "yes")).userDictionaryEnabled)
    }

    @Test
    @DisplayName("desktop reader config value maps both ways")
    fun `desktop interop`() {
        assertEquals(ZhConvertMode.TRADITIONAL, ZhConvertPrefsCodec.fromDesktopConfig("Simplified To Traditional").mode)
        assertEquals(ZhConvertMode.AUTO, ZhConvertPrefsCodec.fromDesktopConfig(null).mode)
        assertEquals("", ZhConvertPrefsCodec.toDesktopConfig(ZhConvertSettings.DEFAULT))
    }

    @Test
    @DisplayName("in-memory store writes, reads and clears")
    fun `store round trip`() {
        val store = InMemoryZhConvertSettingsStore()
        assertEquals(ZhConvertSettings.DEFAULT, store.read())

        val written = store.write(ZhConvertSettings(mode = ZhConvertMode.TRADITIONAL))
        assertEquals(ZhConvertMode.TRADITIONAL, written.mode)
        assertEquals(ZhConvertMode.TRADITIONAL, store.read().mode)
        assertEquals("Simplified To Traditional", store.raw()[ZhConvertPrefsCodec.KEY_MODE])

        store.clear()
        assertEquals(ZhConvertSettings.DEFAULT, store.read())
    }

    @Test
    @DisplayName("repository persists, notifies and hands out a live engine")
    fun `repository persists and notifies`() {
        val store = InMemoryZhConvertSettingsStore()
        val repository = ZhConvertSettingsRepository(store)
        val seen = ArrayList<ZhConvertMode>()
        val listener: (ZhConvertSettings) -> Unit = { seen.add(it.mode) }
        repository.addListener(listener)

        repository.setMode(ZhConvertMode.TRADITIONAL)
        assertEquals(ZhConvertMode.TRADITIONAL, store.read().mode)
        assertEquals("乾淨", repository.engine().toTraditional("干净"))

        repository.setUserDictionary("干净\t干净")
        assertEquals(listOf(ZhConvertMode.TRADITIONAL, ZhConvertMode.TRADITIONAL), seen)
        assertEquals("干净", repository.engine().toTraditional("干净")) // user rule wins

        repository.removeListener(listener)
        repository.setMode(ZhConvertMode.SIMPLIFIED)
        assertEquals(2, seen.size, "no notification after removeListener")
        assertEquals(ZhConvertMode.SIMPLIFIED, store.read().mode)

        repository.clear()
        assertEquals(ZhConvertSettings.DEFAULT, store.read())
        assertEquals("乾淨", repository.engine().toTraditional("干净"))
        assertEquals(ZhConvertMode.AUTO, repository.settings.mode)
    }

    @Test
    @DisplayName("the user dictionary is applied only while enabled")
    fun `user dictionary only when enabled`() {
        val repository = ZhConvertSettingsRepository()
        repository.setUserDictionary("计划\t計畫", enabled = true)
        assertEquals("計畫", repository.engine().toTraditional("计划"))

        repository.setUserDictionary("计划\t計畫", enabled = false)
        assertFalse(repository.settings.hasUserDictionary)
        assertEquals("計劃", repository.engine().toTraditional("计划"))
        assertEquals("计划\t計畫", repository.settings.userDictionary) // kept on disk

        // refresh() re-reads the store without losing the persisted dictionary
        repository.refresh()
        assertEquals("计划\t計畫", repository.settings.userDictionary)
        repository.setUserDictionary("计划\t計畫", enabled = true)
        assertEquals("計畫", repository.refresh().engine().toTraditional("计划"))
    }
}
