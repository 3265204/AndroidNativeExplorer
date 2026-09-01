package com.ane.filemanager.core.file

import java.io.File
import java.text.Collator
import java.util.Locale

/** Ordered files beside an opened file; plugins supply only their own acceptance rule. */
internal class SiblingFileSequence private constructor(
    val files: List<File>,
    initialIndex: Int
) {
    var index = initialIndex
        private set

    val current: File
        get() = files[index]
    val hasPrevious: Boolean
        get() = index > 0
    val hasNext: Boolean
        get() = index < files.lastIndex
    val positionLabel: String
        get() = "${index + 1} / ${files.size}"

    fun moveBy(delta: Int): File? {
        val target = index + delta
        if (target !in files.indices) return null
        index = target
        return current
    }

    companion object {
        fun create(opened: File, accepts: (File) -> Boolean): SiblingFileSequence {
            val collator = Collator.getInstance(Locale.getDefault()).apply {
                strength = Collator.PRIMARY
            }
            val siblings = opened.parentFile
                ?.listFiles()
                ?.asSequence()
                ?.filter(accepts)
                ?.sortedWith { left, right -> collator.compare(left.name, right.name) }
                ?.toList()
                .orEmpty()
            val files = if (siblings.any { sameFile(it, opened) }) {
                siblings
            } else {
                listOf(opened)
            }
            val index = files.indexOfFirst { sameFile(it, opened) }.coerceAtLeast(0)
            return SiblingFileSequence(files, index)
        }

        private fun sameFile(left: File, right: File): Boolean = try {
            left.canonicalPath == right.canonicalPath
        } catch (_: Exception) {
            left.absolutePath == right.absolutePath
        }
    }
}
