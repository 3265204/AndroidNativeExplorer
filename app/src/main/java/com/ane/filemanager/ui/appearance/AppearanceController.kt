package com.ane.filemanager.ui.appearance

import android.content.Context
import com.ane.filemanager.ui.model.AppearanceSettings
import com.ane.filemanager.ui.model.LayoutMode

/** Owns persisted presentation preferences; it does not draw or navigate. */
internal class AppearanceController(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var showHidden: Boolean = prefs.getBoolean("showHidden", false)
        private set
    var layoutMode: LayoutMode = if (prefs.getString("layout", "list") == "grid") {
        LayoutMode.GRID
    } else {
        LayoutMode.LIST
    }
        private set
    var dark: Boolean = prefs.getBoolean("dark", false)
        private set
    var textSp: Int = prefs.getInt("textSp", 16)
        private set
    var iconDp: Int = prefs.getInt("iconDp", 34)
        private set
    var spacingDp: Int = prefs.getInt("spacingDp", 8)
        private set

    fun snapshot() = AppearanceSettings(layoutMode, dark, textSp, iconDp, spacingDp)

    fun toggleLayout() {
        layoutMode = if (layoutMode == LayoutMode.LIST) LayoutMode.GRID else LayoutMode.LIST
        prefs.edit().putString("layout", if (layoutMode == LayoutMode.GRID) "grid" else "list").apply()
    }

    fun toggleTheme() {
        dark = !dark
        prefs.edit().putBoolean("dark", dark).apply()
    }

    fun cycleTextSize() {
        textSp = nextOf(textSp, intArrayOf(14, 16, 18, 20))
        prefs.edit().putInt("textSp", textSp).apply()
    }

    fun cycleIconSize() {
        iconDp = nextOf(iconDp, intArrayOf(28, 34, 42, 50))
        prefs.edit().putInt("iconDp", iconDp).apply()
    }

    fun cycleSpacing() {
        spacingDp = nextOf(spacingDp, intArrayOf(4, 8, 12, 16))
        prefs.edit().putInt("spacingDp", spacingDp).apply()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.edit().putBoolean("showHidden", showHidden).apply()
    }

    private fun nextOf(current: Int, values: IntArray): Int {
        val index = values.indexOf(current)
        return values[(if (index < 0) 0 else index + 1) % values.size]
    }
}
