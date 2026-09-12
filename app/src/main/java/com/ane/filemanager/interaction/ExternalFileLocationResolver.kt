package com.ane.filemanager.interaction

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
import com.ane.filemanager.storage.StorageLocations
import java.io.File

internal data class ExternalFileLocation(
    val originalPath: String,
    val accessibleFile: File?,
    val navigationRoot: File? = null
)

/** Resolves a granted URI off the UI thread to an accessible original file or directory. */
internal class ExternalFileLocationResolver(private val context: Context) {
    fun resolve(uri: Uri): ExternalFileLocation? {
        val paths = SharedStoragePaths(
            Environment.getExternalStorageDirectory(),
            StorageLocations.mounted(context).map { it.directory }
        )
        fun originalLocation(path: String): ExternalFileLocation? {
            if (!File(path).isAbsolute) return null
            return ExternalFileLocation(path, paths.readableOriginal(path))
        }
        if (uri.scheme == "file") return uri.path?.let(::originalLocation)
        if (uri.scheme != "content") return null

        val documentId = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull()
        if (documentId != null) {
            val document = when (uri.authority) {
                "${context.packageName}.documents" -> paths.ownDocument(documentId)
                "com.android.externalstorage.documents" -> paths.externalDocument(documentId)
                else -> null
            }
            if (document != null) return ExternalFileLocation(document.path, document)
        }

        // FileProvider paths are aliases, not filesystem paths. The granted descriptor can
        // identify the backing file without relying on QQ/TIM-specific path conventions.
        val descriptorFile = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                originalLocation(Os.readlink("/proc/self/fd/${descriptor.fd}"))
            }
        }.getOrNull()
        if (descriptorFile?.accessibleFile != null) return descriptorFile

        // Some local providers expose an original path but return a pipe from openFile.
        // _data is optional; a display name alone cannot establish the original location.
        val dataLocation = runCatching {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex("_data")
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                    originalLocation(cursor.getString(column))
                } else null
            }
        }.getOrNull()
        if (dataLocation?.accessibleFile != null) return dataLocation

        val original = dataLocation ?: descriptorFile
        val imported = runCatching { TemporaryExternalFileStore(context).prepare(uri) }.getOrNull()
        return if (imported != null) {
            ExternalFileLocation(
                originalPath = original?.originalPath ?: uri.toString(),
                accessibleFile = imported.file,
                navigationRoot = imported.sessionDirectory
            )
        } else original
    }
}
