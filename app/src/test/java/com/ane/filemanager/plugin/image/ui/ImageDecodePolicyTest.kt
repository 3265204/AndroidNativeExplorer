package com.ane.filemanager.plugin.image.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDecodePolicyTest {
    @Test
    fun `sample keeps every decoded edge within max side`() {
        assertEquals(2, ImageDecodePolicy.sampleFor(8_000, 1_000, 4_096))
        assertEquals(2, ImageDecodePolicy.sampleFor(5_000, 3_000, 4_096))
    }

    @Test
    fun `square image is sampled further to respect pixel memory budget`() {
        assertEquals(4, ImageDecodePolicy.sampleFor(8_192, 8_192, 4_096))
        assertEquals(2, ImageDecodePolicy.sampleFor(4_096, 4_096, 4_096))
    }
}
