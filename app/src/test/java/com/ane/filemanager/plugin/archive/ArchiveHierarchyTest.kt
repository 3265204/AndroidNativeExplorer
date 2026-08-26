package com.ane.filemanager.plugin.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveHierarchyTest {
    @Test
    fun synthesizesMissingFoldersAndShowsOnlyOneLevel() {
        val hierarchy = ArchiveHierarchy(listOf(
            ArchiveEntryInfo("docs/manual/images/cover.png", false, 120),
            ArchiveEntryInfo("docs/readme.txt", false, 40),
            ArchiveEntryInfo("root.txt", false, 10)
        ))

        val root = hierarchy.children("")
        assertEquals(listOf("docs", "root.txt"), root.map { it.name })
        assertTrue(root.first().directory)
        assertEquals(2, root.first().childCount)
        assertFalse(root.last().directory)

        val docs = hierarchy.children("docs")
        assertEquals(listOf("manual", "readme.txt"), docs.map { it.name })
        assertEquals("", hierarchy.parent("docs"))
        assertEquals("docs", hierarchy.parent("docs/manual"))
    }

    @Test
    fun explicitDirectoriesMergeWithoutDuplicates() {
        val hierarchy = ArchiveHierarchy(listOf(
            ArchiveEntryInfo("a", true),
            ArchiveEntryInfo("a/b", true),
            ArchiveEntryInfo("a/b/file.bin", false, 5),
            ArchiveEntryInfo("a/b", true)
        ))

        assertEquals(listOf("a"), hierarchy.children("").map { it.name })
        assertEquals(listOf("b"), hierarchy.children("a").map { it.name })
        assertEquals(1, hierarchy.children("a").single().childCount)
    }

    @Test
    fun foldersSortBeforeFilesCaseInsensitively() {
        val hierarchy = ArchiveHierarchy(listOf(
            ArchiveEntryInfo("z.txt", false),
            ArchiveEntryInfo("Beta/file.txt", false),
            ArchiveEntryInfo("alpha", true),
            ArchiveEntryInfo("A.txt", false)
        ))

        assertEquals(
            listOf("alpha", "Beta", "A.txt", "z.txt"),
            hierarchy.children("").map { it.name }
        )
    }
}
