package com.ane.filemanager.plugin.api

import android.app.Activity
import java.io.File

object PluginApi {
    const val VERSION = 2
}

/** Stable API implemented by both bundled and imported in-process plugins. */
interface AnePlugin {
    fun onLoad(host: PluginHost) = Unit
    fun onUnload() = Unit
    fun supports(file: PluginFile): Boolean
    fun open(file: PluginFile, host: PluginHost): Boolean = false
    fun fileActions(file: PluginFile, host: PluginHost): List<PluginFileAction> = emptyList()
}

/** Optional capability for actions that operate on the file manager's current selection. */
interface PluginSelectionActionProvider {
    fun selectionActions(files: List<PluginFile>, host: PluginHost): List<PluginFileAction>
}

data class PluginFile(
    val path: String,
    val name: String,
    val extension: String,
    val mimeType: String
) {
    fun toFile(): File = File(path)
}

data class PluginFileAction(val id: String, val label: String, val run: () -> Unit)

data class PluginTaskResult(
    val success: Boolean,
    val message: String? = null,
    val outputPath: String? = null,
    val outputCreated: Boolean = false
)

interface PluginHost {
    val activity: Activity
    /** Device language preferences, independent from the languages supported or selected by ANE. */
    val systemLocaleTags: List<String>
    /** ANE's current UI language preferences. Plugins may use this for visual consistency, but need not. */
    val hostLocaleTags: List<String>
    fun toast(message: String)
    fun requestPassword(title: String, callback: (CharArray?) -> Unit)
    fun execute(label: String, task: () -> PluginTaskResult, callback: (PluginTaskResult) -> Unit = {})
    fun reportOutput(path: String, created: Boolean)
}
