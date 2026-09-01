package com.ane.filemanager.core.file

import com.ane.filemanager.plugin.api.file.AnePluginFiles
import com.ane.filemanager.plugin.api.file.PluginTextEncoding
import com.ane.filemanager.plugin.api.file.PluginTextTooLargeException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class TextFileServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun oversizedInputThrowsTypedFailure() {
        val file = temporary.newFile("large.txt")
        RandomAccessFile(file, "rw").use {
            it.setLength(AnePluginFiles.DEFAULT_MAX_TEXT_BYTES + 1)
        }

        val error = assertThrows(PluginTextTooLargeException::class.java) {
            TextFileService.read(file)
        }
        assertEquals(AnePluginFiles.DEFAULT_MAX_TEXT_BYTES, error.maximumBytes)
    }

    @Test
    fun allSupportedEncodingsRoundTripWithTheirBom() {
        val expectedText = "ANE 文本\nsecond line"
        PluginTextEncoding.entries.forEach { encoding ->
            val file = temporary.newFile("${encoding.name}.txt")
            TextFileService.write(file, expectedText, encoding)

            val loaded = TextFileService.read(file)
            assertEquals(expectedText, loaded.text)
            assertEquals(encoding, loaded.encoding)
            assertArrayEquals(expectedBom(encoding), file.readBytes().take(expectedBom(encoding).size).toByteArray())
        }
    }

    private fun expectedBom(encoding: PluginTextEncoding): ByteArray = when (encoding) {
        PluginTextEncoding.UTF8 -> byteArrayOf()
        PluginTextEncoding.UTF8_BOM -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        PluginTextEncoding.UTF16_LE -> byteArrayOf(0xff.toByte(), 0xfe.toByte())
        PluginTextEncoding.UTF16_BE -> byteArrayOf(0xfe.toByte(), 0xff.toByte())
    }
}
