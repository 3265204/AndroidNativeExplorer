package com.ane.filemanager.core.file

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/** Canonical extension and MIME lookup for host and plugin file adapters. */
internal object FileTypeResolver {
    fun extension(file: File): String =
        file.extension.lowercase(Locale.ROOT)

    fun mimeType(file: File, unknownType: String): String =
        mimeType(extension(file), unknownType)

    fun mimeType(extension: String, unknownType: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: unknownType
}
