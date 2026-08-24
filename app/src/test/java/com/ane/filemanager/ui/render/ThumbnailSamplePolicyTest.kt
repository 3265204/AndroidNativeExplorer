package com.ane.filemanager.ui.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSamplePolicyTest {
    @Test
    fun `large square is decoded close to thumbnail dimensions`() {
        assertEquals(16, ThumbnailSamplePolicy.sampleFor(8_000, 8_000, 300, 300))
    }

    @Test
    fun `extreme panorama is constrained by pixel budget`() {
        assertEquals(8, ThumbnailSamplePolicy.sampleFor(40_000, 500, 300, 300))
    }

    @Test
    fun `small image is not sampled`() {
        assertEquals(1, ThumbnailSamplePolicy.sampleFor(640, 480, 800, 600))
    }
}
