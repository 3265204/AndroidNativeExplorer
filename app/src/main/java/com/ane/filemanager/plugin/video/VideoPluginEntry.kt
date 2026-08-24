package com.ane.filemanager.plugin.video

import android.content.Intent
import com.ane.filemanager.plugin.video.ui.VideoPlayerActivity
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost

class VideoPluginEntry : AnePlugin {
    override fun supports(file: PluginFile) = file.extension in EXTENSIONS

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        host.activity.startActivity(Intent(host.activity, VideoPlayerActivity::class.java)
            .putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, file.path))
        return true
    }

    private companion object {
        val EXTENSIONS = setOf("mp4", "m4v", "3gp", "3gpp", "webm", "mkv", "m2ts", "mts", "mov", "avi")
    }
}
