package com.ane.filemanager.plugin.api.file

import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost

/** Ordered host-owned view of files adjacent to an opened file. */
interface AnePluginFileSequence {
    val current: PluginFile
    val positionLabel: String
    val hasPrevious: Boolean
    val hasNext: Boolean
    fun moveBy(delta: Int): PluginFile?
}

/** Read-only discovery capability shared by viewers, future search UI, and file agents. */
interface AnePluginFileQueries {
    fun resolve(path: String): PluginFile?

    fun siblingSequence(
        opened: PluginFile,
        accepts: (PluginFile) -> Boolean
    ): AnePluginFileSequence
}

/** Optional v3 provider, preserving the base [PluginHost] ABI. */
interface PluginFileQueryProvider {
    val pluginFileQueries: AnePluginFileQueries
}

val PluginHost.fileQueries: AnePluginFileQueries
    get() = (this as? PluginFileQueryProvider)?.pluginFileQueries
        ?: error("The current host does not provide the ANE file-query capability")
