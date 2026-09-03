package com.ane.filemanager.core.file

import java.io.File

/** Case-insensitive filtering over a caller-provided file list; it never traverses directories. */
internal object FileQueryService {
    fun matchingName(items: List<File>, query: String): List<File> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return items.filter { it.name.contains(needle, ignoreCase = true) }
    }
}
