package com.ane.filemanager.ui.sort

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Collator
import java.util.Locale

class FileSorterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val collator = Collator.getInstance(Locale.ENGLISH)

    @Test
    fun `name ordering keeps folders before files`() {
        val zebra = temporary.newFile("zebra.txt")
        val alpha = temporary.newFile("alpha.txt")
        val folder = temporary.newFolder("folder")

        val sorted = FileSorter.sorted(
            listOf(zebra, alpha, folder), FileSortMode.NAME, { 0L }, collator
        )

        assertEquals(listOf("folder", "alpha.txt", "zebra.txt"), sorted.map(File::getName))
    }

    @Test
    fun `modified size and opened ordering use descending values`() {
        val small = temporary.newFile("small.txt").apply {
            writeText("1")
            setLastModified(1_000L)
        }
        val large = temporary.newFile("large.txt").apply {
            writeText("12345")
            setLastModified(2_000L)
        }

        val modified = FileSorter.sorted(
            listOf(small, large), FileSortMode.MODIFIED, { 0L }, collator
        )
        val size = FileSorter.sorted(
            listOf(small, large), FileSortMode.SIZE, { 0L }, collator
        )
        val opened = FileSorter.sorted(
            listOf(small, large), FileSortMode.LAST_OPENED,
            { if (it == small) 20L else 10L }, collator
        )

        assertEquals(listOf("large.txt", "small.txt"), modified.map(File::getName))
        assertEquals(listOf("large.txt", "small.txt"), size.map(File::getName))
        assertEquals(listOf("small.txt", "large.txt"), opened.map(File::getName))
    }

    @Test
    fun `sorting reads each required metadata value once`() {
        val first = CountingFile("first", modified = 10L)
        val second = CountingFile("second", modified = 20L)

        FileSorter.sorted(
            listOf(first, second), FileSortMode.MODIFIED, { 0L }, collator
        )

        assertEquals(1, first.directoryReads)
        assertEquals(1, second.directoryReads)
        assertEquals(1, first.modifiedReads)
        assertEquals(1, second.modifiedReads)
        assertEquals(0, first.sizeReads)
        assertEquals(0, second.sizeReads)
    }

    private class CountingFile(name: String, private val modified: Long) : File(name) {
        var directoryReads = 0
        var modifiedReads = 0
        var sizeReads = 0

        override fun isDirectory(): Boolean {
            directoryReads++
            return false
        }

        override fun lastModified(): Long {
            modifiedReads++
            return modified
        }

        override fun length(): Long {
            sizeReads++
            return 1L
        }
    }
}
