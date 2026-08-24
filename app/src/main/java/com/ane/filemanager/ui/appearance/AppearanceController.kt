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
    var textSp: Int = prefs.getInt("textSp", 16).coerceIn(TEXT_SIZE_MIN_SP, TEXT_SIZE_MAX_SP)
        private set
    var iconDp: Int = prefs.getInt("iconDp", 34).coerceIn(ICON_SIZE_MIN_DP, ICON_SIZE_MAX_DP)
        private set
    var spacingDp: Int = prefs.getInt("spacingDp", 8).coerceIn(SPACING_MIN_DP, SPACING_MAX_DP)
        private set

    fun snapshot() = AppearanceSettings(layoutMode, dark, textSp, iconDp, spacingDp)

    fun setLayoutMode(value: LayoutMode) {
        if (layoutMode == value) return
        layoutMode = value
        prefs.edit().putString("layout", if (layoutMode == LayoutMode.GRID) "grid" else "list").apply()
    }

    fun setDark(value: Boolean) {
        if (dark == value) return
        dark = value
        prefs.edit().putBoolean("dark", dark).apply()
    }

    fun setTextSize(value: Int) {
        val safe = value.coerceIn(TEXT_SIZE_MIN_SP, TEXT_SIZE_MAX_SP)
        if (textSp == safe) return
        textSp = safe
        prefs.edit().putInt("textSp", textSp).apply()
    }

    fun setIconSize(value: Int) {
        val safe = value.coerceIn(ICON_SIZE_MIN_DP, ICON_SIZE_MAX_DP)
        if (iconDp == safe) return
        iconDp = safe
        prefs.edit().putInt("iconDp", iconDp).apply()
    }

    fun setSpacing(value: Int) {
        val safe = value.coerceIn(SPACING_MIN_DP, SPACING_MAX_DP)
        if (spacingDp == safe) return
        spacingDp = safe
        prefs.edit().putInt("spacingDp", spacingDp).apply()
    }

    fun setShowHidden(value: Boolean) {
        if (showHidden == value) return
        showHidden = value
        prefs.edit().putBoolean("showHidden", showHidden).apply()
    }

    companion object {
        const val TEXT_SIZE_MIN_SP = 12
        const val TEXT_SIZE_MAX_SP = 24
        const val ICON_SIZE_MIN_DP = 24
        const val ICON_SIZE_MAX_DP = 56
        const val SPACING_MIN_DP = 0
        const val SPACING_MAX_DP = 24
    }
}
