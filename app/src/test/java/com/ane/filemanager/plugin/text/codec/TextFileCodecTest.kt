package com.ane.filemanager.plugin.text.codec

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class TextFileCodecTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `oversized input throws typed failure`() {
        val file = temporary.newFile("large.txt")
        RandomAccessFile(file, "rw").use { it.setLength(TextFileCodec.MAX_EDITOR_BYTES + 1) }

        assertThrows(TextFileTooLargeException::class.java) {
            TextFileCodec.load(file)
        }
    }
}
