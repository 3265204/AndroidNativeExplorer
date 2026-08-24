package com.ane.filemanager.localization

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.ane.filemanager.R
import java.util.Locale

enum class LanguageMode(val preferenceValue: String, val languageTag: String?, val labelResource: Int) {
    SYSTEM("system", null, R.string.language_system),
    CHINESE("zh", "zh", R.string.language_chinese),
    ENGLISH("en", "en", R.string.language_english)
}

/** Owns the app locale. The default is system-following; unsupported system languages use Chinese values. */
object AppLanguage {
    private const val PREFERENCES = "language"
    private const val MODE = "mode"

    fun current(context: Context): LanguageMode {
        if (Build.VERSION.SDK_INT >= 33) {
            val tags = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            if (tags.isBlank()) return LanguageMode.SYSTEM
            return fromLanguageTag(tags.substringBefore(','))
        }
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(MODE, LanguageMode.SYSTEM.preferenceValue)
        return LanguageMode.entries.firstOrNull { it.preferenceValue == stored } ?: LanguageMode.SYSTEM
    }

    fun select(context: Context, mode: LanguageMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(MODE, mode.preferenceValue).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                mode.languageTag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        }
    }

    fun wrap(base: Context): ContextWrapper {
        val mode = current(base)
        val tags = mode.languageTag?.let(::listOf) ?: systemLanguageTags(base)
        return wrapWithLanguageTags(base, tags, updateDefaultLocale = true)
    }

    /** Context used by bundled plugin screens: plugin resources follow the device, not ANE's override. */
    fun wrapSystem(base: Context): ContextWrapper {
        val tags = systemLanguageTags(base)
        return wrapWithLanguageTags(base, tags, updateDefaultLocale = false)
    }

    private fun wrapWithLanguageTags(
        base: Context,
        tags: List<String>,
        updateDefaultLocale: Boolean
    ): ContextWrapper {
        if (tags.isEmpty()) return ContextWrapper(base)
        val primary = Locale.forLanguageTag(tags.first())
        if (updateDefaultLocale) Locale.setDefault(primary)
        val configuration = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) {
            // Default resources are Chinese. Passing secondary locales here could make values-en
            // outrank the default even when Chinese is the device's primary language.
            configuration.setLocales(LocaleList.forLanguageTags(tags.first()))
        } else {
            @Suppress("DEPRECATION")
            configuration.setLocale(primary)
        }
        configuration.setLayoutDirection(primary)
        return ContextWrapper(base.createConfigurationContext(configuration))
    }

    fun hostLanguageTags(context: Context): List<String> =
        languageTags(context.resources.configuration)

    fun systemLanguageTags(context: Context): List<String> = if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java).systemLocales
        (0 until locales.size()).map { locales[it].toLanguageTag() }
    } else {
        languageTags(Resources.getSystem().configuration)
    }

    private fun languageTags(configuration: Configuration): List<String> {
        return if (Build.VERSION.SDK_INT >= 24) {
            val locales = configuration.locales
            (0 until locales.size()).map { locales[it].toLanguageTag() }
        } else {
            @Suppress("DEPRECATION")
            listOf(configuration.locale.toLanguageTag())
        }
    }

    private fun fromLanguageTag(tag: String): LanguageMode = when (Locale.forLanguageTag(tag).language) {
        "en" -> LanguageMode.ENGLISH
        "zh" -> LanguageMode.CHINESE
        else -> LanguageMode.SYSTEM
    }
}
