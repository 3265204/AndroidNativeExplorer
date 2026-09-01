package com.ane.filemanager.plugin.video

import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.video.ui.VideoPlayerActivity
import java.io.File

class VideoPluginEntry : AneIntentPluginEntry(
    VideoPlayerActivity::class.java,
    VideoPluginFiles::supports
)

internal object VideoPluginFiles {
    private val extensions = setOf(
        "mp4", "m4v", "3gp", "3gpp", "webm", "mkv", "m2ts", "mts", "mov", "avi"
    )

    fun supports(file: PluginFile): Boolean = file.extension.lowercase() in extensions
    fun accepts(file: File): Boolean = file.isFile && file.extension.lowercase() in extensions
}
