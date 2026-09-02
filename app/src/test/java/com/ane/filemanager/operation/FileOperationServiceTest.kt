package com.ane.filemanager.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val service = FileOperationService()

    @Test
    fun `create increments stem and preserves extension`() {
        val directory = temporary.newFolder("create")
        File(directory, "a.txt").writeText("existing")
        File(directory, "a1.txt").writeText("existing")

        val result = service.create(directory, "a.txt", folder = false)

        assertTrue(result is FileResult.Success)
        assertEquals("a2.txt", (result as FileResult.Success).value.name)
    }

    @Test
    fun `keep both rename leaves existing target intact`() {
        val directory = temporary.newFolder("keep-both")
        val source = File(directory, "source.txt").apply { writeText("source") }
        val target = File(directory, "target.txt").apply { writeText("target") }

        val result = service.rename(source, target.name, RenameConflictPolicy.KEEP_BOTH)

        assertTrue(result is FileResult.Success)
        val record = (result as FileResult.Success).value
        assertEquals("target1.txt", record.result.name)
        assertEquals("target", target.readText())
        assertEquals("source", record.result.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `replace rename and undo restore both original files`() {
        val directory = temporary.newFolder("replace")
        val source = File(directory, "source.txt").apply { writeText("source") }
        val target = File(directory, "target.txt").apply { writeText("target") }
        val trash = File(temporary.root, "trash")

        val renamed = service.rename(source, target.name, RenameConflictPolicy.REPLACE, trash)

        assertTrue(renamed is FileResult.Success)
        val record = (renamed as FileResult.Success).value
        assertEquals("source", target.readText())
        assertFalse(source.exists())

        val undone = service.undoRename(record)

        assertTrue(undone is FileResult.Success)
        assertEquals("source", source.readText())
        assertEquals("target", target.readText())
    }

    @Test
    fun `copy batch reports completed files and can skip a failed source`() {
        val sourceDirectory = temporary.newFolder("copy-source")
        val targetDirectory = temporary.newFolder("copy-target")
        val first = File(sourceDirectory, "first.txt").apply { writeText("first") }
        val missing = File(sourceDirectory, "missing.txt")

        val result = service.transfer(listOf(first, missing), targetDirectory, move = false)

        assertTrue(result is FileResult.Failure)
        assertEquals(FileFailure.SOURCE_MISSING, (result as FileResult.Failure).problem.failure)
        assertTrue(first.exists())
        assertEquals("first", File(targetDirectory, first.name).readText())
        val interruption = result.transferInterruption!!
        assertEquals(listOf(first), interruption.completed.map(TransferRecord::original))

        val resumed = service.transfer(
            interruption.remaining,
            interruption.targetDirectory,
            interruption.moved,
            interruption.completed,
            interruption.skipped + 1
        )

        assertTrue(resumed is FileResult.Success)
        val batch = (resumed as FileResult.Success).value
        assertEquals(1, batch.records.size)
        assertEquals(1, batch.skipped)
    }

    @Test
    fun `move batch can retry a failed source without losing completed records`() {
        val sourceDirectory = temporary.newFolder("move-source")
        val targetDirectory = temporary.newFolder("move-target")
        val first = File(sourceDirectory, "first.txt").apply { writeText("first") }
        val missing = File(sourceDirectory, "missing.txt")

        val result = service.transfer(listOf(first, missing), targetDirectory, move = true)

        assertTrue(result is FileResult.Failure)
        assertEquals(FileFailure.SOURCE_MISSING, (result as FileResult.Failure).problem.failure)
        assertFalse(first.exists())
        assertEquals("first", File(targetDirectory, first.name).readText())
        val interruption = result.transferInterruption!!
        missing.writeText("second")

        val resumed = service.transfer(
            listOf(interruption.failed) + interruption.remaining,
            interruption.targetDirectory,
            interruption.moved,
            interruption.completed,
            interruption.skipped
        )

        assertTrue(resumed is FileResult.Success)
        val batch = (resumed as FileResult.Success).value
        assertEquals(2, batch.records.size)
        assertEquals(0, batch.skipped)
        assertFalse(missing.exists())
        assertEquals("second", File(targetDirectory, missing.name).readText())
    }

    @Test
    fun `partial move retry only finishes deleting the source`() {
        val sourceDirectory = temporary.newFolder("partial-retry-source")
        val targetDirectory = temporary.newFolder("partial-retry-target")
        val source = File(sourceDirectory, "item.txt").apply { writeText("source remainder") }
        val target = File(targetDirectory, "item.txt").apply { writeText("complete target") }
        val partial = TransferRecord(source, target, replaceOriginalOnUndo = true)

        val result = service.transfer(
            emptyList(),
            targetDirectory,
            move = true,
            partialMove = partial
        )

        assertTrue(result is FileResult.Success)
        val record = (result as FileResult.Success).value.records.single()
        assertFalse(source.exists())
        assertEquals("complete target", target.readText())
        assertFalse(record.replaceOriginalOnUndo)
    }

    @Test
    fun `undo skipped partial move replaces the incomplete original`() {
        val sourceDirectory = temporary.newFolder("partial-undo-source")
        val targetDirectory = temporary.newFolder("partial-undo-target")
        val source = File(sourceDirectory, "item.txt").apply { writeText("incomplete") }
        val target = File(targetDirectory, "item.txt").apply { writeText("complete") }
        val partial = TransferRecord(source, target, replaceOriginalOnUndo = true)

        val result = service.undoTransfer(listOf(partial), moved = true)

        assertTrue(result is FileResult.Success)
        assertEquals("complete", source.readText())
        assertFalse(target.exists())
    }

    @Test
    fun `copy can be undone and redone at its recorded destination`() {
        val sourceDirectory = temporary.newFolder("redo-copy-source")
        val targetDirectory = temporary.newFolder("redo-copy-target")
        val source = File(sourceDirectory, "item.txt").apply { writeText("content") }
        val copied = service.transfer(listOf(source), targetDirectory, move = false)
            as FileResult.Success
        val records = copied.value.records

        assertTrue(service.undoTransfer(records, moved = false) is FileResult.Success)
        assertFalse(records.single().result.exists())

        assertTrue(service.redoTransfer(records, moved = false) is FileResult.Success)
        assertEquals("content", records.single().result.readText())
    }

    @Test
    fun `redo transfer refuses to overwrite a branch conflict`() {
        val sourceDirectory = temporary.newFolder("redo-conflict-source")
        val targetDirectory = temporary.newFolder("redo-conflict-target")
        val source = File(sourceDirectory, "item.txt").apply { writeText("source") }
        val target = File(targetDirectory, "item.txt").apply { writeText("conflict") }
        val record = TransferRecord(source, target)

        val result = service.redoTransfer(listOf(record), moved = false)

        assertTrue(result is FileResult.Failure)
        assertEquals(FileFailure.NAME_EXISTS, (result as FileResult.Failure).problem.failure)
        assertEquals("conflict", target.readText())
    }

    @Test
    fun `undo transfer validates the whole batch before changing files`() {
        val sourceDirectory = temporary.newFolder("undo-validation-source")
        val targetDirectory = temporary.newFolder("undo-validation-target")
        val first = File(sourceDirectory, "first.txt").apply { writeText("first") }
        val second = File(sourceDirectory, "second.txt").apply { writeText("second") }
        val copied = service.transfer(listOf(first, second), targetDirectory, move = false)
            as FileResult.Success
        val records = copied.value.records
        records.first().result.delete()

        val result = service.undoTransfer(records, moved = false)

        assertTrue(result is FileResult.Failure)
        assertTrue(records.last().result.exists())
    }
}
