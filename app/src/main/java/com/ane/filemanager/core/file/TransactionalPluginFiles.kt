package com.ane.filemanager.core.file

import com.ane.filemanager.operation.FileTransactionService
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.file.AnePluginFiles
import com.ane.filemanager.plugin.api.file.PluginTextDocument
import com.ane.filemanager.plugin.api.file.PluginTextEncoding
import java.io.File

/** Plugin file capability backed by the host's session-wide transaction owner. */
internal class TransactionalPluginFiles(
    private val transactions: FileTransactionService
) : AnePluginFiles {
    override fun readText(file: PluginFile, maximumBytes: Long): PluginTextDocument =
        TextFileService.read(File(file.path), maximumBytes)

    override fun writeText(file: PluginFile, text: String, encoding: PluginTextEncoding) {
        transactions.writeText(File(file.path), text, encoding, file.name)
    }
}
