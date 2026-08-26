package com.ane.filemanager.plugin.archive

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.WrongPasswordException
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal data class ArchiveInspection(val passwordRequired: Boolean)

internal enum class ArchivePluginError {
    SOURCE_MISSING,
    UNSUPPORTED,
    CORRUPT,
    UNSAFE_ENTRY,
    PASSWORD_REQUIRED,
    WRONG_PASSWORD,
    CREATE_DIRECTORY,
    NAME_EXISTS,
    EXTRACT_FAILED,
    MISSING_VOLUME
}

internal sealed interface ArchivePluginResult<out T> {
    data class Success<T>(val value: T) : ArchivePluginResult<T>
    data class Failure(val error: ArchivePluginError, val subject: String? = null) : ArchivePluginResult<Nothing>
}

/** Built-in plugin for archive formats; the file-manager core only consumes this public boundary. */
internal object ArchiveExtractor {
    fun supports(file: File): Boolean = file.isFile && ArchiveVolumeResolver.matchesName(file.name)

    fun inspect(file: File): ArchivePluginResult<ArchiveInspection> {
        val source = when (val resolved = ArchiveVolumeResolver.resolve(file)) {
            is ArchivePluginResult.Success -> resolved.value
            is ArchivePluginResult.Failure -> return resolved
        }
        val format = ArchiveFormat.forFile(source.primary)
            ?: return failure(ArchivePluginError.UNSUPPORTED, source.primary.name)
        return try {
            val required = when (format) {
                ArchiveFormat.ZIP -> ZipFile(source.primary).use { it.isEncrypted }
                ArchiveFormat.RAR -> openRar(source.primary, null).use { it.isPasswordProtected }
                ArchiveFormat.SEVEN_Z -> inspectSevenZ(source)
                else -> false
            }
            ArchivePluginResult.Success(ArchiveInspection(required))
        } catch (error: MissingNextVolumeException) {
            failure(ArchivePluginError.MISSING_VOLUME, error.message ?: source.primary.name)
        } catch (error: MissingPreviousVolumeException) {
            failure(ArchivePluginError.MISSING_VOLUME, error.message ?: source.primary.name)
        } catch (_: UnsupportedRarEncryptedException) {
            ArchivePluginResult.Success(ArchiveInspection(true))
        } catch (_: WrongPasswordException) {
            ArchivePluginResult.Success(ArchiveInspection(true))
        } catch (error: IOException) {
            if (format == ArchiveFormat.SEVEN_Z && isPasswordError(error)) {
                ArchivePluginResult.Success(ArchiveInspection(true))
            } else {
                failure(ArchivePluginError.CORRUPT, source.primary.name)
            }
        } catch (_: Exception) {
            failure(ArchivePluginError.CORRUPT, source.primary.name)
        }
    }

    fun list(file: File, password: CharArray? = null): ArchivePluginResult<List<ArchiveEntryInfo>> =
        try {
            listInternal(file, password)
        } finally {
            password?.fill('\u0000')
        }

