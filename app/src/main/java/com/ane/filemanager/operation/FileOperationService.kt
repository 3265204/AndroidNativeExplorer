package com.ane.filemanager.operation

import java.io.File
import java.util.UUID

internal enum class FileFailure {
    INVALID_NAME,
    NAME_EXISTS,
    CREATE_FAILED,
    RENAME_FAILED,
    DELETE_FAILED,
    SOURCE_MISSING,
    CREATE_DIRECTORY,
    MOVE_INTO_SELF,
    COPY_INTO_SELF,
    COPY_FAILED,
    MOVE_FAILED,
    PARTIAL_MOVE,
    HISTORY_NODE_MISSING,
    UNKNOWN
}

internal data class FileProblem(val failure: FileFailure, val subject: String? = null)

internal data class TransferRecord(
    val original: File,
    val result: File,
    val replaceOriginalOnUndo: Boolean = false
)
internal data class TransferBatch(val records: List<TransferRecord>, val skipped: Int = 0)
internal data class TransferInterruption(
    val completed: List<TransferRecord>,
    val failed: File,
    val remaining: List<File>,
    val targetDirectory: File,
    val moved: Boolean,
    val skipped: Int,
    val partialMove: TransferRecord? = null
)
internal data class TrashRecord(val original: File, val trashed: File)
internal data class RenameRecord(
    val original: File,
    val result: File,
    val replaced: List<TrashRecord> = emptyList()
)

internal enum class RenameConflictPolicy { FAIL, REPLACE, KEEP_BOTH }

internal sealed interface FileResult<out T> {
    data class Success<T>(val value: T) : FileResult<T>
    data class Failure(
        val problem: FileProblem,
        val transferInterruption: TransferInterruption? = null
    ) : FileResult<Nothing>
}

/**
 * Owns filesystem mutations and reports domain errors only. It deliberately has no Android Context
 * and no localized strings, so UI language and storage behavior can evolve independently.
 */
internal class FileOperationService {
    fun create(directory: File, name: String, folder: Boolean): FileResult<File> {
        if (!isValidName(name)) return FileResult.Failure(FileProblem(FileFailure.INVALID_NAME, name))
        val target = FileOps.numberedTarget(directory, name)
        val created = try {
            if (folder) target.mkdir() else target.createNewFile()
        } catch (_: Exception) {
            false
        }
        return if (created) FileResult.Success(target)
        else FileResult.Failure(FileProblem(FileFailure.CREATE_FAILED, name))
    }

