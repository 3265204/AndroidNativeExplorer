package com.ane.filemanager.pluginmanager

import com.ane.filemanager.plugin.api.PluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestTest {
    private val localizations = mapOf(
        "en" to PluginLocalization("Demo plugin", "English description"),
        "fr" to PluginLocalization("Extension de démonstration", "Description française")
    )

    @Test
    fun apiV3HostKeepsV2CompatibilityButRejectsOutsideRange() {
        assertFalse(PluginApi.supports(1))
        assertTrue(PluginApi.supports(2))
        assertTrue(PluginApi.supports(3))
        assertFalse(PluginApi.supports(4))
    }

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
