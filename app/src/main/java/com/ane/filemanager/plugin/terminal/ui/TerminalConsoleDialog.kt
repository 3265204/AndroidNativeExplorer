package com.ane.filemanager.plugin.terminal.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.input.input
import com.ane.filemanager.plugin.api.ui.AnePluginPage
import com.ane.filemanager.plugin.api.ui.AneTypography
import com.ane.filemanager.plugin.api.ui.ui
import java.io.File

/** Composes terminal-owned content inside the page supplied by the host UI service. */
internal class TerminalConsoleDialog(
    private val host: PluginHost,
    private val startDirectory: File,
    private val onDismissed: () -> Unit
) {
    private val activity = host.activity
    private val ui = host.ui
    private val input = host.input
    private lateinit var page: AnePluginPage
    private lateinit var terminalView: TerminalView
    private lateinit var sessionController: TerminalSessionController
    private var shown = false

    fun show() {
        if (shown) return
        shown = true
        terminalView = buildTerminal()
        sessionController = TerminalSessionController(host, startDirectory, terminalView)
        page = ui.page(
            title = startDirectory.name.ifBlank { startDirectory.absolutePath },
            closeDescription = activity.getString(R.string.terminal_close),
            onClosed = {
                shown = false
                sessionController.close()
                onDismissed()
            }
        )
        page.summary.text = activity.getString(
            R.string.terminal_start_directory,
            startDirectory.absolutePath
        )
        val keyBar = TerminalKeyBar(
            context = activity,
            ui = ui,
            input = input,
            send = terminalView::send,
            adjustFont = ::adjustFont,
            paste = ::paste
        )
        ui.populateConsolePage(page, terminalView, keyBar.view)
        page.show()
        terminalView.post {
            if (!shown) return@post
            terminalView.requestFocus()
            sessionController.start()
        }
    }

    fun dismiss() {
        if (::sessionController.isInitialized) sessionController.close()
        if (::page.isInitialized) page.close()
    }

    private fun buildTerminal(): TerminalView {
        val textSp = terminalPreferences()
            .getInt(PREFERENCE_FONT_SP, AneTypography.terminalTextSp(activity))
            .coerceIn(10, 22)
        return TerminalView(
            context = activity,
            palette = ui.theme,
            input = input,
            initialTextSizeSp = textSp,
            copySelection = ::copySelection,
            paste = ::paste
        )
    }

    private fun adjustFont(delta: Int) {
        terminalView.setTextSizeSp(terminalView.currentTextSizeSp + delta)
        terminalPreferences().edit()
            .putInt(PREFERENCE_FONT_SP, terminalView.currentTextSizeSp)
            .apply()
        terminalView.requestFocus()
    }

    private fun paste() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf {
            it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        }?.getItemAt(0)
        item?.coerceToText(activity)?.toString()?.let(terminalView::send)
        terminalView.requestFocus()
    }

    private fun copySelection(value: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(TERMINAL_CLIP_LABEL, value))
        host.toast(activity.getString(R.string.terminal_copied))
        terminalView.requestFocus()
    }

    private fun terminalPreferences() =
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "ane-terminal"
        const val PREFERENCE_FONT_SP = "font-sp"
        const val TERMINAL_CLIP_LABEL = "ANE terminal"
    }
}
