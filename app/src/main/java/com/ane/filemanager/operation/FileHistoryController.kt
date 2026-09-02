package com.ane.filemanager.operation

/** A reversible filesystem mutation. Both directions run on the file-operation worker. */
internal class FileHistoryAction(
    val label: String,
    val undo: () -> FileResult<Unit>,
    val redo: () -> FileResult<Unit>
)

/** Read-only history data that can be shown by a future history/agent UI. */
internal data class FileHistoryNode(
    val id: Long,
    val parentId: Long?,
    val label: String?,
    val childIds: List<Long>,
    val current: Boolean
)

/**
 * Session-scoped branching operation history.
 *
 * Recording after an undo adds a sibling branch instead of discarding the old future. Moving to a
 * node first undoes to the lowest common ancestor, then redoes down the selected branch. The cursor
 * advances only after each filesystem step succeeds, so a failed checkout always describes the
 * state that was actually reached.
 */
internal class FileHistoryController {
    private class Node(
        val id: Long,
        val parent: Node?,
        val action: FileHistoryAction?
    ) {
        val children = mutableListOf<Node>()
    }

    private val root = Node(ROOT_ID, null, null)
    private val nodes = linkedMapOf(ROOT_ID to root)
    private var cursor = root
    private var nextId = ROOT_ID + 1

    val canUndo get() = synchronized(this) { cursor !== root }
    val canRedo get() = synchronized(this) { cursor.children.isNotEmpty() }
    val currentId get() = synchronized(this) { cursor.id }
    val size get() = synchronized(this) { nodes.size - 1 }

    @Synchronized
    fun push(action: FileHistoryAction): Long {
        val node = Node(nextId++, cursor, action)
        cursor.children += node
        nodes[node.id] = node
        cursor = node
        return node.id
    }

    @Synchronized
    fun undo(): FileResult<Unit> {
        val action = cursor.action ?: return FileResult.Success(Unit)
        return action.undo().onSuccess { cursor = checkNotNull(cursor.parent) }
    }

    /** Redoes the newest branch by default, or a specific direct child when [childId] is supplied. */
    @Synchronized
    fun redo(childId: Long? = null): FileResult<Unit> {
        val child = when (childId) {
            null -> cursor.children.lastOrNull()
            else -> cursor.children.firstOrNull { it.id == childId }
        } ?: return FileResult.Failure(FileProblem(FileFailure.HISTORY_NODE_MISSING, childId?.toString()))
        val action = checkNotNull(child.action)
        return action.redo().onSuccess { cursor = child }
    }

    /** Moves the filesystem and cursor to any known node without deleting other branches. */
    @Synchronized
    fun checkout(targetId: Long): FileResult<Unit> {
        val target = nodes[targetId]
            ?: return FileResult.Failure(FileProblem(FileFailure.HISTORY_NODE_MISSING, targetId.toString()))
        if (target === cursor) return FileResult.Success(Unit)

        val currentAncestors = ancestors(cursor).associateBy(Node::id)
        val targetPath = ancestors(target)
        val common = targetPath.first { it.id in currentAncestors }

        while (cursor !== common) {
            when (val result = undo()) {
                is FileResult.Success -> Unit
                is FileResult.Failure -> return result
            }
        }

        val forward = targetPath.takeWhile { it !== common }.asReversed()
        forward.forEach { node ->
            when (val result = redo(node.id)) {
                is FileResult.Success -> Unit
                is FileResult.Failure -> return result
            }
        }
        return FileResult.Success(Unit)
    }

    @Synchronized
    fun snapshot(): List<FileHistoryNode> = nodes.values.map { node ->
        FileHistoryNode(
            id = node.id,
            parentId = node.parent?.id,
            label = node.action?.label,
            childIds = node.children.map(Node::id),
            current = node === cursor
        )
    }

    private fun ancestors(start: Node): List<Node> = buildList {
        var node: Node? = start
        while (node != null) {
            add(node)
            node = node.parent
        }
    }

    private inline fun FileResult<Unit>.onSuccess(block: () -> Unit): FileResult<Unit> {
        if (this is FileResult.Success) block()
        return this
    }

    private companion object {
        const val ROOT_ID = 0L
    }
}
