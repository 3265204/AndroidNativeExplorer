package com.ane.filemanager.core.file

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileTypeResolverTest {
    @Test
    fun `extension lookup handles spaces and normalizes case`() {
        assertEquals("jpeg", FileTypeResolver.extension(File("Holiday Photo.JPEG")))
    }

    @Test
    fun `extension lookup uses the final filename suffix`() {
        assertEquals("gz", FileTypeResolver.extension(File("backup.TAR.GZ")))
    }
}
