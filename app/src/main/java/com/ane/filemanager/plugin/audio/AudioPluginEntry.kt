package com.ane.filemanager.plugin.audio

import android.content.Intent
import com.ane.filemanager.plugin.audio.ui.AudioPlayerActivity
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost

class AudioPluginEntry : AnePlugin {
    override fun supports(file: PluginFile) = file.extension in EXTENSIONS

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        host.activity.startActivity(Intent(host.activity, AudioPlayerActivity::class.java)
            .putExtra(AudioPlayerActivity.EXTRA_FILE_PATH, file.path))
        return true
    }

    private companion object {
        val EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mid", "midi")
    }
}
