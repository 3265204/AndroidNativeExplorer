package com.ane.filemanager.plugin.terminal.ui

import com.ane.filemanager.plugin.terminal.TerminalCell
import com.ane.filemanager.plugin.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalTextSelectionTest {
    @Test
    fun longPressSelectsTheTouchedNonWhitespaceToken() {
        val lines = screen("echo hello")

        val selection = TerminalSelectionText.wordAt(lines, TerminalTextPosition(0, 7))

        assertEquals("hello", selection?.let { TerminalSelectionText.extract(lines, it) })
    }

    @Test
    fun longPressOnBlankTerminalSpaceLeavesOnlyPasteAvailable() {
        val lines = screen("prompt")

        assertNull(TerminalSelectionText.wordAt(lines, TerminalTextPosition(0, 10)))
    }

    @Test
    fun reverseDragCopiesAcrossLinesInReadingOrder() {
        val lines = screen("first\r\nsecond")
        val selection = TerminalTextSelection(
            anchor = TerminalTextPosition(1, 3),
            focus = TerminalTextPosition(0, 2)
        )

        assertEquals("rst\nseco", TerminalSelectionText.extract(lines, selection))
    }

    @Test
    fun wideCharacterContinuationIsNotCopiedTwice() {
        val lines = screen("A中B")
        val selection = TerminalTextSelection(
            anchor = TerminalTextPosition(0, 0),
            focus = TerminalTextPosition(0, 3)
        )

        assertEquals("A中B", TerminalSelectionText.extract(lines, selection))
    }

    private fun screen(value: String): List<Array<TerminalCell>> =
        TerminalEmulator(initialRows = 2, initialColumns = 16).apply {
            feed(value.toByteArray(Charsets.UTF_8))
        }.snapshot(0).lines
}
