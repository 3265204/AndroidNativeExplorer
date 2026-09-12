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
        assertEquals(6, BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-beta"))
    }
}
