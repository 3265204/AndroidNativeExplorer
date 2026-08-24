package com.ane.filemanager.pluginmanager

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginManifestTest {
    private val localizations = mapOf(
        "en" to PluginLocalization("Demo plugin", "English description"),
        "fr" to PluginLocalization("Extension de démonstration", "Description française")
    )

    @Test
    fun selectsBaseLanguageTranslationForRegionalLocale() {
        val localized = localizePluginMetadata(
            "演示插件", "中文说明", "zh", localizations, listOf("en-US")
        )

        assertEquals("Demo plugin", localized.name)
        assertEquals("English description", localized.description)
    }

    @Test
    fun fallsBackToDefaultChineseMetadata() {
        val localized = localizePluginMetadata(
            "演示插件", "中文说明", "zh", localizations, listOf("de-DE")
        )

        assertEquals("演示插件", localized.name)
        assertEquals("中文说明", localized.description)
    }

    @Test
    fun pluginCanSelectLanguageUnsupportedByHost() {
        val localized = localizePluginMetadata(
            "演示插件", "中文说明", "zh", localizations, listOf("fr-FR")
        )

        assertEquals("Extension de démonstration", localized.name)
        assertEquals("Description française", localized.description)
    }

    @Test
    fun declaredDefaultLocaleWinsBeforeSecondaryLanguage() {
        val localized = localizePluginMetadata(
            "演示插件", "中文说明", "zh", localizations, listOf("zh-Hans-CN", "en-GB")
        )

        assertEquals("演示插件", localized.name)
        assertEquals("中文说明", localized.description)
    }
}
