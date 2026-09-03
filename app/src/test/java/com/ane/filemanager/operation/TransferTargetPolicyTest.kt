package com.ane.filemanager.operation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferTargetPolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `folder cannot target itself or one of its descendants`() {
        val source = temporary.newFolder("source")
        val descendant = File(source, "descendant").apply { mkdir() }

        assertFalse(TransferTargetPolicy.accepts(listOf(source), source))
        assertFalse(TransferTargetPolicy.accepts(listOf(source), descendant))
    }

    @Test
    fun `folder can target an unrelated directory`() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")

        assertTrue(TransferTargetPolicy.accepts(listOf(source), target))
    }

    @Test
    fun `regular file does not invalidate a directory target`() {
        val target = temporary.newFolder("target")
        val source = File(target, "source.txt").apply { writeText("content") }

        assertTrue(TransferTargetPolicy.accepts(listOf(source), target))
    }
}
