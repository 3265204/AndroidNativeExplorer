package com.ane.filemanager.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.ane.filemanager.core.file.FileTypeResolver
import java.io.File
import java.io.FileNotFoundException

/** Small read-only provider used to hand local files to external viewer apps safely. */
class LocalFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val file = checkedFile(uri)
        // Receivers such as WeChat may open a shared URI more than once (for example, once to
        // inspect it and again to copy it). Keep temporary share files alive for those subsequent
        // reads; SharePreparationStore removes expired sessions separately.
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val file = checkedFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri): String {
        return FileTypeResolver.mimeType(checkedFile(uri), "application/octet-stream")
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException("Read only")

    private fun checkedFile(uri: Uri): File {
        val file = File(uri.path ?: throw FileNotFoundException()).canonicalFile
        val roots = listOfNotNull(
            Environment.getExternalStorageDirectory(), context?.filesDir, context?.getExternalFilesDir(null)
        ).map { it.canonicalFile }
        if (roots.none { file == it || file.path.startsWith(it.path + File.separator) } || !file.isFile) {
            throw FileNotFoundException("Path not allowed")
        }
        return file
    }

    companion object {
        fun uriFor(context: Context, file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.files")
            .path(file.canonicalPath)
            .build()
    }
}
