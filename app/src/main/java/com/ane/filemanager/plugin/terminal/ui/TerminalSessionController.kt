package com.ane.filemanager.plugin.terminal.ui

import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.PluginTerminalListener
import com.ane.filemanager.plugin.api.PluginTerminalRequest
import com.ane.filemanager.plugin.api.PluginTerminalSession
import java.io.File

/** Owns the PTY session and keeps background callbacks away from the View state. */
internal class TerminalSessionController(
    private val host: PluginHost,
    private val startDirectory: File,
    private val terminalView: TerminalView
) {
    private val activity = host.activity
    @Volatile private var session: PluginTerminalSession? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        terminalView.attach(
            writer = { bytes -> session?.write(bytes) },
            resize = { rows, columns -> session?.resize(rows, columns) }
        )
        val request = PluginTerminalRequest(
            executable = "/system/bin/sh",
            arguments = listOf("-i"),
            workingDirectory = startDirectory.absolutePath,
            environment = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor"),
            rows = terminalView.currentRows,
            columns = terminalView.currentColumns
        )
        var openFailed = false
        session = runCatching {
            host.openTerminal(request, object : PluginTerminalListener {
                override fun onOutput(bytes: ByteArray) = feed(bytes)

                override fun onExit(exitCode: Int?, signal: Int?) = feedLine(
                    activity.getString(R.string.terminal_shell_closed)
                )

                override fun onError(message: String) = feedLine(
                    "${activity.getString(R.string.terminal_unavailable)}: $message"
                )
            })
        }.getOrElse {
            openFailed = true
            feedLine("${activity.getString(R.string.terminal_unavailable)}: ${it.message.orEmpty()}")
            null
        }
        if (session == null && !openFailed) {
            feedLine(activity.getString(R.string.terminal_api_upgrade_required))
        } else {
            session?.resize(terminalView.currentRows, terminalView.currentColumns)
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    private fun feed(bytes: ByteArray) {
        val copy = bytes.copyOf()
        terminalView.post { terminalView.feed(copy) }
    }

    private fun feedLine(message: String) = feed("\r\n$message\r\n".toByteArray())
}
