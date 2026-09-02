package com.ane.filemanager.plugin.api.file

import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost
import java.io.Closeable

/** Writable staging object whose final placement and history are owned by the host. */
interface AnePluginOutputSession : Closeable {
    val stagingPath: String
    fun commit(): PluginFile
    override fun close()
}

interface AnePluginOutputs {
    fun begin(
        parentDirectory: PluginFile,
        suggestedName: String
    ): AnePluginOutputSession
}

/** Optional v3 provider, preserving the base [PluginHost] ABI. */
interface PluginOutputProvider {
    val pluginOutputs: AnePluginOutputs
}

val PluginHost.outputs: AnePluginOutputs
    get() = (this as? PluginOutputProvider)?.pluginOutputs
        ?: error("The current host does not provide the ANE output capability")
