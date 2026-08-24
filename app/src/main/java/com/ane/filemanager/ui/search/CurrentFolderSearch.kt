package com.ane.filemanager.ui.search

import java.io.File

/** Searches the already loaded current-folder snapshot without starting another filesystem scan. */
internal object CurrentFolderSearch {
    fun matches(items: List<File>, query: String): List<File> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return items.filter { it.name.contains(needle, ignoreCase = true) }
    }
}
