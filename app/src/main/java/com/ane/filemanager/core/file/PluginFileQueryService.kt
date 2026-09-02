package com.ane.filemanager.core.file

import android.webkit.MimeTypeMap
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.file.AnePluginFileQueries
import com.ane.filemanager.plugin.api.file.AnePluginFileSequence
import java.io.File

/** Host implementation of read-only file discovery exposed to plugins. */
internal object PluginFileQueryService : AnePluginFileQueries {
    override fun resolve(path: String): PluginFile? = File(path)
        .takeIf(File::exists)
        ?.asPluginFile()

    override fun siblingSequence(
        opened: PluginFile,
        accepts: (PluginFile) -> Boolean
    ): AnePluginFileSequence {
        val openedFile = File(opened.path)
        val sequence = SiblingFileSequence.create(openedFile) { candidate ->
            accepts(candidate.asPluginFile())
        }
        return object : AnePluginFileSequence {
            override val current get() = sequence.current.asPluginFile()
            override val positionLabel get() = sequence.positionLabel
            override val hasPrevious get() = sequence.hasPrevious
            override val hasNext get() = sequence.hasNext
            override fun moveBy(delta: Int): PluginFile? = sequence.moveBy(delta)?.asPluginFile()
        }
    }
}

internal fun File.asPluginFile(): PluginFile {
    val normalizedExtension = extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(normalizedExtension)
        ?: "application/octet-stream"
    return PluginFile(absolutePath, name, normalizedExtension, mime)
}
