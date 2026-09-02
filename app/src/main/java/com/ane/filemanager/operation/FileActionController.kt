package com.ane.filemanager.operation

import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates user-facing file transactions and the session-wide branching history.
 * Rendering, selection gestures and Dock navigation stay outside this class.
 */
internal class FileActionController(
    private val host: MainActivity,
    rootDirectory: File,
    private val currentDirectory: () -> File,
    private val selectedFiles: () -> List<File>,
    private val replaceSelection: (File?) -> Unit,
    private val exitMultiSelect: () -> Unit,
    private val setBusy: (String?) -> Unit,
    private val refresh: () -> Unit
) {
    private val files = FileOperationService()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, FILE_OPERATION_THREAD_NAME)
    }
    private val closed = AtomicBoolean(false)
    private val history = FileHistoryController()
    private val trashDirectory = File(rootDirectory, ".ane-filemanager-trash")
    private var clipboard = listOf<File>()

    private var clipboardCut: Boolean = false

    val hasClipboard get() = clipboard.isNotEmpty()
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    init {
        execute { files.cleanupTrash(trashDirectory) }
    }

    fun copySelection(cut: Boolean) {
        clipboard = selectedFiles()
        clipboardCut = cut
        host.toast(s(R.string.clipboard_set, s(if (cut) R.string.verb_cut else R.string.verb_copy), clipboard.size))
        exitMultiSelect()
    }

    fun paste() {
        val sources = clipboard.filter(File::exists)
        if (sources.isEmpty()) {
            clipboard = emptyList()
            host.toast(s(R.string.clipboard_missing))
            return
        }
        val moving = clipboardCut
        performTransfer(
            s(if (moving) R.string.status_moving else R.string.status_copying),
            sources,
            currentDirectory(),
            moving
        ) { batch ->
            if (moving && batch.skipped == 0) clipboard = emptyList()
        }
    }

    fun create(folder: Boolean) {
        val initial = s(if (folder) R.string.default_new_folder else R.string.default_new_file)
        host.promptName(s(if (folder) R.string.action_new_folder else R.string.dialog_new_empty_file), initial) { name ->
            when (val result = files.create(currentDirectory(), name, folder)) {
                is FileResult.Success -> {
                    val created = result.value
                    recordUndo(createdOutputAction(
                        created,
                        s(if (folder) R.string.action_new_folder else R.string.action_new_file)
                    ))
                    refresh()
                    replaceSelection(created)
                }
                is FileResult.Failure -> showProblem(result.problem)
            }
        }
    }

    fun rename() {
        val file = selectedFiles().singleOrNull() ?: return
        host.promptName(s(R.string.dialog_rename), file.name) { name ->
            val result = files.rename(file, name)
            if (result is FileResult.Failure && result.problem.failure == FileFailure.NAME_EXISTS) {
                host.resolveNameConflict(name,
                    onReplace = { rename(file, name, RenameConflictPolicy.REPLACE) },
                    onKeepBoth = { rename(file, name, RenameConflictPolicy.KEEP_BOTH) }
                )
            } else {
                finishRename(result)
            }
        }
    }

    fun delete() {
        val targets = selectedFiles()
        if (targets.isEmpty()) return
        host.confirm(s(R.string.dialog_delete_title, targets.size), s(R.string.dialog_delete_message)) {
            performJob(s(R.string.status_deleting), { files.deleteToTrash(targets, trashDirectory) }) { records ->
                if (records.isNotEmpty()) {
                    recordUndo(deletedFilesAction(records))
                }
                exitMultiSelect()
            }
        }
    }

    fun move(sources: List<File>, target: File) {
        if (sources.isEmpty()) return
        performTransfer(s(R.string.status_moving_count, sources.size), sources, target, moving = true) {
            exitMultiSelect()
        }
    }

    fun registerCreatedOutput(output: File) {
        recordUndo(createdOutputAction(output, output.name))
    }

    fun undoLastOperation() {
        if (!history.canUndo) return
        performJob(s(R.string.status_undoing), history::undo) {
            exitMultiSelect()
        }
    }

    fun redoLastOperation() {
        if (!history.canRedo) return
        performJob(s(R.string.status_redoing), { history.redo() }) {
            exitMultiSelect()
        }
    }

    /**
     * Stops accepting work while allowing queued file transactions to finish safely.
     * Completion callbacks are suppressed after closing because the host Activity is no longer valid.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    private fun <T> performJob(label: String, job: () -> FileResult<T>, onSuccess: (T) -> Unit = {}) {
        if (closed.get()) return
        setBusy(label)
        val accepted = execute {
            val result = job()
            if (closed.get()) return@execute
            host.runOnUiThread {
                if (closed.get()) return@runOnUiThread
                setBusy(null)
                when (result) {
                    is FileResult.Success -> {
                        onSuccess(result.value)
                        refresh()
                        host.toast(s(R.string.operation_complete))
                    }
                    is FileResult.Failure -> {
                        refresh()
                        showProblem(result.problem)
                    }
                }
            }
        }
        if (!accepted) setBusy(null)
    }

    private fun performTransfer(
        label: String,
        sources: List<File>,
        targetDirectory: File,
        moving: Boolean,
        completed: List<TransferRecord> = emptyList(),
        skipped: Int = 0,
        partialMove: TransferRecord? = null,
        onFinished: (TransferBatch) -> Unit = {}
    ) {
        if (closed.get()) return
        setBusy(label)
        val accepted = execute {
            val result = files.transfer(sources, targetDirectory, moving, completed, skipped, partialMove)
            if (closed.get()) return@execute
            host.runOnUiThread {
                if (closed.get()) return@runOnUiThread
                setBusy(null)
                refresh()
                when (result) {
                    is FileResult.Success -> finishTransfer(result.value, moving, onFinished)
                    is FileResult.Failure -> {
                        val interruption = result.transferInterruption
                        if (interruption == null) {
                            showProblem(result.problem)
                        } else {
                            val completedAfterSkip = interruption.completed + listOfNotNull(interruption.partialMove)
                            host.resolveTransferFailure(
                                problem = result.problem,
                                onRetry = {
                                    performTransfer(
                                        label,
                                        if (interruption.partialMove == null) {
                                            listOf(interruption.failed) + interruption.remaining
                                        } else {
                                            interruption.remaining
                                        },
                                        interruption.targetDirectory,
                                        interruption.moved,
                                        interruption.completed,
                                        interruption.skipped,
                                        interruption.partialMove,
                                        onFinished
                                    )
                                },
                                onSkip = {
                                    performTransfer(
                                        label,
                                        interruption.remaining,
                                        interruption.targetDirectory,
                                        interruption.moved,
                                        completedAfterSkip,
                                        interruption.skipped + 1,
                                        null,
                                        onFinished
                                    )
                                },
                                onCancel = {
                                    finishTransfer(
                                        TransferBatch(completedAfterSkip, interruption.skipped + 1),
                                        moving,
                                        onFinished,
                                        cancelled = true
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        if (!accepted) setBusy(null)
    }

    private fun finishTransfer(
        batch: TransferBatch,
        moved: Boolean,
        onFinished: (TransferBatch) -> Unit,
        cancelled: Boolean = false
    ) {
        recordTransferUndo(batch.records, moved)
        onFinished(batch)
        val message = when {
            cancelled && batch.records.isNotEmpty() -> s(R.string.operation_cancelled_with_completed)
            cancelled -> s(R.string.operation_cancelled)
            batch.skipped > 0 -> s(R.string.operation_complete_with_skipped, batch.skipped)
            else -> s(R.string.operation_complete)
        }
        host.toast(message)
    }

    private fun execute(task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute { task() }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun recordTransferUndo(records: List<TransferRecord>, moved: Boolean) {
        if (records.isNotEmpty()) {
            recordUndo(FileHistoryAction(
                label = s(if (moved) R.string.action_cut else R.string.action_copy),
                undo = { files.undoTransfer(records, moved) },
                redo = { files.redoTransfer(records, moved) }
            ))
        }
    }

    private fun rename(file: File, name: String, policy: RenameConflictPolicy) {
        performJob(
            s(R.string.status_renaming),
            { files.rename(file, name, policy, trashDirectory) }
        ) { record -> applyRename(record) }
    }

    private fun finishRename(result: FileResult<RenameRecord>) {
        when (result) {
            is FileResult.Success -> {
                applyRename(result.value)
                refresh()
            }
            is FileResult.Failure -> showProblem(result.problem)
        }
    }

    private fun applyRename(record: RenameRecord) {
        if (record.original != record.result) {
            var currentRecord = record
            recordUndo(FileHistoryAction(
                label = s(R.string.action_rename),
                undo = { files.undoRename(currentRecord) },
                redo = {
                    val policy = if (currentRecord.replaced.isEmpty()) {
                        RenameConflictPolicy.FAIL
                    } else {
                        RenameConflictPolicy.REPLACE
                    }
                    files.rename(
                        currentRecord.original,
                        currentRecord.result.name,
                        policy,
                        trashDirectory
                    ).map { repeated -> currentRecord = repeated }
                }
            ))
        }
        replaceSelection(record.result)
    }

    private fun createdOutputAction(output: File, label: String): FileHistoryAction {
        var trashRecords = emptyList<TrashRecord>()
        return FileHistoryAction(
            label = label,
            undo = {
                deleteRecordedToTrash(listOf(output)).map { records ->
                    trashRecords = records
                }
            },
            redo = { files.restoreTrash(trashRecords) }
        )
    }

    private fun deletedFilesAction(initialRecords: List<TrashRecord>): FileHistoryAction {
        var trashRecords = initialRecords
        return FileHistoryAction(
            label = s(R.string.action_delete),
            undo = { files.restoreTrash(trashRecords) },
            redo = {
                deleteRecordedToTrash(trashRecords.map(TrashRecord::original)).map { records ->
                    trashRecords = records
                }
            }
        )
    }

    private fun recordUndo(action: FileHistoryAction) {
        history.push(action)
    }

    private fun deleteRecordedToTrash(targets: List<File>): FileResult<List<TrashRecord>> {
        targets.firstOrNull { !it.exists() }?.let { missing ->
            return FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, missing.name))
        }
        return files.deleteToTrash(targets, trashDirectory)
    }

    private inline fun <T> FileResult<T>.map(onSuccess: (T) -> Unit): FileResult<Unit> = when (this) {
        is FileResult.Success -> {
            onSuccess(value)
            FileResult.Success(Unit)
        }
        is FileResult.Failure -> this
    }

    private fun showProblem(problem: FileProblem) = host.toast(host.fileProblemMessage(problem))
    private fun s(resId: Int, vararg args: Any): String = host.getString(resId, *args)

    private companion object {
        const val FILE_OPERATION_THREAD_NAME = "ane-file-operation"
    }
}
