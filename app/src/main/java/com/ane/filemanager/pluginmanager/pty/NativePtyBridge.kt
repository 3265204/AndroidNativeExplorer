package com.ane.filemanager.pluginmanager.pty

internal object NativePtyBridge {
    init {
        System.loadLibrary("ane_pty")
    }

    external fun spawn(
        executable: String,
        arguments: Array<String>,
        workingDirectory: String,
        environmentKeys: Array<String>,
        environmentValues: Array<String>,
        rows: Int,
        columns: Int
    ): Long

    external fun read(handle: Long, buffer: ByteArray): Int
    external fun write(handle: Long, bytes: ByteArray): Int
    external fun resize(handle: Long, rows: Int, columns: Int): Boolean
    external fun signal(handle: Long, signal: Int): Boolean
    external fun terminate(handle: Long)
    /** High 32 bits: exit code or -1. Low 32 bits: signal or -1. Frees the native handle. */
    external fun waitAndDestroy(handle: Long): Long
}
