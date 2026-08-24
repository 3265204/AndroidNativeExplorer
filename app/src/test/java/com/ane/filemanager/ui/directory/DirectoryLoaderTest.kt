package com.ane.filemanager.ui.directory

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DirectoryLoaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `listing hides dotfiles and sorts off the caller thread`() {
        val directory = temporary.newFolder("files")
        File(directory, "z.txt").writeText("z")
        File(directory, "a.txt").writeText("a")
        File(directory, ".hidden").writeText("hidden")
        val loaded = mutableListOf<String>()
        val completed = CountDownLatch(1)
        val callerThread = Thread.currentThread()
        var loaderThread: Thread? = null
        val loader = DirectoryLoader { _, files ->
            loaded += files.map(File::getName)
            completed.countDown()
        }

        try {
            loader.load(directory, showHidden = false) { files ->
                loaderThread = Thread.currentThread()
                files.sortedBy(File::getName)
            }

            completed.await(3, TimeUnit.SECONDS)
            assertEquals(listOf("a.txt", "z.txt"), loaded)
            assertEquals(false, callerThread === loaderThread)
        } finally {
            loader.close()
        }
    }
}
