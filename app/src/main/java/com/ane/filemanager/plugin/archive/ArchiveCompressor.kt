package com.ane.filemanager.plugin.archive

import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class WritableArchiveFormat(val extension: String) {
    ZIP("zip"),
    SEVEN_Z("7z"),
    TAR("tar"),
    TAR_GZIP("tar.gz");

    fun isAvailable(): Boolean = runCatching {
        when (this) {
            ZIP -> Class.forName("java.util.zip.ZipOutputStream")
            SEVEN_Z -> Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZOutputFile")
            TAR -> Class.forName("org.apache.commons.compress.archivers.tar.TarArchiveOutputStream")
            TAR_GZIP -> {
                Class.forName("org.apache.commons.compress.archivers.tar.TarArchiveOutputStream")
                Class.forName("org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream")
            }
        }
    }.isSuccess
}

/** Archive-plugin-owned writers. Unsupported writers, including RAR, never become capabilities. */
internal object ArchiveCompressor {
    fun availableFormats(): List<WritableArchiveFormat> =
        WritableArchiveFormat.entries.filter(WritableArchiveFormat::isAvailable)

    @Throws(IOException::class)
    fun compress(sources: List<File>, format: WritableArchiveFormat, fallbackBaseName: String): File {
        if (!format.isAvailable()) throw IOException("Archive writer unavailable")
        val files = sources.filter(File::exists).distinctBy(File::getCanonicalPath)
        if (files.isEmpty()) throw IOException("Nothing selected")
        val parent = files.first().parentFile?.canonicalFile ?: throw IOException("Missing parent folder")
        if (files.any { it.parentFile?.canonicalFile != parent }) {
            throw IOException("Selected files do not share a folder")
        }

        val baseName = outputBaseName(files, fallbackBaseName)
        val output = availableTarget(parent, baseName, format.extension)
        val staging = File(parent, ".${output.name}.compress-${UUID.randomUUID()}.tmp")
        try {
            when (format) {
                WritableArchiveFormat.ZIP -> writeZip(files, staging)
                WritableArchiveFormat.SEVEN_Z -> writeSevenZ(files, staging)
                WritableArchiveFormat.TAR -> writeTar(files, staging, gzip = false)
                WritableArchiveFormat.TAR_GZIP -> writeTar(files, staging, gzip = true)
            }
            if (output.exists() || !staging.renameTo(output)) throw IOException("Could not commit archive")
            return output
        } catch (error: Exception) {
            staging.delete()
            if (error is IOException) throw error
            throw IOException("Could not create archive", error)
        }
    }

    private fun writeZip(sources: List<File>, target: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { archive ->
            visit(sources) { file, name ->
                val entry = ZipEntry(if (file.isDirectory) "$name/" else name).apply {
                    time = file.lastModified()
                }
                archive.putNextEntry(entry)
                if (file.isFile) file.inputStream().buffered().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }
    }

    private fun writeSevenZ(sources: List<File>, target: File) {
        SevenZOutputFile(target).use { archive ->
            archive.setContentCompression(SevenZMethod.LZMA2)
            visit(sources) { file, name ->
                archive.putArchiveEntry(archive.createArchiveEntry(file, if (file.isDirectory) "$name/" else name))
                if (file.isFile) file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        archive.write(buffer, 0, count)
                    }
                }
                archive.closeArchiveEntry()
            }
        }
    }

    private fun writeTar(sources: List<File>, target: File, gzip: Boolean) {
        val fileOutput = BufferedOutputStream(FileOutputStream(target))
        val compressed = if (gzip) GzipCompressorOutputStream(fileOutput) else fileOutput
        TarArchiveOutputStream(compressed).use { archive ->
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            visit(sources) { file, name ->
                archive.putArchiveEntry(TarArchiveEntry(file, if (file.isDirectory) "$name/" else name))
                if (file.isFile) file.inputStream().buffered().use { it.copyTo(archive) }
                archive.closeArchiveEntry()
            }
        }
    }

    private fun visit(sources: List<File>, accept: (File, String) -> Unit) {
        fun visitOne(file: File, entryName: String) {
            accept(file, entryName)
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw IOException("Could not read ${file.name}")
                children.sortedBy { it.name.lowercase() }.forEach { child ->
                    visitOne(child, "$entryName/${safeEntryName(child.name)}")
                }
            }
        }
        sources.forEach { visitOne(it, safeEntryName(it.name)) }
    }

    private fun outputBaseName(files: List<File>, fallback: String): String {
        val raw = if (files.size == 1) {
            val file = files.single()
            if (file.isDirectory) file.name else file.nameWithoutExtension
        } else fallback
        return safeEntryName(raw).trim().trim('.').ifBlank { "archive" }
    }

    private fun availableTarget(parent: File, baseName: String, extension: String): File {
        var output = File(parent, "$baseName.$extension")
        var index = 1
        while (output.exists()) output = File(parent, "$baseName (${index++}).$extension")
        return output
    }

    private fun safeEntryName(name: String) = name.replace('/', '_').replace('\\', '_')

    private const val COPY_BUFFER_SIZE = 64 * 1024
}
