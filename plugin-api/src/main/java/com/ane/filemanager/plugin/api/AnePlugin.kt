package com.ane.filemanager.plugin.api

import android.app.Activity
import java.io.File

object PluginApi {
    const val VERSION = 3
    const val MIN_SUPPORTED_VERSION = 2

    fun supports(version: Int): Boolean = version in MIN_SUPPORTED_VERSION..VERSION
}

/** Stable API implemented by both bundled and imported in-process plugins. */
interface AnePlugin {
    fun onLoad(host: PluginHost) = Unit
    fun onUnload() = Unit
    fun supports(file: PluginFile): Boolean
    fun open(file: PluginFile, host: PluginHost): Boolean = false
    /** Actions automatically routed to the matched file's long-press menu. */
    fun fileActions(file: PluginFile, host: PluginHost): List<PluginFileAction> = emptyList()
}

/** Optional selected-file actions automatically grouped under Tools in the selection menu. */
interface PluginSelectionActionProvider {
    fun selectionActions(files: List<PluginFile>, host: PluginHost): List<PluginFileAction>
}

/** Optional current-directory actions automatically grouped under Tools in the plus menu. */
interface PluginDirectoryActionProvider {
    fun directoryActions(directory: PluginFile, host: PluginHost): List<PluginFileAction>
}

/** Optional visual hint. The plugin remains the owner of file-type recognition. */
interface PluginFileIconProvider {
    fun fileIcon(file: PluginFile): PluginFileIcon?
}

enum class PluginFileIcon {
    ARCHIVE
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
    /** API v3: opens a real PTY owned by the host. Callbacks arrive on a background thread. */
    fun openTerminal(
        request: PluginTerminalRequest,
        listener: PluginTerminalListener
    ): PluginTerminalSession? = null
}

data class PluginTerminalRequest(
    val executable: String = "/system/bin/sh",
    val arguments: List<String> = emptyList(),
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
    val rows: Int = 24,
    val columns: Int = 80
)

interface PluginTerminalListener {
    fun onOutput(bytes: ByteArray)
    fun onExit(exitCode: Int?, signal: Int?)
    fun onError(message: String)
}

interface PluginTerminalSession : java.io.Closeable {
    val isOpen: Boolean
    fun write(bytes: ByteArray): Boolean
    fun resize(rows: Int, columns: Int)
    fun sendSignal(signal: Int): Boolean
}

object PluginTerminalSignal {
    const val INTERRUPT = 2
    const val QUIT = 3
    const val TERMINATE = 15
    const val WINDOW_CHANGED = 28
}
