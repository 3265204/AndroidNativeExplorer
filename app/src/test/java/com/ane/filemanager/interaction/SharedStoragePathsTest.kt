package com.ane.filemanager.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SharedStoragePathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `resolves original files with spaces and non Latin names`() {
        val root = temporary.newFolder("primary")
        val folder = File(root, "Download/QQ").apply { mkdirs() }
        val file = File(folder, "测试 file.txt").apply { writeText("test") }.canonicalFile
        val paths = SharedStoragePaths(root, emptyList())
        assertEquals(file, paths.readable(file.path))
        assertEquals(file, paths.externalDocument("primary:Download/QQ/测试 file.txt"))
        assertEquals(folder.canonicalFile, paths.readable(folder.path))
    }

    @Test fun `resolves own roots removable volumes and home documents`() {
        val root = temporary.newFolder("primary")
        val volume = temporary.newFolder("ABCD-1234")
        val document = File(root, "Documents/note.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("test") }
        val removable = File(volume, "note.txt").apply { writeText("test") }.canonicalFile
        val paths = SharedStoragePaths(root, listOf(volume))
        assertEquals(root.canonicalFile, paths.ownDocument("root"))
        assertEquals(document.canonicalFile, paths.externalDocument("home:note.txt"))
        assertEquals(removable, paths.ownDocument("volume:ABCD-1234:note.txt"))
        assertEquals(removable, paths.externalDocument("ABCD-1234:note.txt"))
        assertNull(paths.externalDocument("missing-volume:note.txt"))
    }

    @Test fun `original accessible private paths are allowed without becoming storage volumes`() {
        val root = temporary.newFolder("shared")
        val privateFolder = temporary.newFolder("private-app", "files")
        val file = File(privateFolder, "note.txt").apply { writeText("test") }.canonicalFile
        val paths = SharedStoragePaths(root, emptyList())
        assertEquals(file, paths.readableOriginal(file.path))
        assertEquals(privateFolder.canonicalFile, paths.readableOriginal(privateFolder.path))
        assertNull(paths.readable(file.path))
        assertNull(paths.externalDocument("raw:${file.path}"))
        assertNull(paths.readableOriginal(File(privateFolder, "missing.txt").path))
    }

    @Test fun `rejects traversal sibling prefixes missing paths and symlink escapes`() {
        val root = temporary.newFolder("primary")
        val sibling = temporary.newFolder("primary-other")
        val outside = File(sibling, "note.txt").apply { writeText("test") }
        Files.createSymbolicLink(File(root, "escape").toPath(), sibling.toPath())
        val paths = SharedStoragePaths(root, emptyList())
        assertNull(paths.readable(outside.path))
        assertNull(paths.readable("relative.txt"))
        assertNull(paths.readable(File(root, "missing.txt").path))
        assertNull(paths.ownDocument("../primary-other/note.txt"))
        assertNull(paths.externalDocument("primary:../primary-other/note.txt"))
        assertNull(paths.externalDocument("primary:escape/note.txt"))
        assertNull(paths.externalDocument("raw:${outside.path}"))
    }

    @Test fun `does not accept another mounted volume through a traversing document id`() {
        val root = temporary.newFolder("primary")
        val volume = temporary.newFolder("ABCD-1234")
        File(volume, "file.txt").writeText("test")
        val paths = SharedStoragePaths(root, listOf(volume))
        assertNull(paths.externalDocument("primary:../ABCD-1234/file.txt"))
        assertNull(paths.ownDocument("../ABCD-1234/file.txt"))
    }
}
