package com.ane.filemanager.ui.selection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

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
        val controller = FileSelectionController(
            invalidate = {},
            doubleClickTimeoutMs = 300L,
            monotonicTimeMs = { now }
        )

        controller.click(file)
        controller.resetClickSequence()
        now = 100L
        val result = controller.click(file)

        assertEquals(ClickResult.SELECTED, result)
        assertTrue(controller.contains(file))
    }

    @Test
    fun `double click on directory requests directory navigation`() {
        val directory = createTempDirectory("selection-directory-").toFile()
        var now = 0L
        val controller = FileSelectionController(
            invalidate = {},
            doubleClickTimeoutMs = 300L,
            monotonicTimeMs = { now }
        )

        assertEquals(ClickResult.SELECTED, controller.click(directory))
        now = 100L
        assertEquals(ClickResult.OPEN_DIRECTORY, controller.click(directory))

        directory.delete()
    }

    private fun controller() = FileSelectionController(
        invalidate = {},
        doubleClickTimeoutMs = 300L
    )
}
