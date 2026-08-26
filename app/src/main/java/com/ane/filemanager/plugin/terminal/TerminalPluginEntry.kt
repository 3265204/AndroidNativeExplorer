package com.ane.filemanager.plugin.terminal

import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginDirectoryActionProvider
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginFileAction
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.R
import com.ane.filemanager.plugin.terminal.ui.TerminalConsoleDialog
import java.io.File

class TerminalPluginEntry : AnePlugin, PluginDirectoryActionProvider {
    private val consoles = mutableSetOf<TerminalConsoleDialog>()

    override fun onUnload() {
        consoles.toList().forEach(TerminalConsoleDialog::dismiss)
        consoles.clear()
    }

    override fun supports(file: PluginFile): Boolean = false

    override fun open(file: PluginFile, host: PluginHost): Boolean = false

    override fun directoryActions(
        directory: PluginFile,
        host: PluginHost
    ): List<PluginFileAction> {
        val currentDirectory = directory.toFile().takeIf(File::isDirectory) ?: return emptyList()
        return listOf(action(currentDirectory, host))
    }

    private fun action(directory: File, host: PluginHost): PluginFileAction {
        return PluginFileAction(
            "terminal.open-here",
            host.activity.getString(R.string.terminal_open_here)
        ) {
            lateinit var console: TerminalConsoleDialog
            console = TerminalConsoleDialog(host, directory) {
                consoles -= console
            }
            consoles += console
            console.show()
        }
    }
}
