package com.ane.filemanager.plugin.terminal.ui

import android.content.Context
import android.widget.HorizontalScrollView
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.input.AnePluginInput
import com.ane.filemanager.plugin.api.input.PluginTerminalKey
import com.ane.filemanager.plugin.api.ui.AnePluginUi
import com.ane.filemanager.plugin.api.ui.AneUiAction

/** Screen-key actions; rendering and byte sequences are both supplied by host capabilities. */
internal class TerminalKeyBar(
    private val context: Context,
    private val ui: AnePluginUi,
    private val input: AnePluginInput,
    private val send: (ByteArray) -> Unit,
    private val adjustFont: (Int) -> Unit,
    private val paste: () -> Unit
) {
    val view: HorizontalScrollView = ui.compactButtonBar(context, buildActions())

    private fun buildActions() = listOf(
        shortcut(context.getString(R.string.terminal_escape), PluginTerminalKey.ESCAPE),
        shortcut(context.getString(R.string.terminal_tab), PluginTerminalKey.TAB),
        shortcut("↑", PluginTerminalKey.UP),
        shortcut("↓", PluginTerminalKey.DOWN),
        shortcut("←", PluginTerminalKey.LEFT),
        shortcut("→", PluginTerminalKey.RIGHT),
        shortcut(context.getString(R.string.terminal_control_c), PluginTerminalKey.CONTROL_C),
        shortcut(context.getString(R.string.terminal_control_d), PluginTerminalKey.CONTROL_D),
        AneUiAction("A−") { adjustFont(-1) },
        AneUiAction("A+") { adjustFont(1) },
        AneUiAction(context.getString(R.string.terminal_paste), primary = true, run = paste)
    )

    private fun shortcut(
        label: String,
        shortcut: PluginTerminalKey
    ) = AneUiAction(label) { send(input.terminalShortcut(shortcut)) }
}
