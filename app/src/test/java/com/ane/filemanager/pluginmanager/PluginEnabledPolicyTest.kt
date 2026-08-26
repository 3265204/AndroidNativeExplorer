package com.ane.filemanager.pluginmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginEnabledPolicyTest {
    @Test
    fun manifestDefaultControlsFirstAppearance() {
        assertFalse(resolvePluginEnabled("ane.terminal", false, emptySet(), emptySet()))
        assertTrue(resolvePluginEnabled("ane.archive", true, emptySet(), emptySet()))
    }

    @Test
    fun explicitUserChoiceOverridesManifestDefault() {
        assertTrue(resolvePluginEnabled("ane.terminal", false, emptySet(), setOf("ane.terminal")))
        assertFalse(resolvePluginEnabled("ane.archive", true, setOf("ane.archive"), emptySet()))
    }
}
