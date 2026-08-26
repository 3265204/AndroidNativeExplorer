package com.ane.filemanager.plugin.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    @Test
    fun cursorMovementAndEraseMutateTheScreen() {
        val terminal = TerminalEmulator(initialRows = 3, initialColumns = 8)

        terminal.feed("abc\u001b[2D!\u001b[K".bytes())

        assertEquals("a!", terminal.line(0).trimEnd())
        assertEquals(2, terminal.snapshot(0).cursorColumn)
    }

    @Test
    fun scrollbackRetainsLinesThatLeaveThePrimaryScreen() {
        val terminal = TerminalEmulator(initialRows = 3, initialColumns = 8)

        terminal.feed("one\r\ntwo\r\nthree\r\nfour".bytes())

        assertEquals(listOf("two", "three", "four"), terminal.textLines())
        assertEquals(1, terminal.snapshot(0).maximumScrollOffset)
        assertEquals("one", terminal.snapshot(1).lines.first().text().trimEnd())
    }

    @Test
    fun alternateScreenRestoresPrimaryContentsAndCursor() {
        val terminal = TerminalEmulator(initialRows = 3, initialColumns = 8)
        terminal.feed("main".bytes())

        terminal.feed("\u001b[?1049halt".bytes())
        assertEquals("alt", terminal.line(0).trimEnd())

        terminal.feed("\u001b[?1049l".bytes())
        assertEquals("main", terminal.line(0).trimEnd())
        assertEquals(4, terminal.snapshot(0).cursorColumn)
    }

    @Test
    fun utf8DecoderAcceptsACharacterSplitAcrossReads() {
        val terminal = TerminalEmulator(initialRows = 2, initialColumns = 8)
        val bytes = "中".bytes()

        terminal.feed(bytes.copyOfRange(0, 1))
        terminal.feed(bytes.copyOfRange(1, bytes.size))

        assertEquals("中", terminal.snapshot(0).lines[0][0].text)
        assertEquals(2, terminal.snapshot(0).lines[0][0].width)
        assertEquals(0, terminal.snapshot(0).lines[0][1].width)
    }

    @Test
    fun sgrFlagsAndCursorVisibilityAreTracked() {
        val terminal = TerminalEmulator(initialRows = 2, initialColumns = 8)

        terminal.feed("\u001b[1;4mX\u001b[?25l".bytes())

        val cell = terminal.snapshot(0).lines[0][0]
        assertTrue(cell.flags and TerminalEmulator.FLAG_BOLD != 0)
        assertTrue(cell.flags and TerminalEmulator.FLAG_UNDERLINE != 0)
        assertFalse(terminal.snapshot(0).cursorShown)
    }

    @Test
    fun deviceStatusQueryWritesAResponseBackToThePty() {
        val responses = mutableListOf<String>()
        val terminal = TerminalEmulator(2, 8) { responses += it.toString(Charsets.UTF_8) }
        terminal.feed("abc\u001b[6n".bytes())

        assertEquals(listOf("\u001b[1;4R"), responses)
    }

    @Test
    fun resizeUpdatesGridAndClampsCursor() {
        val terminal = TerminalEmulator(initialRows = 4, initialColumns = 10)
        terminal.feed("\u001b[4;10H".bytes())

        terminal.resize(2, 5)

        val snapshot = terminal.snapshot(0)
        assertEquals(2, terminal.rows)
        assertEquals(5, terminal.columns)
        assertEquals(1, snapshot.cursorRow)
        assertEquals(4, snapshot.cursorColumn)
    }

    private fun TerminalEmulator.line(index: Int): String = snapshot(0).lines[index].text()
    private fun TerminalEmulator.textLines(): List<String> =
        snapshot(0).lines.map { it.text().trimEnd() }

    private fun Array<TerminalCell>.text(): String = joinToString(separator = "") { it.text }
    private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)
}
