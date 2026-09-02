package com.ane.filemanager.core.file

import com.ane.filemanager.operation.FileTransactionService
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.file.AnePluginOutputSession
import com.ane.filemanager.plugin.api.file.AnePluginOutputs
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Gives plugins writable staging paths while keeping final placement and history in the host. */
internal class PluginOutputService(
    private val stagingRoot: File,
    private val transactions: FileTransactionService
) : AnePluginOutputs {
    override fun begin(
        parentDirectory: PluginFile,
        suggestedName: String
    ): AnePluginOutputSession {
        val parent = File(parentDirectory.path).canonicalFile
        if (!parent.isDirectory) throw IOException("Output parent is unavailable")
        val safeName = suggestedName.trim().takeIf {
            it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it
        } ?: throw IOException("Invalid output name")
        val sessionRoot = File(stagingRoot, UUID.randomUUID().toString())
        if (!sessionRoot.mkdirs()) throw IOException("Could not create output staging area")
        val staging = File(sessionRoot, safeName)
        val finished = AtomicBoolean(false)
        return object : AnePluginOutputSession {
            override val stagingPath: String get() = staging.absolutePath

            override fun commit(): PluginFile {
                if (!finished.compareAndSet(false, true)) throw IOException("Output session is closed")
                return try {
                    transactions.commitStagedOutput(staging, parent, safeName).asPluginFile()
                } finally {
                    sessionRoot.deleteRecursively()
                }
            }

            override fun close() {
                if (finished.compareAndSet(false, true)) sessionRoot.deleteRecursively()
            }
        }
    }
}