    private fun listInternal(
        file: File,
        password: CharArray?
    ): ArchivePluginResult<List<ArchiveEntryInfo>> {
        val source = when (val resolved = ArchiveVolumeResolver.resolve(file)) {
            is ArchivePluginResult.Success -> resolved.value
            is ArchivePluginResult.Failure -> return resolved
        }
        val format = ArchiveFormat.forFile(source.primary)
            ?: return failure(ArchivePluginError.UNSUPPORTED, source.primary.name)
        return try {
            val entries = when (format) {
                ArchiveFormat.ZIP -> listZip(source.primary, password)
                ArchiveFormat.RAR -> listRar(source.primary, password)
                ArchiveFormat.SEVEN_Z -> listSevenZ(source, password)
                ArchiveFormat.TAR -> listTar(FileInputStream(source.primary))
                ArchiveFormat.TAR_GZIP -> listTar(gzip(source.primary))
                ArchiveFormat.TAR_BZIP2 -> listTar(bzip2(source.primary))
                ArchiveFormat.TAR_XZ -> listTar(xz(source.primary))
                ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ -> listOf(
                    ArchiveEntryInfo(source.outputName, directory = false)
                )
            }
            ArchivePluginResult.Success(entries)
        } catch (error: UnsafeArchiveEntryException) {
            failure(ArchivePluginError.UNSAFE_ENTRY, error.entryName)
        } catch (error: MissingNextVolumeException) {
            failure(ArchivePluginError.MISSING_VOLUME, error.message ?: source.primary.name)
        } catch (error: MissingPreviousVolumeException) {
            failure(ArchivePluginError.MISSING_VOLUME, error.message ?: source.primary.name)
        } catch (error: ZipException) {
            if (error.type == ZipException.Type.WRONG_PASSWORD) passwordFailure(password, source.primary)
            else failure(ArchivePluginError.CORRUPT, source.primary.name)
        } catch (_: UnsupportedRarEncryptedException) {
            passwordFailure(password, source.primary)
        } catch (_: WrongPasswordException) {
            passwordFailure(password, source.primary)
        } catch (error: IOException) {
            if (format == ArchiveFormat.SEVEN_Z && isSevenZPasswordFailure(error, password)) {
                passwordFailure(password, source.primary)
            } else failure(ArchivePluginError.CORRUPT, source.primary.name)
        } catch (_: Exception) {
            failure(ArchivePluginError.CORRUPT, source.primary.name)
        }
    }

    fun extract(file: File, password: CharArray? = null): ArchivePluginResult<File> =
        try {
            extractInternal(file, password)
        } finally {
            password?.fill('\u0000')
        }

    private fun extractInternal(
        file: File,
        password: CharArray?
    ): ArchivePluginResult<File> {
        val source = when (val resolved = ArchiveVolumeResolver.resolve(file)) {
            is ArchivePluginResult.Success -> resolved.value
            is ArchivePluginResult.Failure -> return resolved
        }
        val format = ArchiveFormat.forFile(source.primary)
            ?: return failure(ArchivePluginError.UNSUPPORTED, source.primary.name)
        val parent = source.primary.parentFile
            ?: return failure(ArchivePluginError.EXTRACT_FAILED, source.primary.name)
        val output = availableTarget(parent, source.outputName)
        val staging = File(parent, ".${output.name}.extract-${UUID.randomUUID()}")
        if (!staging.mkdir()) return failure(ArchivePluginError.CREATE_DIRECTORY, output.name)

        return try {
            when (format) {
                ArchiveFormat.ZIP -> extractZip(source.primary, staging, password)
                ArchiveFormat.RAR -> extractRar(source.primary, staging, password)
                ArchiveFormat.SEVEN_Z -> extractSevenZ(source, staging, password)
                ArchiveFormat.TAR -> extractTar(FileInputStream(source.primary), staging)
                ArchiveFormat.TAR_GZIP -> extractTar(gzip(source.primary), staging)
                ArchiveFormat.TAR_BZIP2 -> extractTar(bzip2(source.primary), staging)
                ArchiveFormat.TAR_XZ -> extractTar(xz(source.primary), staging)
                ArchiveFormat.GZIP -> extractSingle(gzip(source.primary), staging, source.outputName)
                ArchiveFormat.BZIP2 -> extractSingle(bzip2(source.primary), staging, source.outputName)
                ArchiveFormat.XZ -> extractSingle(xz(source.primary), staging, source.outputName)
            }
            if (output.exists()) throw ArchivePluginException(ArchivePluginError.NAME_EXISTS, output.name)
            move(staging, output)
            ArchivePluginResult.Success(output)
        } catch (error: UnsafeArchiveEntryException) {
            delete(staging)
            failure(ArchivePluginError.UNSAFE_ENTRY, error.entryName)
        } catch (error: MissingNextVolumeException) {
            delete(staging)
            failure(ArchivePluginError.MISSING_VOLUME, error.message ?: source.primary.name)
        } catch (error: MissingPreviousVolumeException) {
            delete(staging)
            failure(ArchivePluginError.MISSING_VOLUME, error.message ?: source.primary.name)
        } catch (error: ZipException) {
            delete(staging)
            if (error.type == ZipException.Type.WRONG_PASSWORD) passwordFailure(password, source.primary)
            else failure(ArchivePluginError.CORRUPT, source.primary.name)
        } catch (_: UnsupportedRarEncryptedException) {
            delete(staging)
            passwordFailure(password, source.primary)
        } catch (_: WrongPasswordException) {
            delete(staging)
            passwordFailure(password, source.primary)
        } catch (error: ArchivePluginException) {
            delete(staging)
            failure(error.error, error.subject ?: source.primary.name)
        } catch (error: IOException) {
            delete(staging)
            if (format == ArchiveFormat.SEVEN_Z && isSevenZPasswordFailure(error, password)) {
                passwordFailure(password, source.primary)
            }
            else failure(ArchivePluginError.EXTRACT_FAILED, source.primary.name)
        } catch (_: Exception) {
            delete(staging)
            failure(ArchivePluginError.EXTRACT_FAILED, source.primary.name)
        }
    }

