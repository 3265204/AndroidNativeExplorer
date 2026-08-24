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
}
