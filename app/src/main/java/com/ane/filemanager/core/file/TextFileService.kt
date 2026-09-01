package com.ane.filemanager.core.file

import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.file.AnePluginFiles
import com.ane.filemanager.plugin.api.file.PluginTextDocument
import com.ane.filemanager.plugin.api.file.PluginTextEncoding
import com.ane.filemanager.plugin.api.file.PluginTextTooLargeException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/** Host-owned text I/O with encoding and BOM preservation. */
internal object TextFileService : AnePluginFiles {
    override fun readText(file: PluginFile, maximumBytes: Long): PluginTextDocument =
        read(file.toHostFile(), maximumBytes)

    override fun writeText(file: PluginFile, text: String, encoding: PluginTextEncoding) =
        write(file.toHostFile(), text, encoding)

    fun read(
        file: File,
        maximumBytes: Long = AnePluginFiles.DEFAULT_MAX_TEXT_BYTES
    ): PluginTextDocument {
        require(maximumBytes >= 0) { "maximumBytes must not be negative" }
        val reportedSize = file.length()
        if (reportedSize > maximumBytes) {
            throw PluginTextTooLargeException(maximumBytes, reportedSize)
        }
        val bytes = file.readBytes()
        if (bytes.size.toLong() > maximumBytes) {
            throw PluginTextTooLargeException(maximumBytes, bytes.size.toLong())
        }
        val encoding = detectEncoding(bytes)
        val descriptor = descriptor(encoding)
        return PluginTextDocument(
            text = String(
                bytes,
                descriptor.bom.size,
                bytes.size - descriptor.bom.size,
                descriptor.charset
            ),
            encoding = encoding
        )
    }

    fun write(file: File, text: String, encoding: PluginTextEncoding) {
        val descriptor = descriptor(encoding)
        FileOutputStream(file, false).use { output ->
            if (descriptor.bom.isNotEmpty()) output.write(descriptor.bom)
            output.write(text.toByteArray(descriptor.charset))
            output.flush()
            output.fd.sync()
        }
    }

    private fun detectEncoding(bytes: ByteArray): PluginTextEncoding = when {
        bytes.startsWith(UTF8_BOM) -> PluginTextEncoding.UTF8_BOM
        bytes.startsWith(UTF16_LE_BOM) -> PluginTextEncoding.UTF16_LE
        bytes.startsWith(UTF16_BE_BOM) -> PluginTextEncoding.UTF16_BE
        else -> PluginTextEncoding.UTF8
    }

    private fun descriptor(encoding: PluginTextEncoding): EncodingDescriptor = when (encoding) {
        PluginTextEncoding.UTF8 -> EncodingDescriptor(Charsets.UTF_8, byteArrayOf())
        PluginTextEncoding.UTF8_BOM -> EncodingDescriptor(
            Charsets.UTF_8,
            UTF8_BOM
        )
        PluginTextEncoding.UTF16_LE -> EncodingDescriptor(
            UTF16_LE_CHARSET,
            UTF16_LE_BOM
        )
        PluginTextEncoding.UTF16_BE -> EncodingDescriptor(
            UTF16_BE_CHARSET,
            UTF16_BE_BOM
        )
    }

    private fun ByteArray.startsWith(expected: ByteArray): Boolean {
        if (size < expected.size) return false
        return expected.indices.all { this[it] == expected[it] }
    }

    private fun PluginFile.toHostFile() = File(path)

    private data class EncodingDescriptor(val charset: Charset, val bom: ByteArray)

    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xfe.toByte(), 0xff.toByte())
    private val UTF16_LE_CHARSET: Charset = Charset.forName("UTF-16LE")
    private val UTF16_BE_CHARSET: Charset = Charset.forName("UTF-16BE")
}
