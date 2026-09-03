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

    @Test
    fun `reset click sequence prevents the next tap from opening`() {
        val file = File("/selection/file")
        var now = 0L
        var opened = false
        val controller = FileSelectionController(
            openFile = { opened = true },
            openDirectory = { opened = true },
            invalidate = {},
            doubleClickTimeoutMs = 300L,
            monotonicTimeMs = { now }
        )

        controller.click(file)
        controller.resetClickSequence()
        now = 100L
        controller.click(file)

        assertFalse(opened)
        assertTrue(controller.contains(file))
    }

    private fun controller() = FileSelectionController(
        openFile = {},
        openDirectory = {},
        invalidate = {},
        doubleClickTimeoutMs = 300L
    )
}
