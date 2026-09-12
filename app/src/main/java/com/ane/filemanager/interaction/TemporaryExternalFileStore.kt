package com.ane.filemanager.interaction

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

internal data class TemporaryExternalFile(val file: File, val sessionDirectory: File)

/** Materializes a URI grant so ANE can show it in a normal, session-only directory. */
internal class TemporaryExternalFileStore(private val context: Context) {
    private val rootDirectory = File(context.externalCacheDir ?: context.cacheDir, DIRECTORY_NAME)

    @Throws(IOException::class)
    fun prepare(uri: Uri): TemporaryExternalFile {
        cleanupExpired()
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IOException("Could not create external-location cache")
        }
        val session = File(rootDirectory, "locate-${UUID.randomUUID()}")
        if (!session.mkdir()) throw IOException("Could not create external-location session")
        val target = File(session, safeName(displayName(uri)))
        return try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("The shared file cannot be opened")
            source.buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("File import was interrupted")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            session.setLastModified(System.currentTimeMillis())
            TemporaryExternalFile(target, session)
        } catch (error: Exception) {
            session.deleteRecursively()
            if (error is IOException) throw error
            throw IOException("Could not import the shared file", error)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column)
                else null
            }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: FALLBACK_NAME

    private fun safeName(name: String): String {
        val sanitized = name.replace('/', '_').replace('\\', '_')
            .filterNot { it == '\u0000' || it.code in 1..31 }
            .trim().trim('.')
        return sanitized.ifBlank { FALLBACK_NAME }.take(MAX_NAME_LENGTH)
    }

    private fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        rootDirectory.listFiles().orEmpty()
            .filter(File::isDirectory)
            .filter { now - it.lastModified() >= MAX_AGE_MILLIS }
            .forEach(File::deleteRecursively)
    }

    private companion object {
        const val DIRECTORY_NAME = "CleanOnExit"
        const val FALLBACK_NAME = "shared-file"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_NAME_LENGTH = 180
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
