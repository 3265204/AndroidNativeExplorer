package com.ane.filemanager.plugin.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

internal class TerminalEmulator(
    initialRows: Int = 24,
    initialColumns: Int = 80,
    private val response: (ByteArray) -> Unit = {}
) {
    var rows: Int = initialRows.coerceAtLeast(2)
        private set
    var columns: Int = initialColumns.coerceAtLeast(2)
        private set

    private var screen = Array(rows) { blankLine() }
    private val history = ArrayDeque<Array<TerminalCell>>()
    private var cursorRow = 0
    private var cursorColumn = 0
    private var savedRow = 0
    private var savedColumn = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false
    private var wraparound = true
    private var cursorShown = true
    private var alternate = false
    private var primary: SavedScreen? = null
    private var currentForeground = DEFAULT_FOREGROUND
    private var currentBackground = DEFAULT_BACKGROUND
    private var currentFlags = 0
    private var parserState = ParserState.NORMAL
    private val sequence = StringBuilder()
    private var pendingHighSurrogate: Char? = null
    private var pendingBytes = ByteArray(0)
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    @Synchronized
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val input = ByteBuffer.wrap(pendingBytes + bytes)
        val output = CharBuffer.allocate((input.remaining() * decoder.maxCharsPerByte()).toInt() + 2)
        decoder.decode(input, output, false)
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        while (output.hasRemaining()) consume(output.get())
    }

    @Synchronized
    fun resize(newRows: Int, newColumns: Int) {
        val safeRows = newRows.coerceAtLeast(2)
        val safeColumns = newColumns.coerceAtLeast(2)
        if (safeRows == rows && safeColumns == columns) return
        screen = resizeGrid(screen, safeRows, safeColumns)
        primary = primary?.let {
            it.copy(
                screen = resizeGrid(it.screen, safeRows, safeColumns),
                row = it.row.coerceIn(0, safeRows - 1),
                column = it.column.coerceIn(0, safeColumns - 1)
            )
        }
        rows = safeRows
        columns = safeColumns
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    @Synchronized
    fun snapshot(scrollOffset: Int): TerminalSnapshot {
        val safeOffset = scrollOffset.coerceIn(0, history.size)
        val all = ArrayList<Array<TerminalCell>>(history.size + rows)
        history.forEach { all += fitLine(it, columns) }
        screen.forEach { all += copyLine(it) }
        val end = (all.size - safeOffset).coerceAtLeast(rows)
        val start = (end - rows).coerceAtLeast(0)
        val visible = all.subList(start, end).map(::copyLine)
        return TerminalSnapshot(
            lines = visible,
            cursorRow = if (safeOffset == 0) cursorRow else -1,
            cursorColumn = cursorColumn,
            cursorShown = cursorShown && safeOffset == 0,
            maximumScrollOffset = history.size
        )
    }

    private fun consume(character: Char) {
        when (parserState) {
            ParserState.NORMAL -> consumeNormal(character)
            ParserState.ESCAPE -> consumeEscape(character)
            ParserState.CSI -> consumeCsi(character)
            ParserState.OSC -> consumeOsc(character)
            ParserState.OSC_ESCAPE -> {
                if (character == '\\') finishOsc() else {
                    sequence.append('\u001b').append(character)
                    parserState = ParserState.OSC
                }
            }
            ParserState.CHARSET -> parserState = ParserState.NORMAL
        }
    }

    private fun consumeNormal(character: Char) {
        when (character) {
            '\u001b' -> parserState = ParserState.ESCAPE
            '\r' -> {
                cursorColumn = 0
                wrapPending = false
            }
            '\n', '\u000b', '\u000c' -> lineFeed()
            '\b' -> {
                cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
                wrapPending = false
            }
            '\t' -> cursorColumn = (((cursorColumn / 8) + 1) * 8).coerceAtMost(columns - 1)
            '\u0007', '\u0000' -> Unit
            else -> if (character >= ' ') consumePrintable(character)
        }
    }

    private fun consumePrintable(character: Char) {
        val high = pendingHighSurrogate
        if (high != null) {
            pendingHighSurrogate = null
            if (Character.isLowSurrogate(character)) {
                putCodePoint(Character.toCodePoint(high, character))
                return
            }
            putCodePoint(high.code)
        }
        if (Character.isHighSurrogate(character)) pendingHighSurrogate = character
        else putCodePoint(character.code)
    }

    private fun consumeEscape(character: Char) {
        when (character) {
            '[' -> beginSequence(ParserState.CSI)
            ']' -> beginSequence(ParserState.OSC)
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'M' -> reverseIndex()
            'E' -> {
                cursorColumn = 0
                lineFeed()
            }
            'c' -> reset()
            '(', ')', '*', '+' -> parserState = ParserState.CHARSET
            else -> parserState = ParserState.NORMAL
        }
        if (character !in charArrayOf('[', ']', '(', ')', '*', '+')) parserState = ParserState.NORMAL
    }

    private fun consumeCsi(character: Char) {
        if (character.code in 0x40..0x7e) {
            executeCsi(character, sequence.toString())
            sequence.clear()
            parserState = ParserState.NORMAL
        } else if (sequence.length < MAX_SEQUENCE_LENGTH) {
            sequence.append(character)
        }
    }

    private fun consumeOsc(character: Char) {
        when (character) {
            '\u0007' -> finishOsc()
            '\u001b' -> parserState = ParserState.OSC_ESCAPE
            else -> if (sequence.length < MAX_SEQUENCE_LENGTH) sequence.append(character)
        }
    }

    private fun finishOsc() {
        sequence.clear()
        parserState = ParserState.NORMAL
    }

    private fun beginSequence(state: ParserState) {
        sequence.clear()
        parserState = state
    }

    private fun executeCsi(command: Char, raw: String) {
        val privateMode = raw.startsWith('?')
        val clean = raw.trimStart('?', '>', '!').replace(':', ';')
        val parameters = if (clean.isBlank()) emptyList() else clean.split(';').map {
            it.toIntOrNull() ?: 0
        }
        fun value(index: Int, fallback: Int = 1): Int =
            parameters.getOrNull(index)?.takeIf { it != 0 } ?: fallback

        when (command) {
            'A' -> moveCursor(row = cursorRow - value(0))
            'B' -> moveCursor(row = cursorRow + value(0))
            'C' -> moveCursor(column = cursorColumn + value(0))
            'D' -> moveCursor(column = cursorColumn - value(0))
            'E' -> moveCursor(row = cursorRow + value(0), column = 0)
            'F' -> moveCursor(row = cursorRow - value(0), column = 0)
            'G', '`' -> moveCursor(column = value(0) - 1)
            'd' -> moveCursor(row = value(0) - 1)
            'H', 'f' -> moveCursor(value(0) - 1, value(1) - 1)
            'J' -> eraseDisplay(parameters.firstOrNull() ?: 0)
            'K' -> eraseLine(parameters.firstOrNull() ?: 0)
            'm' -> applyGraphics(parameters)
            'r' -> setScrollRegion(value(0), value(1, rows))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'P' -> deleteCharacters(value(0))
            '@' -> insertCharacters(value(0))
            'X' -> eraseCharacters(value(0))
            'L' -> insertLines(value(0))
            'M' -> deleteLines(value(0))
            'S' -> repeat(value(0)) { scrollUp() }
            'T' -> repeat(value(0)) { scrollDown() }
            'h', 'l' -> setModes(parameters, privateMode, command == 'h')
            'n' -> reportStatus(parameters.firstOrNull() ?: 0)
            'c' -> response("\u001b[?1;2c".toByteArray())
        }
        wrapPending = false
    }

    private fun putCodePoint(codePoint: Int) {
        val width = characterWidth(codePoint)
        if (width == 0) {
            val previous = (cursorColumn - 1).coerceAtLeast(0)
            screen[cursorRow][previous].text += String(Character.toChars(codePoint))
            return
        }
        if (wrapPending || width == 2 && cursorColumn == columns - 1) {
            if (wraparound) {
                cursorColumn = 0
                lineFeed()
            } else cursorColumn = columns - width
        }
        val cell = styledCell(String(Character.toChars(codePoint)), width)
        screen[cursorRow][cursorColumn] = cell
        if (width == 2 && cursorColumn + 1 < columns) {
            screen[cursorRow][cursorColumn + 1] = styledCell("", 0)
        }
        cursorColumn += width
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            wrapPending = true
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) scrollUp()
        else cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown()
        else cursorRow = (cursorRow - 1).coerceAtLeast(0)
    }

    private fun scrollUp() {
        val removed = screen[scrollTop]
        for (row in scrollTop until scrollBottom) screen[row] = screen[row + 1]
        screen[scrollBottom] = blankLine()
        if (scrollTop == 0 && scrollBottom == rows - 1 && !alternate) {
            history.addLast(copyLine(removed))
            while (history.size > MAX_SCROLLBACK_LINES) history.removeFirst()
        }
    }

    private fun scrollDown() {
        for (row in scrollBottom downTo scrollTop + 1) screen[row] = screen[row - 1]
        screen[scrollTop] = blankLine()
    }

    private fun moveCursor(row: Int = cursorRow, column: Int = cursorColumn) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorColumn = column.coerceIn(0, columns - 1)
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseRange(cursorRow, cursorColumn, columns)
                for (row in cursorRow + 1 until rows) eraseRange(row, 0, columns)
            }
            1 -> {
                for (row in 0 until cursorRow) eraseRange(row, 0, columns)
                eraseRange(cursorRow, 0, cursorColumn + 1)
            }
            2 -> for (row in 0 until rows) eraseRange(row, 0, columns)
            3 -> history.clear()
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseRange(cursorRow, cursorColumn, columns)
            1 -> eraseRange(cursorRow, 0, cursorColumn + 1)
            2 -> eraseRange(cursorRow, 0, columns)
        }
    }

    private fun eraseRange(row: Int, start: Int, end: Int) {
        for (column in start.coerceAtLeast(0) until end.coerceAtMost(columns)) {
            screen[row][column] = styledCell()
        }
    }

    private fun deleteCharacters(count: Int) {
        val line = screen[cursorRow]
        val amount = count.coerceAtMost(columns - cursorColumn)
        for (column in cursorColumn until columns - amount) line[column] = line[column + amount]
        for (column in columns - amount until columns) line[column] = styledCell()
    }

    private fun insertCharacters(count: Int) {
        val line = screen[cursorRow]
        val amount = count.coerceAtMost(columns - cursorColumn)
        for (column in columns - 1 downTo cursorColumn + amount) line[column] = line[column - amount]
        for (column in cursorColumn until cursorColumn + amount) line[column] = styledCell()
    }

    private fun eraseCharacters(count: Int) = eraseRange(
        cursorRow,
        cursorColumn,
        cursorColumn + count.coerceAtLeast(1)
    )

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (row in scrollBottom downTo cursorRow + 1) screen[row] = screen[row - 1]
            screen[cursorRow] = blankLine()
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (row in cursorRow until scrollBottom) screen[row] = screen[row + 1]
            screen[scrollBottom] = blankLine()
        }
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        val safeTop = (top - 1).coerceIn(0, rows - 1)
        val safeBottom = (bottom - 1).coerceIn(0, rows - 1)
        if (safeTop >= safeBottom) return
        scrollTop = safeTop
        scrollBottom = safeBottom
        moveCursor(0, 0)
    }

    private fun setModes(parameters: List<Int>, privateMode: Boolean, enabled: Boolean) {
        if (!privateMode) return
        parameters.forEach { mode ->
            when (mode) {
                7 -> wraparound = enabled
                25 -> cursorShown = enabled
                47, 1047, 1049 -> if (enabled) enterAlternate() else leaveAlternate()
            }
        }
    }

    private fun enterAlternate() {
        if (alternate) return
        primary = SavedScreen(screen.map(::copyLine).toTypedArray(), cursorRow, cursorColumn)
        screen = Array(rows) { blankLine() }
        cursorRow = 0
        cursorColumn = 0
        scrollTop = 0
        scrollBottom = rows - 1
        alternate = true
    }

    private fun leaveAlternate() {
        if (!alternate) return
        primary?.let {
            screen = resizeGrid(it.screen, rows, columns)
            cursorRow = it.row.coerceIn(0, rows - 1)
            cursorColumn = it.column.coerceIn(0, columns - 1)
        }
        primary = null
        scrollTop = 0
        scrollBottom = rows - 1
        alternate = false
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedColumn = cursorColumn
    }

    private fun restoreCursor() = moveCursor(savedRow, savedColumn)

    private fun applyGraphics(parameters: List<Int>) {
        val values = if (parameters.isEmpty()) listOf(0) else parameters
        var index = 0
        while (index < values.size) {
            when (val value = values[index]) {
                0 -> {
                    currentForeground = DEFAULT_FOREGROUND
                    currentBackground = DEFAULT_BACKGROUND
                    currentFlags = 0
                }
                1 -> currentFlags = currentFlags or FLAG_BOLD
                2 -> currentFlags = currentFlags or FLAG_DIM
                3 -> currentFlags = currentFlags or FLAG_ITALIC
                4 -> currentFlags = currentFlags or FLAG_UNDERLINE
                7 -> currentFlags = currentFlags or FLAG_INVERSE
                8 -> currentFlags = currentFlags or FLAG_HIDDEN
                9 -> currentFlags = currentFlags or FLAG_STRIKE
                22 -> currentFlags = currentFlags and (FLAG_BOLD or FLAG_DIM).inv()
                23 -> currentFlags = currentFlags and FLAG_ITALIC.inv()
                24 -> currentFlags = currentFlags and FLAG_UNDERLINE.inv()
                27 -> currentFlags = currentFlags and FLAG_INVERSE.inv()
                28 -> currentFlags = currentFlags and FLAG_HIDDEN.inv()
                29 -> currentFlags = currentFlags and FLAG_STRIKE.inv()
                in 30..37 -> currentForeground = ANSI_COLORS[value - 30]
                in 40..47 -> currentBackground = ANSI_COLORS[value - 40]
                in 90..97 -> currentForeground = ANSI_COLORS[8 + value - 90]
                in 100..107 -> currentBackground = ANSI_COLORS[8 + value - 100]
                38, 48 -> {
                    val color = extendedColor(values, index + 1)
                    if (color != null) {
                        if (value == 38) currentForeground = color.first else currentBackground = color.first
                        index += color.second
                    }
                }
                39 -> currentForeground = DEFAULT_FOREGROUND
                49 -> currentBackground = DEFAULT_BACKGROUND
            }
            index++
        }
    }

    private fun extendedColor(values: List<Int>, start: Int): Pair<Int, Int>? {
        return when (values.getOrNull(start)) {
            5 -> values.getOrNull(start + 1)?.let { color256(it) to 2 }
            2 -> if (start + 3 < values.size) {
                rgb(
                    values[start + 1].coerceIn(0, 255),
                    values[start + 2].coerceIn(0, 255),
                    values[start + 3].coerceIn(0, 255)
                ) to 4
            } else null
            else -> null
        }
    }

    private fun reportStatus(mode: Int) {
        when (mode) {
            5 -> response("\u001b[0n".toByteArray())
            6 -> response("\u001b[${cursorRow + 1};${cursorColumn + 1}R".toByteArray())
        }
    }

    private fun reset() {
        screen = Array(rows) { blankLine() }
        history.clear()
        cursorRow = 0
        cursorColumn = 0
        savedRow = 0
        savedColumn = 0
        scrollTop = 0
        scrollBottom = rows - 1
        currentForeground = DEFAULT_FOREGROUND
        currentBackground = DEFAULT_BACKGROUND
        currentFlags = 0
        wrapPending = false
        wraparound = true
        cursorShown = true
    }

    private fun blankLine() = Array(columns) { styledCell() }

    private fun styledCell(text: String = " ", width: Int = 1) = TerminalCell(
        text,
        currentForeground,
        currentBackground,
        currentFlags,
        width
    )

    private fun copyLine(line: Array<TerminalCell>) = Array(line.size) { line[it].copy() }

    private fun fitLine(line: Array<TerminalCell>, width: Int) = Array(width) { column ->
        line.getOrNull(column)?.copy() ?: TerminalCell()
    }

    private fun resizeGrid(
        source: Array<Array<TerminalCell>>,
        targetRows: Int,
        targetColumns: Int
    ): Array<Array<TerminalCell>> = Array(targetRows) { row ->
        Array(targetColumns) { column ->
            source.getOrNull(row)?.getOrNull(column)?.copy() ?: TerminalCell()
        }
    }

    private fun color256(index: Int): Int {
        val safe = index.coerceIn(0, 255)
        if (safe < 16) return ANSI_COLORS[safe]
        if (safe >= 232) {
            val gray = 8 + (safe - 232) * 10
            return rgb(gray, gray, gray)
        }
        val cube = safe - 16
        fun component(value: Int) = if (value == 0) 0 else 55 + value * 40
        return rgb(
            component(cube / 36),
            component((cube / 6) % 6),
            component(cube % 6)
        )
    }

    private fun characterWidth(codePoint: Int): Int {
        val type = Character.getType(codePoint)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        ) return 0
        return if (
            codePoint in 0x1100..0x115f || codePoint in 0x2329..0x232a ||
            codePoint in 0x2e80..0xa4cf || codePoint in 0xac00..0xd7a3 ||
            codePoint in 0xf900..0xfaff || codePoint in 0xfe10..0xfe19 ||
            codePoint in 0xfe30..0xfe6f || codePoint in 0xff00..0xff60 ||
            codePoint in 0xffe0..0xffe6 || codePoint in 0x1f300..0x1faff ||
            codePoint in 0x20000..0x3fffd
        ) 2 else 1
    }

    private data class SavedScreen(
        val screen: Array<Array<TerminalCell>>,
        val row: Int,
        val column: Int
    )

    private enum class ParserState { NORMAL, ESCAPE, CSI, OSC, OSC_ESCAPE, CHARSET }

    companion object {
        const val DEFAULT_FOREGROUND = -1
        const val DEFAULT_BACKGROUND = -2
        const val FLAG_BOLD = 1
        const val FLAG_DIM = 1 shl 1
        const val FLAG_ITALIC = 1 shl 2
        const val FLAG_UNDERLINE = 1 shl 3
        const val FLAG_INVERSE = 1 shl 4
        const val FLAG_HIDDEN = 1 shl 5
        const val FLAG_STRIKE = 1 shl 6
        private const val MAX_SEQUENCE_LENGTH = 4096
        private const val MAX_SCROLLBACK_LINES = 2000
        private val ANSI_COLORS = intArrayOf(
            rgb(0, 0, 0), rgb(205, 49, 49), rgb(13, 188, 121),
            rgb(229, 229, 16), rgb(36, 114, 200), rgb(188, 63, 188),
            rgb(17, 168, 205), rgb(229, 229, 229), rgb(102, 102, 102),
            rgb(241, 76, 76), rgb(35, 209, 139), rgb(245, 245, 67),
            rgb(59, 142, 234), rgb(214, 112, 214), rgb(41, 184, 219),
            rgb(255, 255, 255)
        )

        private fun rgb(red: Int, green: Int, blue: Int): Int =
            (0xff shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)
    }
}

internal data class TerminalCell(
    var text: String = " ",
    var foreground: Int = TerminalEmulator.DEFAULT_FOREGROUND,
    var background: Int = TerminalEmulator.DEFAULT_BACKGROUND,
    var flags: Int = 0,
    var width: Int = 1
)

internal data class TerminalSnapshot(
    val lines: List<Array<TerminalCell>>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorShown: Boolean,
    val maximumScrollOffset: Int
)
