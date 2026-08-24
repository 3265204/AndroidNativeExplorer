package com.ane.filemanager.plugin.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivePluginTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `zip extracts into available sibling folder`() {
        val archive = temporary.newFile("sample.zip")
        writeZip(archive, "folder/hello.txt" to "hello")
        temporary.newFolder("sample")

        val result = ArchiveExtractor.extract(archive)

        assertTrue(result is ArchivePluginResult.Success)
        val output = (result as ArchivePluginResult.Success).value
        assertEquals("sample (1)", output.name)
        assertEquals("hello", File(output, "folder/hello.txt").readText())
    }

    @Test
    fun `zip traversal is rejected without partial output`() {
        val archive = temporary.newFile("unsafe.zip")
        writeZip(archive, "../escaped.txt" to "nope")

        val result = ArchiveExtractor.extract(archive)

        assertTrue(result is ArchivePluginResult.Failure)
        assertEquals(ArchivePluginError.UNSAFE_ENTRY, (result as ArchivePluginResult.Failure).error)
        assertFalse(File(temporary.root, "escaped.txt").exists())
        assertFalse(File(temporary.root, "unsafe").exists())
        assertTrue(temporary.root.listFiles().orEmpty().none { ".extract-" in it.name })
    }

    @Test
    fun `encrypted zip is detected and accepts password`() {
        val source = temporary.newFile("secret.txt").apply { writeText("classified") }
        val archive = File(temporary.root, "secure.zip")
        val password = "correct horse".toCharArray()
        val parameters = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
        }
        ZipFile(archive, password).addFile(source, parameters)

        val inspection = ArchiveExtractor.inspect(archive)
        assertTrue(inspection is ArchivePluginResult.Success && inspection.value.passwordRequired)

        val wrong = ArchiveExtractor.extract(archive, "wrong".toCharArray())
        assertTrue(wrong is ArchivePluginResult.Failure)
        assertEquals(ArchivePluginError.WRONG_PASSWORD, (wrong as ArchivePluginResult.Failure).error)

        val success = ArchiveExtractor.extract(archive, "correct horse".toCharArray())
        assertTrue(success is ArchivePluginResult.Success)
        val output = (success as ArchivePluginResult.Success).value
        assertEquals("classified", File(output, source.name).readText())
    }

    @Test
    fun `encrypted seven z is detected and accepts password`() {
        val source = temporary.newFile("seven-secret.txt").apply { writeText("seven classified") }
        val archive = File(temporary.root, "secure.7z")
        val password = "seven-pass".toCharArray()
        SevenZOutputFile(archive, password).use { sevenZ ->
            sevenZ.putArchiveEntry(sevenZ.createArchiveEntry(source, source.name))
            source.inputStream().use(sevenZ::write)
            sevenZ.closeArchiveEntry()
        }

        val inspection = ArchiveExtractor.inspect(archive)
        assertTrue(inspection is ArchivePluginResult.Success && inspection.value.passwordRequired)

        val wrong = ArchiveExtractor.extract(archive, "wrong".toCharArray())
        assertTrue(wrong is ArchivePluginResult.Failure)
        assertEquals(ArchivePluginError.WRONG_PASSWORD, (wrong as ArchivePluginResult.Failure).error)

        val success = ArchiveExtractor.extract(archive, "seven-pass".toCharArray())
        assertTrue(success is ArchivePluginResult.Success)
        val output = (success as ArchivePluginResult.Success).value
        assertEquals("seven classified", File(output, source.name).readText())
    }

    @Test
    fun `only formats with bundled writers are advertised`() {
        val extensions = ArchiveCompressor.availableFormats().map { it.extension }

        assertEquals(listOf("zip", "7z", "tar", "tar.gz"), extensions)
        assertFalse("rar" in extensions)
    }

    @Test
    fun `zip compressor preserves selected roots and nested files`() {
        val note = temporary.newFile("note.txt").apply { writeText("top level") }
        val folder = temporary.newFolder("folder")
        File(folder, "nested.txt").writeText("nested")

        val output = ArchiveCompressor.compress(
            listOf(note, folder),
            WritableArchiveFormat.ZIP,
            "bundle"
        )

        assertEquals("bundle.zip", output.name)
        java.util.zip.ZipFile(output).use { zip ->
            assertEquals("top level", zip.getInputStream(zip.getEntry("note.txt")).bufferedReader().readText())
            assertEquals("nested", zip.getInputStream(zip.getEntry("folder/nested.txt")).bufferedReader().readText())
        }
    }

    @Test
    fun `seven z compressor round trips through extractor`() {
        val folder = temporary.newFolder("source")
        File(folder, "nested.txt").writeText("seven nested")

        val archive = ArchiveCompressor.compress(
            listOf(folder),
            WritableArchiveFormat.SEVEN_Z,
            "unused"
        )
        val extracted = ArchiveExtractor.extract(archive)

        assertTrue(extracted is ArchivePluginResult.Success)
        val output = (extracted as ArchivePluginResult.Success).value
        assertEquals("seven nested", File(output, "source/nested.txt").readText())
    }

    @Test
    fun `numbered split zip extracts when any volume is selected`() {
        val source = temporary.newFile("zip-volume.bin").apply { writeBytes(randomBytes(180_000)) }
        val archive = File(temporary.root, "numbered.zip")
        ZipFile(archive).addFile(source)
        val parts = splitNumbered(archive, 64 * 1024)

        val result = ArchiveExtractor.extract(parts.last())

        assertTrue(result is ArchivePluginResult.Success)
        val output = (result as ArchivePluginResult.Success).value
        assertTrue(source.readBytes().contentEquals(File(output, source.name).readBytes()))
    }

    @Test
    fun `numbered split seven z extracts when a later volume is selected`() {
        val source = temporary.newFile("seven-volume.bin").apply { writeBytes(randomBytes(140_000)) }
        val archive = File(temporary.root, "volumes.7z")
        SevenZOutputFile(archive).use { sevenZ ->
            sevenZ.putArchiveEntry(sevenZ.createArchiveEntry(source, source.name))
            source.inputStream().use(sevenZ::write)
            sevenZ.closeArchiveEntry()
        }
        val parts = splitNumbered(archive, 48 * 1024)

        val result = ArchiveExtractor.extract(parts[1])

        assertTrue(result is ArchivePluginResult.Success)
        val output = (result as ArchivePluginResult.Success).value
        assertTrue(source.readBytes().contentEquals(File(output, source.name).readBytes()))
    }

    @Test
    fun `standard split zip extracts when z volume is selected`() {
        val source = temporary.newFile("standard-volume.bin").apply { writeBytes(randomBytes(180_000)) }
        val archive = File(temporary.root, "standard.zip")
        ZipFile(archive).createSplitZipFile(
            listOf(source),
            ZipParameters(),
            true,
            64 * 1024L
        )
        val firstVolume = File(temporary.root, "standard.z01")
        assertTrue(firstVolume.exists())

        val result = ArchiveExtractor.extract(firstVolume)

        assertTrue(result is ArchivePluginResult.Success)
        val output = (result as ArchivePluginResult.Success).value
        assertTrue(source.readBytes().contentEquals(File(output, source.name).readBytes()))
    }

    @Test
    fun `numbered split reports a missing volume before extraction`() {
        val first = temporary.newFile("broken.7z.001")
        temporary.newFile("broken.7z.003")

        val result = ArchiveExtractor.inspect(first)

        assertTrue(result is ArchivePluginResult.Failure)
        result as ArchivePluginResult.Failure
        assertEquals(ArchivePluginError.MISSING_VOLUME, result.error)
        assertEquals("broken.7z.002", result.subject)
    }

    @Test
    fun `rar part selection resolves to the first volume`() {
        val first = temporary.newFile("backup.part01.rar")
        val second = temporary.newFile("backup.part02.rar")

        val result = ArchiveVolumeResolver.resolve(second)

        assertTrue(result is ArchivePluginResult.Success)
        val source = (result as ArchivePluginResult.Success).value
        assertEquals(first, source.primary)
        assertEquals(listOf(first, second), source.parts)
        assertEquals("backup", source.outputName)
    }

    private fun writeZip(target: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun splitNumbered(source: File, partSize: Int): List<File> {
        val bytes = source.readBytes()
        val baseName = source.name
        assertTrue(source.delete())
        return bytes.indices.step(partSize).mapIndexed { index, start ->
            File(temporary.root, "$baseName.${(index + 1).toString().padStart(3, '0')}").apply {
                writeBytes(bytes.copyOfRange(start, minOf(start + partSize, bytes.size)))
            }
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { Random(42L).nextBytes(it) }
}
