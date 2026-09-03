package com.ane.filemanager.sharing

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class PreparedShare(
    val files: List<File>,
    val sessionDirectory: File?
)

/** Creates and owns private ZIP files for folders and multi-item shares. */
internal class SharePreparationStore(private val rootDirectory: File) {
    @Throws(IOException::class)
    fun prepare(sources: List<File>): PreparedShare {
        if (sources.any { !it.exists() || (!it.isFile && !it.isDirectory) }) {
            throw IOException("A selected item is no longer available")
        }
        if (sources.size == 1 && sources.single().isFile) return PreparedShare(sources, null)
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IOException("Could not create temporary share directory")
        }
        val session = File(rootDirectory, "share-${UUID.randomUUID()}")
        if (!session.mkdir()) throw IOException("Could not create temporary share session")
        return try {
            val archiveName = if (sources.size == 1) sources.single().name else MULTI_ITEM_ARCHIVE_NAME
            val archive = zipSources(sources, availableArchive(session, archiveName))
            session.setLastModified(System.currentTimeMillis())
            PreparedShare(listOf(archive), session)
        } catch (error: Exception) {
            session.deleteRecursively()
            if (error is IOException) throw error
            throw IOException("Could not prepare folder for sharing", error)
        }
    }

    /** Called after a receiver closes a temporary archive; original shared files are never removed. */
    fun removeAfterRead(file: File) {
        val session = temporarySessionFor(file) ?: return
        file.delete()
        if (session.listFiles().isNullOrEmpty()) session.delete()
    }

    fun removeSession(session: File?) {
        if (session == null || !isDirectChild(rootDirectory, session)) return
        session.deleteRecursively()
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis(), maxAgeMillis: Long = MAX_AGE_MILLIS) {
        rootDirectory.listFiles().orEmpty()
            .filter(File::isDirectory)
            .filter { now - it.lastModified() >= maxAgeMillis }
            .forEach(File::deleteRecursively)
    }

    fun isTemporaryArchive(file: File): Boolean = temporarySessionFor(file) != null

    private fun temporarySessionFor(file: File): File? {
        val parent = file.parentFile ?: return null
        return parent.takeIf { file.isFile && isDirectChild(rootDirectory, it) }
    }

    private fun zipSources(sources: List<File>, target: File): File {
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { archive ->
                val usedRootNames = mutableSetOf<String>()
                val visitedDirectories = mutableSetOf<String>()
                sources.forEach { source ->
                    addToZip(
                        archive,
                        source,
                        availableEntryName(safeName(source.name), usedRootNames),
                        visitedDirectories
                    )
                }
            }
            return target
        } catch (error: Exception) {
            target.delete()
            if (error is IOException) throw error
            throw IOException("Could not create share archive", error)
        }
    }

    private fun addToZip(
        archive: ZipOutputStream,
        file: File,
        entryName: String,
        visitedDirectories: MutableSet<String>
    ) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Folder sharing was interrupted")
        val directory = file.isDirectory
        if (!directory && !file.isFile) throw IOException("Could not read ${file.name}")
        if (directory && !visitedDirectories.add(file.canonicalPath)) return
        archive.putNextEntry(ZipEntry(if (directory) "$entryName/" else entryName).apply {
            time = file.lastModified()
        })
        if (file.isFile) file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Folder sharing was interrupted")
                }
                val count = input.read(buffer)
                if (count < 0) break
                archive.write(buffer, 0, count)
            }
        }
        archive.closeEntry()
        if (directory) {
            val children = file.listFiles() ?: throw IOException("Could not read ${file.name}")
            children.sortedBy { it.name.lowercase() }.forEach { child ->
                addToZip(archive, child, "$entryName/${safeName(child.name)}", visitedDirectories)
            }
        }
    }

    private fun availableArchive(session: File, requestedName: String): File {
        val withoutZip = requestedName.removeSuffix(".zip")
        val baseName = safeName(withoutZip).trim().trim('.').ifBlank { "shared-files" }
        var target = File(session, "$baseName.zip")
        var suffix = 1
        while (target.exists()) target = File(session, "$baseName (${suffix++}).zip")
        return target
    }

    private fun availableEntryName(requestedName: String, usedNames: MutableSet<String>): String {
        val baseName = requestedName.ifBlank { "item" }
        var name = baseName
        var suffix = 1
        while (!usedNames.add(name.lowercase())) name = "$baseName (${suffix++})"
        return name
    }

    private fun safeName(name: String): String = name.replace('/', '_').replace('\\', '_')

    private fun isDirectChild(parent: File, child: File): Boolean = runCatching {
        child.canonicalFile.parentFile == parent.canonicalFile
    }.getOrDefault(false)

    companion object {
        const val DIRECTORY_NAME = "share-temp"
        private const val MULTI_ITEM_ARCHIVE_NAME = "shared-files.zip"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
