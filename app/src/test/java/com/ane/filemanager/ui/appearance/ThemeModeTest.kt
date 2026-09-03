package com.ane.filemanager.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun defaultsToSystemTheme() {
        assertEquals(ThemeMode.SYSTEM, resolveThemeMode(stored = null, legacyDark = null))
    }

    @Test
    fun preservesLegacyExplicitChoice() {
        assertEquals(ThemeMode.DARK, resolveThemeMode(stored = null, legacyDark = true))
        assertEquals(ThemeMode.LIGHT, resolveThemeMode(stored = null, legacyDark = false))
    }

    @Test
    fun storedThemeModeWinsOverLegacyValue() {
        assertEquals(ThemeMode.SYSTEM, resolveThemeMode(stored = "system", legacyDark = true))
        assertEquals(ThemeMode.LIGHT, resolveThemeMode(stored = "light", legacyDark = true))
        assertEquals(ThemeMode.DARK, resolveThemeMode(stored = "dark", legacyDark = false))
    }
}
