package com.ane.filemanager.navigation

import java.io.File
import java.util.ArrayDeque

internal data class BrowserTab(
    var label: String,
    var directory: File,
    var pinned: Boolean = false,
    val history: ArrayDeque<File> = ArrayDeque()
)