    @Throws(IOException::class)
    private fun inspectSevenZ(source: ResolvedArchiveSource): Boolean {
        openSevenZ(source, null).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true) {
                    return true
                }
            }
        }
        return false
    }

    @Throws(ZipException::class, UnsafeArchiveEntryException::class)
    private fun listZip(file: File, password: CharArray?): List<ArchiveEntryInfo> =
        (if (password == null) ZipFile(file) else ZipFile(file, password)).use { archive ->
            archive.fileHeaders.map { entry ->
                if (entry.externalFileAttributes?.getOrNull(3)?.toInt()?.and(0x20) != 0) {
                    throw UnsafeArchiveEntryException(entry.fileName)
                }
                ArchiveEntryInfo(
                    path = safeDisplayPath(entry.fileName),
                    directory = entry.isDirectory,
                    size = entry.uncompressedSize.takeIf { it >= 0L && !entry.isDirectory }
                )
            }
        }

    @Throws(Exception::class)
    private fun listRar(file: File, password: CharArray?): List<ArchiveEntryInfo> =
        openRar(file, password).use { archive ->
            archive.fileHeaders.map { entry ->
                if (entry.redirection != null) throw UnsafeArchiveEntryException(entry.fileName)
                ArchiveEntryInfo(
                    path = safeDisplayPath(entry.fileName),
                    directory = entry.isDirectory,
                    size = entry.fullUnpackSize.takeIf { it >= 0L && !entry.isDirectory }
                )
            }
        }

    @Throws(IOException::class, UnsafeArchiveEntryException::class)
    private fun listSevenZ(
        source: ResolvedArchiveSource,
        password: CharArray?
    ): List<ArchiveEntryInfo> = openSevenZ(source, password).use { archive ->
        buildList {
            while (true) {
                val entry = archive.nextEntry ?: break
                add(ArchiveEntryInfo(
                    path = safeDisplayPath(entry.name ?: throw IOException("Unnamed 7z entry")),
                    directory = entry.isDirectory,
                    size = entry.size.takeIf { it >= 0L && !entry.isDirectory }
                ))
            }
        }
    }

    @Throws(IOException::class, UnsafeArchiveEntryException::class)
    private fun listTar(input: InputStream): List<ArchiveEntryInfo> =
        TarArchiveInputStream(BufferedInputStream(input)).use { archive ->
            buildList {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.isSymbolicLink || entry.isLink) {
                        throw UnsafeArchiveEntryException(entry.name)
                    }
                    add(ArchiveEntryInfo(
                        path = safeDisplayPath(entry.name),
                        directory = entry.isDirectory,
                        size = entry.size.takeIf { it >= 0L && !entry.isDirectory }
                    ))
                }
            }
        }

    @Throws(ZipException::class, UnsafeArchiveEntryException::class)
    private fun extractZip(file: File, output: File, password: CharArray?) {
        (if (password == null) ZipFile(file) else ZipFile(file, password)).use { archive ->
            archive.fileHeaders.forEach {
                safeTarget(output, it.fileName)
                if (it.externalFileAttributes?.getOrNull(3)?.toInt()?.and(0x20) != 0) {
                    throw UnsafeArchiveEntryException(it.fileName)
                }
            }
            archive.extractAll(output.absolutePath)
        }
    }

    @Throws(Exception::class)
    private fun extractRar(file: File, output: File, password: CharArray?) {
        openRar(file, password).use { archive ->
            archive.fileHeaders.forEach { entry ->
                if (entry.redirection != null) throw UnsafeArchiveEntryException(entry.fileName)
                val target = safeTarget(output, entry.fileName)
                if (entry.isDirectory) {
                    createDirectory(target)
                } else {
                    createDirectory(target.parentFile)
                    BufferedOutputStream(FileOutputStream(target)).use { archive.extractFile(entry, it) }
                    entry.mTime?.time?.takeIf { it > 0L }?.let(target::setLastModified)
                }
            }
        }
    }

    @Throws(IOException::class, UnsafeArchiveEntryException::class)
    private fun extractSevenZ(source: ResolvedArchiveSource, output: File, password: CharArray?) {
        openSevenZ(source, password).use { archive ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val entry = archive.nextEntry ?: break
                val target = safeTarget(output, entry.name ?: throw IOException("Unnamed 7z entry"))
                if (entry.isDirectory) {
                    createDirectory(target)
                } else {
                    createDirectory(target.parentFile)
                    BufferedOutputStream(FileOutputStream(target)).use { out ->
                        while (true) {
                            val count = archive.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                    entry.lastModifiedDate?.time?.takeIf { it > 0L }?.let(target::setLastModified)
                }
            }
        }
    }

    @Throws(IOException::class, UnsafeArchiveEntryException::class)
    private fun extractTar(input: InputStream, output: File) {
        TarArchiveInputStream(BufferedInputStream(input)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) throw UnsafeArchiveEntryException(entry.name)
                writeStreamEntry(archive, entry, output)
            }
        }
    }

    @Throws(IOException::class, UnsafeArchiveEntryException::class)
    private fun writeStreamEntry(input: InputStream, entry: ArchiveEntry, output: File) {
        val target = safeTarget(output, entry.name)
        if (entry.isDirectory) {
            createDirectory(target)
        } else {
            createDirectory(target.parentFile)
            BufferedOutputStream(FileOutputStream(target)).use(input::copyTo)
            entry.lastModifiedDate?.time?.takeIf { it > 0L }?.let(target::setLastModified)
        }
    }

    @Throws(IOException::class)
    private fun extractSingle(input: InputStream, output: File, name: String) {
        input.use { source ->
            BufferedOutputStream(FileOutputStream(File(output, name))).use(source::copyTo)
        }
    }

    private fun openRar(file: File, password: CharArray?): Archive {
        val options = ArchiveOptions.builder()
            .password(password)
            .maxDictionarySize(MAX_ARCHIVE_MEMORY_BYTES)
            .build()
        return Archive(file, options)
    }

    private fun openSevenZ(source: ResolvedArchiveSource, password: CharArray?): SevenZFile {
        val builder = SevenZFile.builder()
            .setPassword(password)
            .setMaxMemoryLimitKiB(MAX_ARCHIVE_MEMORY_KIB)
        if (source.parts.size <= 1) return builder.setFile(source.primary).get()
        val channel = MultiReadOnlySeekableByteChannel.forFiles(*source.parts.toTypedArray())
        return try {
            builder.setDefaultName(source.primary.name).setSeekableByteChannel(channel).get()
        } catch (error: Exception) {
            channel.close()
            throw error
        }
    }

    private fun gzip(file: File): InputStream =
        GzipCompressorInputStream.builder()
            .setInputStream(BufferedInputStream(FileInputStream(file)))
            .setDecompressConcatenated(true)
            .get()

    private fun bzip2(file: File): InputStream =
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(file)), true)

    private fun xz(file: File): InputStream =
        XZCompressorInputStream.builder()
            .setInputStream(BufferedInputStream(FileInputStream(file)))
            .setDecompressConcatenated(true)
            .setMemoryLimitKiB(MAX_ARCHIVE_MEMORY_KIB)
            .get()

    @Throws(UnsafeArchiveEntryException::class)
    private fun safeDisplayPath(entryName: String): String {
        val slashed = entryName.replace('\\', '/')
        val normalized = slashed.trim('/')
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (normalized.isBlank() || slashed.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            parts.isEmpty() || parts.any { it == ".." }
        ) throw UnsafeArchiveEntryException(entryName)
        return parts.joinToString("/")
    }

    @Throws(IOException::class, UnsafeArchiveEntryException::class)
    private fun safeTarget(root: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.isBlank()) throw UnsafeArchiveEntryException(entryName)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, normalized).canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        if (target == canonicalRoot || !target.path.startsWith(rootPrefix)) {
            throw UnsafeArchiveEntryException(entryName)
        }
        return target
    }

    @Throws(ArchivePluginException::class)
    private fun createDirectory(directory: File?) {
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw ArchivePluginException(ArchivePluginError.CREATE_DIRECTORY, directory.name)
        }
    }

    @Throws(IOException::class, ArchivePluginException::class)
    private fun move(source: File, target: File) {
        if (source.renameTo(target)) return
        copy(source, target)
        if (!delete(source)) throw ArchivePluginException(ArchivePluginError.EXTRACT_FAILED, source.name)
    }

    @Throws(IOException::class, ArchivePluginException::class)
    private fun copy(source: File, target: File) {
        if (source.isDirectory) {
            createDirectory(target)
            source.listFiles()?.forEach { copy(it, File(target, it.name)) }
        } else {
            createDirectory(target.parentFile)
            FileInputStream(source).use { input -> FileOutputStream(target).use(input::copyTo) }
        }
    }

    private fun delete(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { if (!delete(it)) return false }
        return !file.exists() || file.delete()
    }

    private fun availableTarget(directory: File, name: String): File {
        var target = File(directory, name)
        var index = 1
        while (target.exists()) target = File(directory, "$name (${index++})")
        return target
    }

    private fun isPasswordError(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.lowercase() }
        .any { "password" in it }

    private fun isSevenZPasswordFailure(error: Throwable, password: CharArray?): Boolean {
        if (isPasswordError(error)) return true
        if (password == null) return false
        return generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message?.lowercase().orEmpty()
            "checksum" in message || "crc" in message ||
                cause.javaClass.name == "org.tukaani.xz.CorruptedInputException"
        }
    }

    private fun passwordFailure(password: CharArray?, file: File): ArchivePluginResult.Failure = failure(
        if (password == null) ArchivePluginError.PASSWORD_REQUIRED else ArchivePluginError.WRONG_PASSWORD,
        file.name
    )

    private fun failure(error: ArchivePluginError, subject: String): ArchivePluginResult.Failure =
        ArchivePluginResult.Failure(error, subject)

    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_ARCHIVE_MEMORY_KIB = 128 * 1024
    private const val MAX_ARCHIVE_MEMORY_BYTES = 128L * 1024 * 1024
}

