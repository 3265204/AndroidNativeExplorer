package com.ane.filemanager.pluginmanager.pty

import com.ane.filemanager.plugin.api.PluginTerminalListener
import com.ane.filemanager.plugin.api.PluginTerminalRequest
import com.ane.filemanager.plugin.api.PluginTerminalSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class HostPtyTerminalSession private constructor(
    private val handle: Long,
    private val listener: PluginTerminalListener
) : PluginTerminalSession {
    private val open = AtomicBoolean(true)
    private val nativeOperationLock = Any()
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "ane-pty-reader") }

    override val isOpen: Boolean
        get() = open.get()

    init {
        reader.execute(::readLoop)
    }

    override fun write(bytes: ByteArray): Boolean {
        if (!open.get() || bytes.isEmpty()) return false
        return synchronized(nativeOperationLock) {
            if (!open.get()) false else
                runCatching { NativePtyBridge.write(handle, bytes) == bytes.size }.getOrDefault(false)
        }
    }

    override fun resize(rows: Int, columns: Int) {
        if (!open.get()) return
        synchronized(nativeOperationLock) {
            if (open.get()) runCatching {
                NativePtyBridge.resize(handle, rows.coerceAtLeast(1), columns.coerceAtLeast(1))
            }
        }
    }

    override fun sendSignal(signal: Int): Boolean = synchronized(nativeOperationLock) {
        open.get() && runCatching { NativePtyBridge.signal(handle, signal) }.getOrDefault(false)
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        synchronized(nativeOperationLock) { runCatching { NativePtyBridge.terminate(handle) } }
        reader.shutdown()
    }

    private fun readLoop() {
        try {
            val buffer = ByteArray(8 * 1024)
            while (open.get()) {
                val count = NativePtyBridge.read(handle, buffer)
                if (count <= 0) break
                runCatching { listener.onOutput(buffer.copyOf(count)) }
            }
        } catch (error: Throwable) {
            if (open.get()) runCatching {
                listener.onError(error.message ?: "PTY read failed")
            }
        } finally {
            open.set(false)
            val status = synchronized(nativeOperationLock) {
                runCatching { NativePtyBridge.waitAndDestroy(handle) }.getOrDefault(-1L)
            }
            val exitCode = (status shr 32).toInt().takeIf { it >= 0 }
            val signal = status.toInt().takeIf { it >= 0 }
            runCatching { listener.onExit(exitCode, signal) }
            reader.shutdown()
        }
    }

    companion object {
        fun open(
            request: PluginTerminalRequest,
            listener: PluginTerminalListener
        ): PluginTerminalSession? = runCatching {
            require(request.executable.startsWith('/'))
            require(File(request.workingDirectory).isDirectory)
            val environment = request.environment.entries.toList()
            val handle = NativePtyBridge.spawn(
                request.executable,
                request.arguments.toTypedArray(),
                request.workingDirectory,
                environment.map { it.key }.toTypedArray(),
                environment.map { it.value }.toTypedArray(),
                request.rows.coerceAtLeast(1),
                request.columns.coerceAtLeast(1)
            )
            require(handle != 0L)
            HostPtyTerminalSession(handle, listener)
        }.onFailure {
            listener.onError(it.message ?: "Unable to start PTY")
        }.getOrNull()
    }
}
