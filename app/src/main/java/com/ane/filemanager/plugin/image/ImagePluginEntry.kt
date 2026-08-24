package com.ane.filemanager.plugin.image

import android.content.Intent
import com.ane.filemanager.plugin.image.ui.ImageActivity
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost

class ImagePluginEntry : AnePlugin {
    override fun supports(file: PluginFile) = file.extension in EXTENSIONS

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        host.activity.startActivity(Intent(host.activity, ImageActivity::class.java)
            .putExtra(ImageActivity.EXTRA_FILE_PATH, file.path))
        return true
    }

    private companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    }
}
