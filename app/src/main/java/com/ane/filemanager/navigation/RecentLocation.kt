package com.ane.filemanager.navigation

import java.io.File

/** A display-only location. Never create this path or use it as an output directory. */
internal object RecentLocation {
    val directory = File("/ane-virtual/recent")
    fun isRecent(file: File) = file.absolutePath == directory.absolutePath
}
