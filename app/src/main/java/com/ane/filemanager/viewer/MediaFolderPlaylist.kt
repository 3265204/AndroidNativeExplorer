package com.ane.filemanager.viewer

import java.io.File
import java.text.Collator
import java.util.Locale

internal enum class MediaKind(val extensions: Set<String>) {
    IMAGE(setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")),
    VIDEO(setOf("mp4", "m4v", "3gp", "3gpp", "webm", "mkv", "m2ts", "mts", "mov", "avi")),
    AUDIO(setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mid", "midi"));

    fun accepts(file: File) = file.isFile && file.extension.lowercase() in extensions
}

/** A stable, filename-sorted snapshot of media files beside the file that opened the viewer. */
internal class MediaFolderPlaylist private constructor(
    val files: List<File>,
    initialIndex: Int
) {
    var index: Int = initialIndex
        private set

    val current: File get() = files[index]
    val hasPrevious get() = index > 0
    val hasNext get() = index < files.lastIndex
    val positionLabel get() = "${index + 1} / ${files.size}"

    fun moveBy(delta: Int): File? {
        val target = index + delta
        if (target !in files.indices) return null
        index = target
        return current
    }

    companion object {
        fun create(opened: File, kind: MediaKind): MediaFolderPlaylist {
            val collator = Collator.getInstance(Locale.getDefault()).apply {
                strength = Collator.PRIMARY
            }
            val siblings = opened.parentFile?.listFiles()
                ?.asSequence()
                ?.filter(kind::accepts)
                ?.sortedWith { left, right -> collator.compare(left.name, right.name) }
                ?.toList()
                .orEmpty()
            val files = if (siblings.any { sameFile(it, opened) }) siblings else listOf(opened)
            val index = files.indexOfFirst { sameFile(it, opened) }.coerceAtLeast(0)
            return MediaFolderPlaylist(files, index)
        }

        private fun sameFile(left: File, right: File): Boolean = try {
            left.canonicalPath == right.canonicalPath
        } catch (_: Exception) {
            left.absolutePath == right.absolutePath
        }
    }
}
