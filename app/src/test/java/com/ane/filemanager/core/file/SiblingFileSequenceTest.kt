package com.ane.filemanager.core.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiblingFileSequenceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun filtersSortsAndMovesThroughSiblingFiles() {
        val directory = temporary.newFolder("media")
        val second = directory.resolve("b.demo").apply { writeText("b") }
        directory.resolve("a.demo").writeText("a")
        directory.resolve("ignored.txt").writeText("ignored")

        val sequence = SiblingFileSequence.create(second) { it.extension == "demo" }

        assertEquals(listOf("a.demo", "b.demo"), sequence.files.map { it.name })
        assertEquals("b.demo", sequence.current.name)
        assertTrue(sequence.hasPrevious)
        assertFalse(sequence.hasNext)
        assertEquals("1 / 2", sequence.moveBy(-1)?.let { sequence.positionLabel })
        assertNull(sequence.moveBy(-1))
    }

    @Test
    fun keepsOpenedFileWhenDirectoryScanDoesNotAcceptIt() {
        val opened = temporary.newFile("opened.bin")

        val sequence = SiblingFileSequence.create(opened) { false }

        assertEquals(listOf(opened), sequence.files)
        assertEquals(opened, sequence.current)
    }
}
