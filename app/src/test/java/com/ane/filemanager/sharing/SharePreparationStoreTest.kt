package com.ane.filemanager.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class SharePreparationStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `multiple selected items become one temporary zip file`() {
        val sourceRoot = temporary.newFolder("sources")
        val folder = File(sourceRoot, "photos").apply { mkdir() }
        File(folder, "nested").mkdir()
        File(folder, "nested/image.txt").writeText("pixels")
        val note = File(sourceRoot, "note.txt").apply { writeText("hello") }
        val store = SharePreparationStore(temporary.newFolder("share-temp"))

        val prepared = store.prepare(listOf(folder, note))

        assertEquals(listOf("shared-files.zip"), prepared.files.map(File::getName))
        ZipFile(prepared.files.single()).use { zip ->
            assertTrue(zip.getEntry("photos/").isDirectory)
            assertEquals(
                "pixels",
                zip.getInputStream(zip.getEntry("photos/nested/image.txt")).bufferedReader().readText()
            )
            assertEquals("hello", zip.getInputStream(zip.getEntry("note.txt")).bufferedReader().readText())
        }
    }

    @Test
    fun `single ordinary file is shared without a temporary copy`() {
        val file = temporary.newFile("note.txt").apply { writeText("hello") }
        val store = SharePreparationStore(temporary.newFolder("share-temp"))

        val prepared = store.prepare(listOf(file))

        assertEquals(listOf(file), prepared.files)
        assertEquals(null, prepared.sessionDirectory)
    }

    @Test
    fun `temporary archive is removed after receiver closes it`() {
        val folder = temporary.newFolder("folder")
        File(folder, "item.txt").writeText("item")
        val store = SharePreparationStore(temporary.newFolder("share-temp"))
        val prepared = store.prepare(listOf(folder))
        val archive = prepared.files.single()

        store.removeAfterRead(archive)

        assertFalse(archive.exists())
        assertFalse(prepared.sessionDirectory!!.exists())
        assertTrue(folder.exists())
    }
}
