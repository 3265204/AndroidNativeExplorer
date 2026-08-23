package com.ane.filemanager.ui.render

internal object FileSizeFormatter {
    fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
        else -> "%.1f GB".format(bytes / 1024f / 1024f / 1024f)
    }
}
