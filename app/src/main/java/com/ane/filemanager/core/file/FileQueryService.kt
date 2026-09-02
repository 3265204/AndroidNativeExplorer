package com.ane.filemanager.core.file

import java.io.File

/** Read-only file discovery shared by UI search and future natural-language actions. */
internal object FileQueryService {
    fun matchingName(items: List<File>, query: String): List<File> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return items.filter { it.name.contains(needle, ignoreCase = true) }
    }
}
