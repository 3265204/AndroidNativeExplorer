package com.ane.filemanager.navigation

import java.io.File

/** Keeps pinned locations and temporary browsing sessions alive independently. */
internal class DockSessionController(
    initialDirectory: File,
    initialTabs: List<BrowserTab>,
    private val labelFor: (File) -> String,
    activeDirectory: File = initialDirectory,
    private val onChanged: () -> Unit = {}
) {
    val tabs = mutableListOf<BrowserTab>()
    var activeIndex = 0
        private set

    val currentTab get() = tabs[activeIndex]
    val currentDirectory get() = currentTab.directory

    init {
        tabs += initialTabs.map { tab ->
            tab.copy(history = java.util.ArrayDeque(tab.history))
        }
        val existing = find(activeDirectory)
        if (existing >= 0) activeIndex = existing
        else {
            tabs += BrowserTab(labelFor(initialDirectory), initialDirectory, false)
            activeIndex = tabs.lastIndex
        }
    }

    fun switchTo(index: Int): Boolean {
        if (index !in tabs.indices || index == activeIndex) return false
        activeIndex = index
        onChanged()
        return true
    }

    fun navigateTo(directory: File): Boolean {
        if (same(currentDirectory, directory)) return false
        val existing = find(directory)
        if (existing >= 0) {
            activeIndex = existing
            onChanged()
            return true
        }
        if (currentTab.pinned) {
            tabs += BrowserTab(labelFor(directory), directory, false)
            activeIndex = tabs.lastIndex
        } else {
            currentTab.history.addLast(currentTab.directory)
            currentTab.directory = directory
            currentTab.label = labelFor(directory)
        }
        onChanged()
        return true
    }

    fun goBack(): Boolean {
        val tab = currentTab
        if (tab.history.isEmpty()) return false
        tab.directory = tab.history.removeLast()
        tab.label = labelFor(tab.directory)
        onChanged()
        return true
    }

    /** Moves one directory upward without adding a new history entry. */
    fun navigateBackTo(directory: File): Boolean {
        if (same(currentDirectory, directory)) return false
        val previousTab = currentTab
        if (previousTab.history.peekLast()?.let { same(it, directory) } == true) {
            previousTab.history.removeLast()
        }
        val existing = find(directory)
        when {
            existing >= 0 -> activeIndex = existing
            previousTab.pinned -> {
                tabs += BrowserTab(labelFor(directory), directory, false)
                activeIndex = tabs.lastIndex
            }
            else -> {
                previousTab.directory = directory
                previousTab.label = labelFor(directory)
            }
        }
        onChanged()
        return true
    }

    fun pin(index: Int) {
        if (index !in tabs.indices || tabs[index].pinned) return
        val active = currentTab
        val tab = tabs.removeAt(index).apply { pinned = true }
        val insertion = tabs.indexOfFirst { !it.pinned }.let { if (it < 0) tabs.size else it }
        tabs.add(insertion, tab)
        activeIndex = tabs.indexOf(active)
        onChanged()
    }

    fun unpin(index: Int) {
        // Storage is the permanent anchor and must remain fixed at index zero.
        if (index !in tabs.indices || index == 0 || !tabs[index].pinned) return
        val active = currentTab
        val tab = tabs.removeAt(index).apply { pinned = false }
        tabs += tab
        activeIndex = tabs.indexOf(active)
        onChanged()
    }

    fun close(index: Int): Boolean {
        if (index !in tabs.indices || tabs[index].pinned || tabs.size <= 1) return false
        return removeAt(index)
    }

    private fun removeAt(index: Int): Boolean {
        val wasActive = index == activeIndex
        tabs.removeAt(index)
        activeIndex = when {
            tabs.isEmpty() -> 0
            wasActive -> (index - 1).coerceIn(0, tabs.lastIndex)
            index < activeIndex -> activeIndex - 1
            else -> activeIndex
        }
        onChanged()
        return true
    }

    /** Closes every temporary tab while preserving all pinned locations and one usable tab. */
    fun closeTemporaryTabs(): Int {
        val temporaryCount = tabs.count { !it.pinned }
        if (temporaryCount == 0) return 0
        val active = currentTab
        val pinned = tabs.filter(BrowserTab::pinned)
        val closeableCount = temporaryCount - if (pinned.isEmpty()) 1 else 0
        if (closeableCount <= 0) return 0
        val retained = if (pinned.isNotEmpty()) pinned else listOf(active)
        tabs.clear()
        tabs += retained
        activeIndex = tabs.indexOf(active).takeIf { it >= 0 } ?: 0
        onChanged()
        return closeableCount
    }

    fun rename(index: Int, label: String) {
        if (index in tabs.indices) {
            tabs[index].label = label
            onChanged()
        }
    }

    fun indexOfDirectory(directory: File): Int = find(directory)

    /** Rebinds one tab to a different readable directory without keeping stale back history. */
    fun changeDirectory(index: Int, directory: File): Boolean {
        if (index !in tabs.indices || index == 0 || !directory.isDirectory || !directory.canRead()) return false
        val existing = find(directory)
        if (existing >= 0 && existing != index) return false
        val tab = tabs[index]
        if (same(tab.directory, directory)) return false
        tab.directory = directory
        tab.history.clear()
        onChanged()
        return true
    }

    /** Reorders tabs while keeping the pinned storage tab locked at index zero. */
    fun moveTab(fromIndex: Int, toIndex: Int): Int {
        if (fromIndex !in tabs.indices || fromIndex == 0 || tabs.size < 2) return fromIndex
        val destination = toIndex.coerceIn(1, tabs.lastIndex)
        if (destination == fromIndex) return fromIndex
        val active = currentTab
        val moving = tabs.removeAt(fromIndex)
        tabs.add(destination.coerceAtMost(tabs.size), moving)
        activeIndex = tabs.indexOf(active)
        onChanged()
        return tabs.indexOf(moving)
    }

    private fun find(directory: File): Int = tabs.indexOfFirst { same(it.directory, directory) }

    private fun same(left: File, right: File): Boolean = try {
        left.canonicalFile == right.canonicalFile
    } catch (_: Exception) {
        left.absolutePath == right.absolutePath
    }
}
