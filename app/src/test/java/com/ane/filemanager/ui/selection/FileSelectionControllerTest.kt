package com.ane.filemanager.ui.selection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileSelectionControllerTest {
    @Test
    fun `one slide gesture keeps one selection polarity until release`() {
        val first = File("/selection/first")
        val second = File("/selection/second")
        val controller = controller().apply {
            enterMultiSelect()
            set(second, true)
        }

        controller.beginSlide(first)
        controller.applySlide(second)
        controller.applySlide(first)

        assertTrue(controller.contains(first))
        assertTrue(controller.contains(second))

        controller.endSlide()
        controller.beginSlide(second)
        controller.applySlide(first)
        controller.applySlide(second)

        assertFalse(controller.contains(first))
        assertFalse(controller.contains(second))
    }

    private fun controller() = FileSelectionController(
        openFile = {},
        openDirectory = {},
        invalidate = {},
        doubleClickTimeoutMs = 300L
    )
}
