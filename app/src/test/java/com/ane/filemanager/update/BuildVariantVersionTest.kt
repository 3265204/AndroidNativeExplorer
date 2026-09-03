package com.ane.filemanager.update

import com.ane.filemanager.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildVariantVersionTest {
    @Test
    fun debugBuildUsesBetaVersionName() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".beta"))
        assertEquals(5, BuildConfig.VERSION_CODE)
        assertEquals("0.3.3-beta", BuildConfig.VERSION_NAME)
    }
}
