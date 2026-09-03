package com.ane.filemanager.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesNumericComponentsInsteadOfLexically() {
        assertTrue(AppVersion.isNewer("v0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("1.2.1", "1.2"))
        assertFalse(AppVersion.isNewer("1.2.0", "1.2"))
    }

    @Test
    fun stableVersionIsNewerThanPreRelease() {
        assertTrue(AppVersion.isNewer("1.0.0", "1.0.0-beta.2"))
        assertFalse(AppVersion.isNewer("1.0.0-beta.2", "1.0.0"))
        assertTrue(AppVersion.isNewer("1.0.0-beta.10", "1.0.0-beta.2"))
    }
}
