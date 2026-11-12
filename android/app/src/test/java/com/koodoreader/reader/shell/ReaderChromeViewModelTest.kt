package com.koodoreader.reader.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderChromeViewModelTest {
    private class FakeStore : ReaderChromePreferenceStore {
        var value = ReaderChromeUiState()
        var saves = 0
        override fun load() = value
        override fun save(state: ReaderChromeUiState) { value = state; saves++ }
    }

    @Test
    fun `changes expose stateflow and persist once`() {
        val store = FakeStore()
        val vm = ReaderChromeViewModel(store)
        vm.setFullScreen(true)
        vm.setFirstLineIndent(true)
        vm.setHorizontalMarginDp(24)
        assertEquals(ReaderChromeUiState(true, true, 24), vm.state.value)
        assertEquals(store.value, vm.state.value)
        assertEquals(3, store.saves)
    }

    @Test
    fun `invalid margin falls back to default`() {
        val store = FakeStore()
        store.value = store.value.copy(horizontalMarginDp = 13)
        val vm = ReaderChromeViewModel(store)
        vm.setHorizontalMarginDp(13)
        assertEquals(20, vm.state.value.horizontalMarginDp)
    }
}
