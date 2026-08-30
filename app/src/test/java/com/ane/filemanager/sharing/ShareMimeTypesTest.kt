package com.ane.filemanager.sharing

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareMimeTypesTest {
    @Test
    fun keepsExactTypeWhenEveryFileMatches() {
        assertEquals("image/png", ShareMimeTypes.common(listOf("image/png", "image/png")))
    }

    @Test
    fun usesTopLevelWildcardForRelatedTypes() {
        assertEquals("image/*", ShareMimeTypes.common(listOf("image/png", "image/jpeg")))
    }

    @Test
    fun usesAnyTypeForMixedFamilies() {
        assertEquals("*/*", ShareMimeTypes.common(listOf("image/png", "application/pdf")))
    }
}
