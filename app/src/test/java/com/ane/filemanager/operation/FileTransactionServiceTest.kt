package com.ane.filemanager.operation

import com.ane.filemanager.plugin.api.file.PluginTextEncoding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun `delete routes each source to trash on its own storage volume`() {
        val primary = temporary.newFolder("primary-volume")
        val removable = temporary.newFolder("removable-volume")
        val primaryFile = File(primary, "note.txt").apply { writeText("note") }
        val removableFile = File(removable, "movie.bin").apply { writeText("movie") }
        val transactions = FileTransactionService(primary) { listOf(primary, removable) }
        try {
            val result = transactions.call {
                transactions.deleteToTrash(listOf(primaryFile, removableFile))
            }

            assertTrue(result is FileResult.Success)
            val records = (result as FileResult.Success).value
            assertEquals(2, records.size)
            assertEquals(
                File(primary, ".ane-filemanager-trash").canonicalFile,
                records.first { it.original == primaryFile }.trashed.parentFile!!.parentFile!!.canonicalFile
            )
            assertEquals(
                File(removable, ".ane-filemanager-trash").canonicalFile,
                records.first { it.original == removableFile }.trashed.parentFile!!.parentFile!!.canonicalFile
            )

            assertTrue(transactions.files.restoreTrash(records) is FileResult.Success)
            assertEquals("note", primaryFile.readText())
            assertEquals("movie", removableFile.readText())
            assertFalse(File(primary, ".ane-filemanager-trash").exists())
            assertFalse(File(removable, ".ane-filemanager-trash").exists())
        } finally {
            transactions.close()
        }
    }

    @Test
    fun `startup removes stale trash from every mounted storage volume`() {
        val primary = temporary.newFolder("cleanup-primary")
        val removable = temporary.newFolder("cleanup-removable")
        val primaryTrash = File(primary, ".ane-filemanager-trash").apply { mkdir() }
        val removableTrash = File(removable, ".ane-filemanager-trash").apply { mkdir() }
        File(primaryTrash, "old.txt").writeText("old")
        File(removableTrash, "old.txt").writeText("old")
        val transactions = FileTransactionService(primary) { listOf(primary, removable) }
        try {
            transactions.call { Unit }

            assertFalse(primaryTrash.exists())
            assertFalse(removableTrash.exists())
        } finally {
            transactions.close()
        }
    }

    @Test
    fun `cancelling await suppresses delivery but lets accepted transaction finish`() = runBlocking {
        val root = temporary.newFolder("cancel-root")
        val transactions = FileTransactionService(root)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        try {
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                transactions.await {
                    started.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    completed.set(true)
                }
            }

            assertTrue(started.await(3, TimeUnit.SECONDS))
            waiter.cancelAndJoin()
            release.countDown()
            transactions.call { Unit }

            assertTrue(completed.get())
        } finally {
            release.countDown()
            transactions.close()
        }
    }
}
