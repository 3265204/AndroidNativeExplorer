package com.ane.filemanager.navigation

import java.io.File
import java.util.ArrayDeque

internal data class BrowserTab(
    var label: String,
    var directory: File,
    var pinned: Boolean = false,
    val history: ArrayDeque<File> = ArrayDeque(),
    val fixed: Boolean = false,
    val external: Boolean = false,
    /** A session-only browsing ceiling, used for imported files from other apps. */
    val navigationRoot: File? = null
)
