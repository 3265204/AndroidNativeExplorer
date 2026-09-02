package com.ane.filemanager.operation

import com.ane.filemanager.plugin.api.file.PluginTextEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileTransactionServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `plugin text write shares undo and redo history`() {
        val root = temporary.newFolder("root")
        val file = File(root, "story.txt").apply { writeText("before") }
        val transactions = FileTransactionService(root)
        try {
            transactions.writeText(file, "after", PluginTextEncoding.UTF8, "story.txt")

            assertEquals("after", file.readText())
            assertTrue(transactions.canUndo)
            assertTrue(transactions.call { transactions.history.undo() } is FileResult.Success)
            assertEquals("before", file.readText())
            assertTrue(transactions.call { transactions.history.redo() } is FileResult.Success)
            assertEquals("after", file.readText())
        } finally {
            transactions.close()
        }
    }

    @Test
    fun `staged plugin output is committed and reversible as one node`() {
        val root = temporary.newFolder("output-root")
        val staging = temporary.newFile("staged.zip").apply { writeText("archive") }
        val transactions = FileTransactionService(root)
        try {
            val output = transactions.commitStagedOutput(staging, root, "game.zip")

            assertEquals("archive", output.readText())
            assertFalse(staging.exists())
            assertTrue(transactions.call { transactions.history.undo() } is FileResult.Success)
            assertFalse(output.exists())
            assertTrue(transactions.call { transactions.history.redo() } is FileResult.Success)
            assertEquals("archive", output.readText())
        } finally {
            transactions.close()
        }
    }
}
