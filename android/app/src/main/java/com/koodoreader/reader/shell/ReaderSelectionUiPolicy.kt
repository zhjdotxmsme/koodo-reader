package com.koodoreader.reader.shell

object ReaderSelectionUiPolicy {
    fun showMenu(menuOpen: Boolean, adjusting: Boolean): Boolean = menuOpen && !adjusting

    fun showHandles(menuOpen: Boolean, adjusting: Boolean, useFoliate: Boolean): Boolean =
        menuOpen && adjusting && useFoliate
}
