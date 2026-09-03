package com.ane.filemanager.operation

import java.io.File

/** Shared preflight rules for paste and drag-and-drop transfer destinations. */
internal object TransferTargetPolicy {
    fun sourceNestedByTarget(sources: Collection<File>, targetDirectory: File): File? =
        sources.firstOrNull { source ->
            source.isDirectory && isSameOrInside(targetDirectory, source)
        }

    fun accepts(sources: Collection<File>, targetDirectory: File): Boolean =
        sourceNestedByTarget(sources, targetDirectory) == null

    private fun isSameOrInside(candidate: File, possibleParent: File): Boolean {
        val candidatePath = normalizedPath(candidate)
        val parentPath = normalizedPath(possibleParent)
        if (candidatePath == parentPath) return true
        val parentPrefix = parentPath.trimEnd(File.separatorChar) + File.separator
        return candidatePath.startsWith(parentPrefix)
    }

    private fun normalizedPath(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }
}
