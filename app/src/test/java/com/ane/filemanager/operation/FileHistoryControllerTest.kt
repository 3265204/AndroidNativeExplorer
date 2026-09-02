package com.ane.filemanager.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileHistoryControllerTest {
    @Test
    fun `recording after undo keeps both future branches`() {
        val history = FileHistoryController()
        var value = 0
        val first = history.push(action("first", { value -= 1 }, { value += 1 }))
        value += 1
        val oldFuture = history.push(action("old future", { value -= 10 }, { value += 10 }))
        value += 10

        assertSuccess(history.undo())
        val newFuture = history.push(action("new future", { value -= 100 }, { value += 100 }))
        value += 100

        val root = history.snapshot().single { it.id == 0L }
        val branchPoint = history.snapshot().single { it.id == first }
        assertEquals(listOf(first), root.childIds)
        assertEquals(listOf(oldFuture, newFuture), branchPoint.childIds)
        assertEquals(101, value)
    }

    @Test
    fun `checkout crosses the common ancestor and replays the selected branch`() {
        val history = FileHistoryController()
        var value = 0
        history.push(action("base", { value -= 1 }, { value += 1 }))
        value += 1
        val left = history.push(action("left", { value -= 10 }, { value += 10 }))
        value += 10
        assertSuccess(history.undo())
        history.push(action("right", { value -= 100 }, { value += 100 }))
        value += 100

        assertSuccess(history.checkout(left))

        assertEquals(11, value)
        assertEquals(left, history.currentId)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `failed checkout leaves cursor at last state reached`() {
        val history = FileHistoryController()
        history.push(action("base", {}, {}))
        val blocked = history.push(FileHistoryAction(
            label = "blocked",
            undo = { FileResult.Success(Unit) },
            redo = { FileResult.Failure(FileProblem(FileFailure.NAME_EXISTS)) }
        ))
        assertSuccess(history.checkout(0L))

        val result = history.checkout(blocked)

        assertTrue(result is FileResult.Failure)
        assertEquals(1L, history.currentId)
    }

    private fun action(
        label: String,
        undo: () -> Unit,
        redo: () -> Unit
    ) = FileHistoryAction(
        label = label,
        undo = { undo(); FileResult.Success(Unit) },
        redo = { redo(); FileResult.Success(Unit) }
    )

    private fun assertSuccess(result: FileResult<Unit>) {
        assertTrue(result is FileResult.Success)
    }
}
