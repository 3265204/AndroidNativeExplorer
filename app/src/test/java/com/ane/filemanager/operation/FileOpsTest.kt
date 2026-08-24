package com.ane.filemanager.operation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class FileOpsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `recursive copy removes partial target when a child fails`() {
        val source = temporary.newFolder("recursive-source")
        source.resolve("first.txt").writeText("first")
        val brokenLink = source.resolve("broken-link")
        try {
            Files.createSymbolicLink(brokenLink.toPath(), source.resolve("missing-target").toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this test platform", error)
        }
        val target = temporary.root.resolve("recursive-target")

        assertThrows(IOException::class.java) { FileOps.copy(source, target) }

        assertFalse(target.exists())
    }
}
