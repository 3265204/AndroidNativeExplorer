package com.ane.filemanager.ui.selection

import java.io.File

/** Owns selection state and click semantics; it performs no drawing or file mutation. */
internal class FileSelectionController(
    private val openFile: (File) -> Unit,
    private val openDirectory: (File) -> Unit,
    private val invalidate: () -> Unit,
    private val doubleClickTimeoutMs: Long,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / NANOSECONDS_PER_MILLISECOND },
    private val onSelectionChanged: (List<File>) -> Unit = {}
) {
    private val selected = linkedSetOf<String>()
    private val slideVisited = hashSetOf<String>()
    private var slideSelectAdd = true
    private var lastClickPath: String? = null
    private var lastClickTime = 0L

    var multiSelect: Boolean = false
        private set

    val paths: Set<String> get() = selected
    val size get() = selected.size
    val isEmpty get() = selected.isEmpty()

    fun contains(file: File) = file.absolutePath in selected
    fun files() = selected.map(::File).filter(File::exists)

    fun retain(items: List<File>) {
        if (selected.retainAll(items.mapTo(hashSetOf()) { it.absolutePath })) changed()
    }

    fun enterMultiSelect() {
        multiSelect = true
        changed()
    }

    fun exitMultiSelect() {
        multiSelect = false
        clear()
    }

    fun clear() {
        selected.clear()
        changed()
    }

    fun resetClickSequence() {
        lastClickPath = null
        lastClickTime = 0L
    }

    fun replace(file: File?) {
        selected.clear()
        if (file != null) selected += file.absolutePath
        changed()
    }

    fun set(file: File, shouldSelect: Boolean) {
        if (shouldSelect) selected += file.absolutePath else selected -= file.absolutePath
        changed()
    }

    fun selectOnLongPress(file: File) {
        if (!multiSelect) selected.clear()
        selected += file.absolutePath
        changed()
    }

    fun prepareContext(file: File) {
        if (!multiSelect) replace(file)
        else if (!contains(file)) set(file, true)
    }

    fun click(file: File) {
        if (multiSelect) {
            resetClickSequence()
            set(file, !contains(file))
            return
        }
        val now = monotonicTimeMs()
        val doubleClick = lastClickPath == file.absolutePath &&
            now - lastClickTime <= doubleClickTimeoutMs
        if (doubleClick) {
            resetClickSequence()
            if (file.isDirectory) openDirectory(file) else openFile(file)
            return
        }
        lastClickPath = file.absolutePath
        lastClickTime = now
        replace(file)
    }

    fun selectAll(items: List<File>) {
        if (items.isEmpty()) return
        multiSelect = true
        selected.clear()
        selected += items.map(File::getAbsolutePath)
        changed()
    }

    fun beginSlide(file: File) {
        slideVisited.clear()
        slideSelectAdd = !contains(file)
        applySlide(file)
    }

    fun applySlide(file: File) {
        if (!slideVisited.add(file.absolutePath)) return
        set(file, slideSelectAdd)
    }

    fun endSlide() {
        slideVisited.clear()
    }

    fun dragFiles(origin: File?): List<File> {
        origin ?: return emptyList()
        return if (contains(origin) && size > 1) files() else listOf(origin)
    }

    private fun changed() {
        invalidate()
        onSelectionChanged(files())
    }

    private companion object {
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
    }
}
