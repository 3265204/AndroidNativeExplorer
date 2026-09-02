package com.ane.filemanager.plugin.image

import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.image.ui.ImageActivity

class ImagePluginEntry : AneIntentPluginEntry(ImageActivity::class.java, ImagePluginFiles::supports)

internal object ImagePluginFiles {
    private val extensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif"
    )

    fun supports(file: PluginFile): Boolean = file.extension.lowercase() in extensions
}
