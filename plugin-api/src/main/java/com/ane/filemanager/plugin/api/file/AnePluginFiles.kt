package com.ane.filemanager.plugin.api.file

import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost
import java.io.IOException

enum class PluginTextEncoding {
    UTF8,
    UTF8_BOM,
    UTF16_LE,
    UTF16_BE
}

data class PluginTextDocument(
    val text: String,
    val encoding: PluginTextEncoding
)

class PluginTextTooLargeException(
    val maximumBytes: Long,
    val actualBytes: Long
) : IOException("Text file is $actualBytes bytes; maximum is $maximumBytes bytes")

/** File-content capability. The host decides which backend performs the actual I/O. */
interface AnePluginFiles {
    fun readText(
        file: PluginFile,
        maximumBytes: Long = DEFAULT_MAX_TEXT_BYTES
    ): PluginTextDocument

    fun writeText(
        file: PluginFile,
        text: String,
        encoding: PluginTextEncoding
    )

    companion object {
        const val DEFAULT_MAX_TEXT_BYTES = 16L * 1024L * 1024L
    }
}

/** Optional v3 capability implemented by the current host without changing PluginHost's ABI. */
interface PluginFileServiceProvider {
    val pluginFiles: AnePluginFiles
}

val PluginHost.files: AnePluginFiles
    get() = (this as? PluginFileServiceProvider)?.pluginFiles
        ?: error("The current host does not provide the ANE file capability")