internal enum class ArchiveFormat(private vararg val suffixes: String) {
    TAR_GZIP("tar.gz", "tgz"),
    TAR_BZIP2("tar.bz2", "tbz2", "tbz"),
    TAR_XZ("tar.xz", "txz"),
    SEVEN_Z("7z"),
    RAR("rar"),
    ZIP("zip", "zipx"),
    TAR("tar"),
    GZIP("gz", "gzip"),
    BZIP2("bz2", "bzip2"),
    XZ("xz");

    fun outputName(fileName: String): String {
        val suffix = suffixes.firstOrNull { fileName.endsWith(".$it", ignoreCase = true) }
        return suffix?.let { fileName.dropLast(it.length + 1) }?.ifBlank { "archive" } ?: fileName
    }

    companion object {
        fun forFile(file: File): ArchiveFormat? {
            if (Regex("(?i)^.+\\.zip\\.\\d{3,}$").matches(file.name)) return ZIP
            if (Regex("(?i)^.+\\.7z\\.\\d{3,}$").matches(file.name)) return SEVEN_Z
            return entries.firstOrNull { format ->
                format.suffixes.any { file.name.endsWith(".$it", ignoreCase = true) }
            }
        }

        fun outputNameForSingle(fileName: String): String =
            forFile(File(fileName))?.outputName(fileName) ?: fileName
    }
}

private class UnsafeArchiveEntryException(val entryName: String) : IOException(entryName)
private class ArchivePluginException(val error: ArchivePluginError, val subject: String?) : IOException(subject)
