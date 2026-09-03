package com.ane.filemanager.ui.appearance

import android.content.Context
import android.content.res.Configuration
import com.ane.filemanager.ui.model.AppearanceSettings
import com.ane.filemanager.ui.model.LayoutMode

internal enum class ThemeMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark")
}

internal fun resolveThemeMode(stored: String?, legacyDark: Boolean?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.preferenceValue == stored }
        ?: legacyDark?.let { if (it) ThemeMode.DARK else ThemeMode.LIGHT }
        ?: ThemeMode.SYSTEM

/** Owns persisted presentation preferences; it does not draw or navigate. */
internal class AppearanceController(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val appContext = context

    var showHidden: Boolean = prefs.getBoolean("showHidden", false)
        private set
    var layoutMode: LayoutMode = if (prefs.getString(KEY_LAYOUT, "list") == "grid") {
        LayoutMode.GRID
    } else {
        LayoutMode.LIST
    }
        private set
    var themeMode: ThemeMode = resolveThemeMode(
        prefs.getString(KEY_THEME_MODE, null),
        prefs.getBoolean(KEY_LEGACY_DARK, false).takeIf { prefs.contains(KEY_LEGACY_DARK) }
    )
        private set
    var dark: Boolean = resolveDark(themeMode)
        private set
    var textSp: Int = prefs.getInt("textSp", 16).coerceIn(TEXT_SIZE_MIN_SP, TEXT_SIZE_MAX_SP)
        private set
    var iconDp: Int = prefs.getInt("iconDp", 34).coerceIn(ICON_SIZE_MIN_DP, ICON_SIZE_MAX_DP)
        private set
    var spacingDp: Int = prefs.getInt("spacingDp", 8).coerceIn(SPACING_MIN_DP, SPACING_MAX_DP)
        private set

    fun snapshot() = AppearanceSettings(layoutMode, dark, textSp, iconDp, spacingDp)

    /** Applies a temporary layout preview without changing the persisted preference. */
    fun previewLayoutMode(value: LayoutMode) {
        layoutMode = value
    }

    fun setLayoutMode(value: LayoutMode) {
        layoutMode = value
        // Persist even when the explicit choice matches the default. Onboarding should record
        // the user's decision rather than relying on whatever the default happens to be.
        prefs.edit().putString(KEY_LAYOUT, if (layoutMode == LayoutMode.GRID) "grid" else "list").apply()
    }

    fun setThemeMode(value: ThemeMode) {
        if (themeMode == value) return
        themeMode = value
        dark = resolveDark(value)
        prefs.edit()
            .putString(KEY_THEME_MODE, value.preferenceValue)
            .putBoolean(KEY_LEGACY_DARK, dark)
            .apply()
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

    private fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.SYSTEM -> appContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    companion object {
        private const val KEY_LAYOUT = "layout"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_LEGACY_DARK = "dark"
        const val TEXT_SIZE_MIN_SP = 12
        const val TEXT_SIZE_MAX_SP = 24
        const val ICON_SIZE_MIN_DP = 24
        const val ICON_SIZE_MAX_DP = 56
        const val SPACING_MIN_DP = 0
        const val SPACING_MAX_DP = 24
    }
}
