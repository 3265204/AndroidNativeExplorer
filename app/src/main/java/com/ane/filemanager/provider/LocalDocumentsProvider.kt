package com.ane.filemanager.provider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.ane.filemanager.R
import com.ane.filemanager.core.file.FileTypeResolver
import java.io.File
import java.io.FileNotFoundException

/** Exposes shared storage through Android's Storage Access Framework. */
class LocalDocumentsProvider : DocumentsProvider() {
    private val storageRoot: File by lazy {
        Environment.getExternalStorageDirectory().canonicalFile
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns, 1).apply {
            newRow().addValues(columns) { column ->
                when (column) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_DOCUMENT_ID
                    DocumentsContract.Root.COLUMN_TITLE -> context?.getString(R.string.app_name) ?: "ANE"
                    DocumentsContract.Root.COLUMN_SUMMARY -> context?.getString(R.string.storage)
                    DocumentsContract.Root.COLUMN_FLAGS ->
                        DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                    DocumentsContract.Root.COLUMN_ICON -> R.mipmap.ic_launcher
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*"
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> storageRoot.usableSpace
                    else -> null
                }
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns, 1).apply {
            include(documentId, fileFor(documentId))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        return MatrixCursor(columns).apply {
            parent.listFiles().orEmpty().forEach { child ->
                runCatching { include(documentIdFor(storageRoot, child), child.canonicalFile) }
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val file = fileFor(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { fileFor(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { fileFor(documentId) }.getOrNull() ?: return false
        return child != parent && child.path.startsWith(parent.path + File.separator)
    }

    private fun fileFor(documentId: String): File {
        val file = when (documentId) {
            ROOT_DOCUMENT_ID -> storageRoot
            else -> File(storageRoot, documentId).canonicalFile
        }
        if (file != storageRoot && !file.path.startsWith(storageRoot.path + File.separator)) {
            throw FileNotFoundException("Path outside storage root")
        }
        if (!file.exists()) throw FileNotFoundException("Document not found: $documentId")
        return file
    }

    private fun MatrixCursor.include(documentId: String, file: File) {
        newRow().addValues(columnNames) { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                    if (documentId == ROOT_DOCUMENT_ID) context?.getString(R.string.storage) else file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeTypeFor(file)
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                DocumentsContract.Document.COLUMN_SIZE -> file.takeIf(File::isFile)?.length()
                DocumentsContract.Document.COLUMN_FLAGS -> if (file.isDirectory) {
                    DocumentsContract.Document.FLAG_DIR_PREFERS_GRID
                } else 0
                else -> null
            }
        }
    }

    private fun mimeTypeFor(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        return FileTypeResolver.mimeType(file, "application/octet-stream")
    }

    private inline fun MatrixCursor.RowBuilder.addValues(
        columns: Array<out String>,
        valueFor: (String) -> Any?
    ) {
        columns.forEach { add(valueFor(it)) }
    }

    companion object {
        private const val ROOT_ID = "shared_storage"
        private const val ROOT_DOCUMENT_ID = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        fun uriFor(context: Context, file: File): Uri {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val documentId = documentIdFor(root, file)
            return DocumentsContract.buildDocumentUri("${context.packageName}.documents", documentId)
        }

        fun treeUriFor(context: Context, directory: File): Uri {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val documentId = documentIdFor(root, directory)
            return DocumentsContract.buildTreeDocumentUri("${context.packageName}.documents", documentId)
        }

        private fun documentIdFor(root: File, file: File): String {
            val canonical = file.canonicalFile
            if (canonical == root) return ROOT_DOCUMENT_ID
            if (!canonical.path.startsWith(root.path + File.separator)) {
                throw FileNotFoundException("Path outside storage root")
            }
            return canonical.path.removePrefix(root.path + File.separator)
        }
    }
}
