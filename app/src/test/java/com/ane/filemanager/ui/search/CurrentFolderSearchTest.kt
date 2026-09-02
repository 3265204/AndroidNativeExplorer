package com.ane.filemanager.core.file

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileQueryServiceTest {
    @Test
    fun `partial matching ignores case and preserves displayed order`() {
        val items = listOf(File("Holiday.MP4"), File("holiday-photo.JPG"), File("notes.txt"))

        val matches = FileQueryService.matchingName(items, "HOLIDAY")

        assertEquals(listOf("Holiday.MP4", "holiday-photo.JPG"), matches.map(File::getName))
    }

    @Test
    fun `blank query returns no matches`() {
        assertEquals(emptyList<File>(), FileQueryService.matchingName(listOf(File("anything")), "   "))
    }
}
