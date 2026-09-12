package com.ane.filemanager.interaction

import java.io.File

/** Validates original locations; never guesses a file by display name or imports a copy. */
internal class SharedStoragePaths(primary: File, volumes: List<File>) {
    private val primary = primary.canonicalFile
    private val roots = (volumes + primary).map { it.canonicalFile }.distinct()

    /** Absolute original locations may be private when this process can actually access them. */
    fun readableOriginal(path: String): File? = runCatching {
        if (!File(path).isAbsolute) return null
        val file = File(path).canonicalFile
        if (!file.canRead()) return null
        when {
            file.isDirectory -> file
            file.isFile && file.parentFile?.let { it.isDirectory && it.canRead() } == true -> file
            else -> null
        }
    }.getOrNull()

    /** Document IDs still have to stay inside the volume they claim to belong to. */
    fun readable(path: String): File? = readableOriginal(path)?.takeIf { file ->
        roots.any { contains(it, file) }
    }

    fun ownDocument(id: String): File? = when {
        id == "root" -> readable(primary.path)
        id.startsWith("volume:") -> {
            val parts = id.removePrefix("volume:").split(':', limit = 2)
            val root = roots.firstOrNull { it.name == parts[0] }
            root?.let { relative(it, parts.getOrElse(1) { "" }) }
        }
        else -> relative(primary, id)
    }

    fun externalDocument(id: String): File? {
        if (id.startsWith("raw:")) return readable(id.removePrefix("raw:"))
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = when {
            volume.equals("primary", ignoreCase = true) -> primary
            volume.equals("home", ignoreCase = true) -> File(primary, "Documents")
            else -> roots.firstOrNull { it.name.equals(volume, ignoreCase = true) }
        } ?: return null
        return relative(root, path)
    }

    private fun relative(root: File, path: String): File? = runCatching {
        if (File(path).isAbsolute) return null
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, path).canonicalFile
        if (!contains(canonicalRoot, file)) return null
        readable(file.path)
    }.getOrNull()

    private fun contains(root: File, file: File): Boolean =
        file == root || file.path.startsWith(root.path + File.separator)
}
