package com.ane.filemanager.plugin.text.codec

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset

internal data class LoadedText(val text: String, val encoding: TextEncoding)
internal class TextFileTooLargeException : IOException()

internal enum class TextEncoding(val charset: Charset, val bom: ByteArray) {
    UTF8(Charsets.UTF_8, byteArrayOf()),
    UTF8_BOM(Charsets.UTF_8, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())),
    UTF16_LE(Charset.forName("UTF-16LE"), byteArrayOf(0xff.toByte(), 0xfe.toByte())),
    UTF16_BE(Charset.forName("UTF-16BE"), byteArrayOf(0xfe.toByte(), 0xff.toByte()))
}

internal object TextFileCodec {
    const val MAX_EDITOR_BYTES = 16L * 1024L * 1024L

    fun load(file: File): LoadedText {
        if (file.length() > MAX_EDITOR_BYTES) throw TextFileTooLargeException()
        val bytes = file.readBytes()
        val encoding = when {
            bytes.startsWith(0xef, 0xbb, 0xbf) -> TextEncoding.UTF8_BOM
            bytes.startsWith(0xff, 0xfe) -> TextEncoding.UTF16_LE
            bytes.startsWith(0xfe, 0xff) -> TextEncoding.UTF16_BE
            else -> TextEncoding.UTF8
        }
        val offset = encoding.bom.size
        return LoadedText(String(bytes, offset, bytes.size - offset, encoding.charset), encoding)
    }

    fun save(file: File, text: String, encoding: TextEncoding) {
        FileOutputStream(file, false).use { output ->
            if (encoding.bom.isNotEmpty()) output.write(encoding.bom)
            output.write(text.toByteArray(encoding.charset))
            output.flush()
            output.fd.sync()
        }
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean {
        if (size < expected.size) return false
        return expected.indices.all { (this[it].toInt() and 0xff) == expected[it] }
    }
}
