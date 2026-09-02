package com.ane.filemanager.plugin.audio

import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.audio.ui.AudioPlayerActivity

class AudioPluginEntry : AneIntentPluginEntry(
    AudioPlayerActivity::class.java,
    AudioPluginFiles::supports
)

internal object AudioPluginFiles {
    private val extensions = setOf(
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mid", "midi"
    )

    fun supports(file: PluginFile): Boolean = file.extension.lowercase() in extensions
}
