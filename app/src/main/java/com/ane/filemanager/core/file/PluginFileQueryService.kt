package com.ane.filemanager.core.file

import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.file.AnePluginFileQueries
import com.ane.filemanager.plugin.api.file.AnePluginFileSequence
import java.io.File

/** Adapts host files and sibling discovery to the plugin file-query API. */
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
    val normalizedExtension = FileTypeResolver.extension(this)
    val mime = FileTypeResolver.mimeType(normalizedExtension, "application/octet-stream")
    return PluginFile(absolutePath, name, normalizedExtension, mime)
}
