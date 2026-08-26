package com.ane.filemanager.plugin.archive

internal data class ArchiveEntryInfo(
    val path: String,
    val directory: Boolean,
    val size: Long? = null
)

internal data class ArchiveBrowserItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long?,
    val childCount: Int
)

/** Builds missing parent directories and exposes one Windows-Explorer-like level at a time. */
internal class ArchiveHierarchy(entries: List<ArchiveEntryInfo>) {
    private data class Node(
        val name: String,
        val path: String,
        var directory: Boolean,
        var size: Long?
    )

    private val nodes = linkedMapOf<String, Node>()
    private val childrenByParent: Map<String, List<Node>>

    init {
        entries.forEach { entry ->
            val parts = entry.path.split('/').filter(String::isNotBlank)
            parts.forEachIndexed { index, name ->
                val path = parts.take(index + 1).joinToString("/")
                val parent = index < parts.lastIndex
                val existing = nodes[path]
                if (existing == null) {
                    nodes[path] = Node(
                        name = name,
                        path = path,
                        directory = parent || entry.directory,
                        size = if (parent || entry.directory) null else entry.size
                    )
                } else if (parent || entry.directory) {
                    existing.directory = true
                    existing.size = null
                } else if (!existing.directory) {
                    existing.size = entry.size
                }
            }
        }
        childrenByParent = nodes.values.groupBy { it.path.substringBeforeLast('/', "") }
    }

    fun children(directory: String): List<ArchiveBrowserItem> {
        return childrenByParent[directory].orEmpty().asSequence()
            .map { node ->
                ArchiveBrowserItem(
                    name = node.name,
                    path = node.path,
                    directory = node.directory,
                    size = node.size,
                    childCount = if (node.directory) childrenByParent[node.path].orEmpty().size else 0
                )
            }
            .sortedWith(compareByDescending<ArchiveBrowserItem> { it.directory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name })
            .toList()
    }

    fun parent(directory: String): String = directory.substringBeforeLast('/', "")

}
