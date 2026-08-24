package com.ane.filemanager.plugin.video

import java.io.File
import java.text.Collator
import java.util.Locale

internal class VideoSequence private constructor(val files: List<File>, initialIndex: Int) {
    var index = initialIndex
        private set
    val current get() = files[index]
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
        fun create(opened: File, accepts: (File) -> Boolean): VideoSequence {
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            val siblings = opened.parentFile?.listFiles()?.asSequence()?.filter(accepts)
                ?.sortedWith { left, right -> collator.compare(left.name, right.name) }?.toList().orEmpty()
            val files = if (siblings.any { sameFile(it, opened) }) siblings else listOf(opened)
            return VideoSequence(files, files.indexOfFirst { sameFile(it, opened) }.coerceAtLeast(0))
        }

        private fun sameFile(left: File, right: File) = try {
            left.canonicalPath == right.canonicalPath
        } catch (_: Exception) {
            left.absolutePath == right.absolutePath
        }
    }
}
