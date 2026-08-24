package com.ane.filemanager.ui.sort

import android.content.Context
import java.io.File
import java.text.Collator

internal enum class FileSortMode { NAME, MODIFIED, SIZE, LAST_OPENED }

/** Persists the selected ordering and app-owned last-opened timestamps. */
internal class FileSortController(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var mode: FileSortMode = preferences.getString(KEY_MODE, null)
        ?.let { value -> FileSortMode.entries.firstOrNull { it.name == value } }
        ?: FileSortMode.NAME
        private set

    fun select(value: FileSortMode) {
        if (mode == value) return
        mode = value
        preferences.edit().putString(KEY_MODE, value.name).apply()
    }

    fun markOpened(file: File) {
        preferences.edit().putLong(openedKey(file), System.currentTimeMillis()).apply()
    }

    fun openedAt(file: File): Long = preferences.getLong(openedKey(file), 0L)

    fun sorted(
        files: List<File>,
        selectedMode: FileSortMode = mode,
        collator: Collator
    ): List<File> = FileSorter.sorted(files, selectedMode, ::openedAt, collator)

    private fun openedKey(file: File): String = "$OPENED_PREFIX${file.absolutePath}"

    private companion object {
        const val PREFERENCES = "file_sorting"
        const val KEY_MODE = "mode"
        const val OPENED_PREFIX = "opened:"
    }
}

internal object FileSorter {
    fun sorted(
        files: List<File>,
        mode: FileSortMode,
        openedAt: (File) -> Long,
        collator: Comparator<in String>
    ): List<File> {
        val entries = files.map { file ->
            SortEntry(
                file = file,
                directory = file.isDirectory,
                value = when (mode) {
                    FileSortMode.NAME -> 0L
                    FileSortMode.MODIFIED -> file.lastModified()
                    FileSortMode.SIZE -> file.length()
                    FileSortMode.LAST_OPENED -> openedAt(file)
                }
            )
        }
        return entries.sortedWith { left, right ->
        when {
            left.directory != right.directory -> if (left.directory) -1 else 1
            else -> compareValue(left, right, mode).takeIf { it != 0 }
                ?: collator.compare(left.file.name, right.file.name).takeIf { it != 0 }
                ?: left.file.name.compareTo(right.file.name)
        }
        }.map(SortEntry::file)
    }

    private fun compareValue(left: SortEntry, right: SortEntry, mode: FileSortMode): Int = when (mode) {
        FileSortMode.NAME -> 0
        else -> right.value.compareTo(left.value)
    }

    private data class SortEntry(val file: File, val directory: Boolean, val value: Long)
}
