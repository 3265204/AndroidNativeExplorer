package com.ane.filemanager.plugin.terminal.ui

import com.ane.filemanager.plugin.terminal.TerminalCell

internal data class TerminalTextPosition(val row: Int, val column: Int) :
    Comparable<TerminalTextPosition> {
    override fun compareTo(other: TerminalTextPosition): Int =
        compareValuesBy(this, other, TerminalTextPosition::row, TerminalTextPosition::column)
}

internal data class TerminalTextSelection(
    val anchor: TerminalTextPosition,
    val focus: TerminalTextPosition
) {
    val start: TerminalTextPosition get() = minOf(anchor, focus)
    val end: TerminalTextPosition get() = maxOf(anchor, focus)

    fun contains(row: Int, column: Int): Boolean =
        TerminalTextPosition(row, column) in start..end

    fun withFocus(position: TerminalTextPosition) = copy(focus = position)
}

/** Pure terminal-grid selection rules, kept separate from Android gesture handling. */
internal object TerminalSelectionText {
    fun wordAt(
        lines: List<Array<TerminalCell>>,
        position: TerminalTextPosition
    ): TerminalTextSelection? {
        val line = lines.getOrNull(position.row) ?: return null
        var column = position.column.coerceIn(line.indices)
        while (column > 0 && line[column].width == 0) column--
        if (line[column].text.isBlank()) return null

        var start = column
        while (start > 0 && isWordCell(line[start - 1])) start--
        var end = column
        while (end + 1 < line.size && isWordCell(line[end + 1])) end++
        return TerminalTextSelection(
            TerminalTextPosition(position.row, start),
            TerminalTextPosition(position.row, end)
        )
    }

    fun all(lines: List<Array<TerminalCell>>): TerminalTextSelection? {
        val lastRow = lines.indexOfLast { line -> line.any(::isWordCell) }
        if (lastRow < 0) return null
        val lastColumn = lines[lastRow].indexOfLast(::isWordCell).coerceAtLeast(0)
        return TerminalTextSelection(
            TerminalTextPosition(0, 0),
            TerminalTextPosition(lastRow, lastColumn)
        )
    }

    fun extract(
        lines: List<Array<TerminalCell>>,
        selection: TerminalTextSelection
    ): String {
        if (lines.isEmpty()) return ""
        val start = selection.start
        val end = selection.end
        return (start.row..end.row).mapNotNull { row ->
            val line = lines.getOrNull(row) ?: return@mapNotNull null
            val first = if (row == start.row) start.column else 0
            val last = if (row == end.row) end.column else line.lastIndex
            if (first > line.lastIndex || last < 0) return@mapNotNull ""
            (first.coerceAtLeast(0)..last.coerceAtMost(line.lastIndex))
                .mapNotNull { column -> line[column].takeIf { it.width != 0 }?.text }
                .joinToString(separator = "")
                .trimEnd()
        }.joinToString(separator = "\n")
    }

    private fun isWordCell(cell: TerminalCell): Boolean =
        cell.width == 0 || cell.text.isNotBlank()
}
