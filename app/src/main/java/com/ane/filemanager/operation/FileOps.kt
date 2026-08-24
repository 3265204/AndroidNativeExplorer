package com.ane.filemanager.operation

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal class FileOperationException(
    val failure: FileFailure,
    val subject: String? = null,
    cause: Throwable? = null
) : IOException(failure.name, cause)

object FileOps {
    /**
     * Returns an unused sibling name by incrementing the stem while leaving the extension intact.
     * For example: a.txt, a1.txt, a2.txt. Dotfiles such as .env are treated as extensionless.
     */
    fun numberedTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var suffix = 1
        while (target.exists()) target = File(dir, "$stem${suffix++}$extension")
        return target
    }

    fun availableTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) target = File(dir, "$base (${i++})$ext")
        return target
    }

    @Throws(IOException::class)
    fun copy(source: File, target: File) {
        val targetExisted = target.exists()
        try {
            if (source.isDirectory) {
                if (isInside(target, source)) throw FileOperationException(FileFailure.COPY_INTO_SELF, source.name)
                if (!target.exists() && !target.mkdirs()) throw FileOperationException(FileFailure.CREATE_DIRECTORY, target.name)
                val children = source.listFiles()
                    ?: throw FileOperationException(FileFailure.COPY_FAILED, source.name)
                children.forEach { copy(it, File(target, it.name)) }
            } else {
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) throw FileOperationException(FileFailure.CREATE_DIRECTORY, it.name)
                }
                FileInputStream(source).channel.use { input ->
                    FileOutputStream(target).channel.use { output ->
                        var pos = 0L
                        while (pos < input.size()) {
                            pos += input.transferTo(pos, minOf(16L * 1024 * 1024, input.size() - pos), output)
                        }
                    }
                }
                target.setLastModified(source.lastModified())
            }
        } catch (error: Exception) {
            if (!targetExisted && target.exists()) {
                FileOps.delete(target)
            }
            throw error
        }
    }

    @Throws(IOException::class)
    fun move(source: File, target: File) {
        if (source == target) return
        if (isInside(target, source)) throw FileOperationException(FileFailure.MOVE_INTO_SELF, source.name)
        if (!source.renameTo(target)) {
            copy(source, target)
            if (!delete(source)) throw FileOperationException(FileFailure.PARTIAL_MOVE, source.name)
        }
    }

    fun delete(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { if (!delete(it)) return false }
        return file.delete()
    }

    @Throws(IOException::class)
    fun isInside(child: File, possibleParent: File): Boolean =
        (child.canonicalPath + File.separator).startsWith(possibleParent.canonicalPath + File.separator)

}
