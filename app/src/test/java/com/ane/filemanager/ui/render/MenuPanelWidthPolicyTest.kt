package com.ane.filemanager.ui.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPanelWidthPolicyTest {
    @Test
    fun `keeps content widths when all levels fit`() {
        assertEquals(
            listOf(200f, 140f),
            MenuPanelWidthPolicy.fit(
                desiredWidths = listOf(200f, 140f),
                availableWidth = 500f,
                gap = 6f,
                minimumWidth = 112f
            )
        )
    }

    @Test
    fun `shrinks levels proportionally without exceeding the safe width`() {
        val fitted = MenuPanelWidthPolicy.fit(
            desiredWidths = listOf(216f, 180f),
            availableWidth = 320f,
            gap = 6f,
            minimumWidth = 112f
        )

        assertEquals(320f, fitted.sum() + 6f, 0.01f)
        assertTrue(fitted.all { it >= 112f })
        assertTrue(fitted[0] > fitted[1])
    }

    @Test
    fun `shares extremely narrow space instead of overlapping`() {
        val fitted = MenuPanelWidthPolicy.fit(
            desiredWidths = listOf(216f, 180f),
            availableWidth = 180f,
            gap = 6f,
            minimumWidth = 112f
        )

        assertEquals(listOf(87f, 87f), fitted)
        assertEquals(180f, fitted.sum() + 6f, 0.01f)
    }
}