    fun rename(
        file: File,
        newName: String,
        conflictPolicy: RenameConflictPolicy = RenameConflictPolicy.FAIL,
        trashRoot: File? = null
    ): FileResult<RenameRecord> {
        if (!isValidName(newName)) return FileResult.Failure(FileProblem(FileFailure.INVALID_NAME, newName))
        if (!file.exists()) return FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, file.name))
        val directory = file.parentFile
            ?: return FileResult.Failure(FileProblem(FileFailure.RENAME_FAILED, file.name))
        val requested = File(directory, newName)
        if (sameFile(file, requested)) return FileResult.Success(RenameRecord(file, file))

        val target = when {
            !requested.exists() -> requested
            conflictPolicy == RenameConflictPolicy.KEEP_BOTH -> FileOps.numberedTarget(directory, newName)
            conflictPolicy == RenameConflictPolicy.REPLACE -> requested
            else -> return FileResult.Failure(FileProblem(FileFailure.NAME_EXISTS, newName))
        }

        var replaced = emptyList<TrashRecord>()
        if (target.exists()) {
            val root = trashRoot
                ?: return FileResult.Failure(FileProblem(FileFailure.RENAME_FAILED, file.name))
            when (val movedAside = deleteToTrash(listOf(target), root)) {
                is FileResult.Success -> replaced = movedAside.value
                is FileResult.Failure -> return movedAside
            }
        }

        if (file.renameTo(target)) return FileResult.Success(RenameRecord(file, target, replaced))
        if (replaced.isNotEmpty()) restoreTrash(replaced)
        return FileResult.Failure(FileProblem(FileFailure.RENAME_FAILED, file.name))
    }

    fun undoRename(record: RenameRecord): FileResult<Unit> {
        if (sameFile(record.original, record.result)) return FileResult.Success(Unit)
        when (val restoredName = restorePath(record.result, record.original)) {
            is FileResult.Failure -> return restoredName
            is FileResult.Success -> Unit
        }
        if (record.replaced.isEmpty()) return FileResult.Success(Unit)
        return when (val restoredTarget = restoreTrash(record.replaced)) {
            is FileResult.Success -> restoredTarget
            is FileResult.Failure -> {
                // Preserve the completed rename if its replaced target cannot be restored.
                restorePath(record.original, record.result)
                restoredTarget
            }
        }
    }

    fun delete(files: List<File>): FileResult<Unit> {
        files.forEach { file ->
            if (!file.exists()) return@forEach
            if (!FileOps.delete(file)) return FileResult.Failure(FileProblem(FileFailure.DELETE_FAILED, file.name))
        }
        return FileResult.Success(Unit)
    }

    fun transfer(
        sources: List<File>,
        targetDirectory: File,
        move: Boolean,
        completed: List<TransferRecord> = emptyList(),
        skipped: Int = 0,
        partialMove: TransferRecord? = null
    ): FileResult<TransferBatch> {
        val records = completed.toMutableList()
        partialMove?.let { record ->
            val problem = finishPartialMove(record)
            if (problem != null) {
                return interruptedTransfer(
                    problem,
                    records,
                    record.original,
                    sources,
                    targetDirectory,
                    move,
                    skipped,
                    record
                )
            }
            records += record.copy(replaceOriginalOnUndo = false)
        }
        sources.forEachIndexed { index, source ->
            if (!source.exists()) {
                return interruptedTransfer(
                    FileProblem(FileFailure.SOURCE_MISSING, source.name),
                    records,
                    source,
                    sources.drop(index + 1),
                    targetDirectory,
                    move,
                    skipped,
                    null
                )
            }
            var attemptedTarget: File? = null
            try {
                if (move && source.parentFile?.canonicalFile == targetDirectory.canonicalFile) return@forEachIndexed
                val target = FileOps.availableTarget(targetDirectory, source.name)
                attemptedTarget = target
                if (move) FileOps.move(source, target) else FileOps.copy(source, target)
                records += TransferRecord(source, target)
            } catch (error: FileOperationException) {
                val partialRecord = attemptedTarget
                    ?.takeIf { error.failure == FileFailure.PARTIAL_MOVE && it.exists() }
                    ?.let { TransferRecord(source, it, replaceOriginalOnUndo = true) }
                return interruptedTransfer(
                    FileProblem(error.failure, error.subject ?: source.name),
                    records,
                    source,
                    sources.drop(index + 1),
                    targetDirectory,
                    move,
                    skipped,
                    partialRecord
                )
            } catch (_: Exception) {
                return interruptedTransfer(
                    FileProblem(if (move) FileFailure.MOVE_FAILED else FileFailure.COPY_FAILED, source.name),
                    records,
                    source,
                    sources.drop(index + 1),
                    targetDirectory,
                    move,
                    skipped,
                    null
                )
            }
        }
        return FileResult.Success(TransferBatch(records, skipped))
    }

    private fun interruptedTransfer(
        problem: FileProblem,
        completed: List<TransferRecord>,
        failed: File,
        remaining: List<File>,
        targetDirectory: File,
        moved: Boolean,
        skipped: Int,
        partialMove: TransferRecord?
    ): FileResult.Failure {
        return FileResult.Failure(
            problem,
            TransferInterruption(completed, failed, remaining, targetDirectory, moved, skipped, partialMove)
        )
    }

    private fun finishPartialMove(record: TransferRecord): FileProblem? {
        return try {
            if (record.original.exists() && !FileOps.delete(record.original)) {
                FileProblem(FileFailure.PARTIAL_MOVE, record.original.name)
            } else {
                null
            }
        } catch (_: Exception) {
            FileProblem(FileFailure.PARTIAL_MOVE, record.original.name)
        }
    }

    fun undoTransfer(records: List<TransferRecord>, moved: Boolean): FileResult<Unit> {
        records.forEach { record ->
            if (!record.result.exists()) {
                return FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, record.result.name))
            }
            if (moved && record.original.exists() && !record.replaceOriginalOnUndo) {
                return FileResult.Failure(FileProblem(FileFailure.NAME_EXISTS, record.original.name))
            }
        }

        val completed = mutableListOf<TransferRecord>()
        records.asReversed().forEach { record ->
            try {
                if (moved) {
                    if (record.replaceOriginalOnUndo && record.original.exists() && !FileOps.delete(record.original)) {
                        rollbackUndoneTransfer(completed, moved)
                        return FileResult.Failure(FileProblem(FileFailure.DELETE_FAILED, record.original.name))
                    }
                    FileOps.move(record.result, record.original)
                }
                else if (!FileOps.delete(record.result)) {
                    rollbackUndoneTransfer(completed, moved)
                    return FileResult.Failure(FileProblem(FileFailure.DELETE_FAILED, record.result.name))
                }
                completed += record
            } catch (error: FileOperationException) {
                rollbackUndoneTransfer(completed, moved)
                return FileResult.Failure(FileProblem(error.failure, error.subject ?: record.result.name))
            } catch (_: Exception) {
                rollbackUndoneTransfer(completed, moved)
                return FileResult.Failure(FileProblem(FileFailure.MOVE_FAILED, record.result.name))
            }
        }
        return FileResult.Success(Unit)
    }

    private fun rollbackUndoneTransfer(records: List<TransferRecord>, moved: Boolean) {
        redoTransfer(records.asReversed(), moved)
    }

    /** Reapplies an already-recorded transfer to its exact destinations. */
    fun redoTransfer(records: List<TransferRecord>, moved: Boolean): FileResult<Unit> {
        val completed = mutableListOf<TransferRecord>()
        records.forEach { record ->
            if (!record.original.exists()) {
                rollbackRedoneTransfer(completed, moved)
                return FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, record.original.name))
            }
            if (record.result.exists()) {
                rollbackRedoneTransfer(completed, moved)
                return FileResult.Failure(FileProblem(FileFailure.NAME_EXISTS, record.result.name))
            }
            try {
                if (moved) FileOps.move(record.original, record.result)
                else FileOps.copy(record.original, record.result)
                completed += record
            } catch (error: FileOperationException) {
                if (record.result.exists() && record.original.exists()) FileOps.delete(record.result)
                rollbackRedoneTransfer(completed, moved)
                return FileResult.Failure(
                    FileProblem(error.failure, error.subject ?: record.original.name)
                )
            } catch (_: Exception) {
                if (record.result.exists() && record.original.exists()) FileOps.delete(record.result)
                rollbackRedoneTransfer(completed, moved)
                return FileResult.Failure(
                    FileProblem(if (moved) FileFailure.MOVE_FAILED else FileFailure.COPY_FAILED, record.original.name)
                )
            }
        }
        return FileResult.Success(Unit)
    }

    private fun rollbackRedoneTransfer(records: List<TransferRecord>, moved: Boolean) {
        records.asReversed().forEach { record ->
            try {
                if (moved && record.result.exists() && !record.original.exists()) {
                    FileOps.move(record.result, record.original)
                } else if (!moved && record.result.exists()) {
                    FileOps.delete(record.result)
                }
            } catch (_: Exception) {
                // Preserve the original redo failure; this is a best-effort transaction rollback.
            }
        }
    }

    fun restorePath(current: File, original: File): FileResult<Unit> {
        if (!current.exists()) return FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, current.name))
        if (original.exists()) return FileResult.Failure(FileProblem(FileFailure.NAME_EXISTS, original.name))
        return try {
            FileOps.move(current, original)
            FileResult.Success(Unit)
        } catch (error: FileOperationException) {
            FileResult.Failure(FileProblem(error.failure, error.subject ?: current.name))
        } catch (_: Exception) {
            FileResult.Failure(FileProblem(FileFailure.MOVE_FAILED, current.name))
        }
    }

    fun deleteToTrash(files: List<File>, trashRoot: File): FileResult<List<TrashRecord>> {
        if (!trashRoot.exists() && !trashRoot.mkdirs()) {
            return FileResult.Failure(FileProblem(FileFailure.CREATE_DIRECTORY, trashRoot.name))
        }
        val batch = File(trashRoot, UUID.randomUUID().toString())
        if (!batch.mkdir()) return FileResult.Failure(FileProblem(FileFailure.CREATE_DIRECTORY, batch.name))
        val records = mutableListOf<TrashRecord>()
        try {
            files.forEach { file ->
                if (!file.exists()) return@forEach
                val trashed = FileOps.availableTarget(batch, file.name)
                FileOps.move(file, trashed)
                records += TrashRecord(file, trashed)
            }
        } catch (error: Exception) {
            records.asReversed().forEach { record ->
                try {
                    if (record.trashed.exists() && !record.original.exists()) {
                        FileOps.move(record.trashed, record.original)
                    }
                } catch (_: Exception) {
                    // Best-effort rollback; the original failure is reported below.
                }
            }
            FileOps.delete(batch)
            val problem = if (error is FileOperationException) {
                FileProblem(error.failure, error.subject)
            } else {
                FileProblem(FileFailure.DELETE_FAILED, files.firstOrNull()?.name)
            }
            return FileResult.Failure(problem)
        }
        if (records.isEmpty()) FileOps.delete(batch)
        return FileResult.Success(records)
    }

    fun restoreTrash(records: List<TrashRecord>): FileResult<Unit> {
        records.forEach { record ->
            if (!record.trashed.exists()) {
                return FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, record.trashed.name))
            }
            if (record.original.exists()) {
                return FileResult.Failure(FileProblem(FileFailure.NAME_EXISTS, record.original.name))
            }
        }
        val completed = mutableListOf<TrashRecord>()
        records.forEach { record ->
            try {
                FileOps.move(record.trashed, record.original)
                completed += record
            } catch (error: FileOperationException) {
                rollbackRestoredTrash(completed)
                return FileResult.Failure(FileProblem(error.failure, error.subject ?: record.original.name))
            } catch (_: Exception) {
                rollbackRestoredTrash(completed)
                return FileResult.Failure(FileProblem(FileFailure.MOVE_FAILED, record.original.name))
            }
        }
        removeTrashBatch(records)
        return FileResult.Success(Unit)
    }

    private fun rollbackRestoredTrash(records: List<TrashRecord>) {
        records.asReversed().forEach { record ->
            try {
                if (record.original.exists() && !record.trashed.exists()) {
                    FileOps.move(record.original, record.trashed)
                }
            } catch (_: Exception) {
                // Preserve the original restore failure; this is a best-effort rollback.
            }
        }
    }

    fun cleanupTrash(trashRoot: File) {
        if (trashRoot.exists()) FileOps.delete(trashRoot)
    }

    private fun removeTrashBatch(records: List<TrashRecord>) {
        val batch = records.firstOrNull()?.trashed?.parentFile ?: return
        val root = batch.parentFile
        FileOps.delete(batch)
        if (root?.listFiles()?.isEmpty() == true) root.delete()
    }

    private fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private fun sameFile(left: File, right: File): Boolean = try {
        left.canonicalFile == right.canonicalFile
    } catch (_: Exception) {
        left.absolutePath == right.absolutePath
    }
}
