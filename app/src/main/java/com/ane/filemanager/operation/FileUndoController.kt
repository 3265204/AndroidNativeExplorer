package com.ane.filemanager.operation

internal class PendingUndo(
    val run: () -> FileResult<Unit>
)

/** Keeps every reversible mutation for this app session in last-in-first-out order. */
internal class FileUndoController {
    private val history = ArrayDeque<PendingUndo>()

    val canUndo get() = history.isNotEmpty()
    val size get() = history.size

    fun current(): PendingUndo? = history.lastOrNull()

    fun push(action: PendingUndo) {
        history.addLast(action)
    }

    fun consume(action: PendingUndo) {
        if (history.lastOrNull() === action) history.removeLast()
    }
}
