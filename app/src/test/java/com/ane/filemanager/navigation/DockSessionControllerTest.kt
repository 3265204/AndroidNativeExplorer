package com.ane.filemanager.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

class DockSessionControllerTest {
    @Test
    fun `closing temporary tabs keeps pinned tabs and falls back from active temporary tab`() {
        val root = File("/storage")
        val pinned = BrowserTab("Storage", root, pinned = true)
        val secondPinned = BrowserTab("Documents", File(root, "Documents"), pinned = true)
        val temporary = BrowserTab("Work", File(root, "Work"), pinned = false)
        val controller = controller(root, listOf(pinned, secondPinned, temporary), temporary.directory)

        val closed = controller.closeTemporaryTabs()

        assertEquals(1, closed)
        assertEquals(listOf(root, secondPinned.directory), controller.tabs.map(BrowserTab::directory))
        assertEquals(root, controller.currentTab.directory)
    }

    @Test
    fun `closing temporary tabs preserves an active pinned tab`() {
        val root = File("/storage")
        val pinned = BrowserTab("Storage", root, pinned = true)
        val temporaryOne = BrowserTab("One", File(root, "One"), pinned = false)
        val temporaryTwo = BrowserTab("Two", File(root, "Two"), pinned = false)
        val controller = controller(root, listOf(pinned, temporaryOne, temporaryTwo), root)

        val closed = controller.closeTemporaryTabs()

        assertEquals(2, closed)
        assertEquals(listOf(root), controller.tabs.map(BrowserTab::directory))
        assertEquals(root, controller.currentTab.directory)
    }

    @Test
    fun `storage anchor cannot be unpinned`() {
        val root = File("/storage")
        val pinned = BrowserTab("Storage", root, pinned = true)
        val controller = controller(root, listOf(pinned), root)

        controller.unpin(0)

        assertTrue(controller.tabs.single().pinned)
        assertFalse(controller.close(0))
    }

    @Test
    fun `changing a tab directory clears stale history and rejects duplicate directories`() {
        val base = Files.createTempDirectory("ane-tabs").toFile()
        val root = File(base, "storage").apply { mkdirs() }
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        val target = File(root, "target").apply { mkdirs() }
        val history = ArrayDeque<File>().apply { addLast(root) }
        val controller = controller(root, listOf(
            BrowserTab("Storage", root, pinned = true),
            BrowserTab("First", first, history = history),
            BrowserTab("Second", second)
        ), first)

        assertTrue(controller.changeDirectory(1, target))
        assertEquals(target.canonicalFile, controller.tabs[1].directory.canonicalFile)
        assertTrue(controller.tabs[1].history.isEmpty())
        assertFalse(controller.changeDirectory(1, second))

        base.deleteRecursively()
    }

    @Test
    fun `reordering tabs keeps storage anchored and preserves the active tab`() {
        val root = File("/storage")
        val first = File(root, "first")
        val second = File(root, "second")
        val third = File(root, "third")
        val controller = controller(root, listOf(
            BrowserTab("Storage", root, pinned = true),
            BrowserTab("First", first, pinned = true),
            BrowserTab("Second", second),
            BrowserTab("Third", third)
        ), second)

        assertEquals(1, controller.moveTab(3, 1))
        assertEquals(listOf(root, third, first, second), controller.tabs.map(BrowserTab::directory))
        assertEquals(second, controller.currentDirectory)
        assertEquals(0, controller.moveTab(0, 3))
        assertEquals(root, controller.tabs.first().directory)
    }

    private fun controller(root: File, tabs: List<BrowserTab>, active: File) = DockSessionController(
        initialDirectory = root,
        initialTabs = tabs,
        labelFor = File::getName,
        activeDirectory = active
    )
}
