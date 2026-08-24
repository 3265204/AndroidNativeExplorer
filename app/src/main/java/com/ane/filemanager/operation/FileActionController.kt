package com.ane.filemanager.operation

import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates user-facing file transactions and the session-wide undo history.
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
    private val undo = FileUndoController()
    private val trashDirectory = File(rootDirectory, ".ane-filemanager-trash")
    private var clipboard = listOf<File>()

    private var clipboardCut: Boolean = false

    val hasClipboard get() = clipboard.isNotEmpty()
    val canUndo get() = undo.canUndo

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
                    recordUndo(PendingUndo(run = { files.delete(listOf(created)) }))
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
                    recordUndo(PendingUndo(
                        run = { files.restoreTrash(records) }
                    ))
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
        recordUndo(PendingUndo(run = { files.delete(listOf(output)) }))
    }

    fun undoLastOperation() {
        val action = undo.current() ?: return
        performJob(s(R.string.status_undoing), action.run) {
            undo.consume(action)
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
        if (records.isNotEmpty()) recordUndo(PendingUndo(run = { files.undoTransfer(records, moved) }))
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
            recordUndo(PendingUndo(run = { files.undoRename(record) }))
        }
        replaceSelection(record.result)
    }

    private fun recordUndo(action: PendingUndo) {
        undo.push(action)
    }

    private fun showProblem(problem: FileProblem) = host.toast(host.fileProblemMessage(problem))
    private fun s(resId: Int, vararg args: Any): String = host.getString(resId, *args)

    private companion object {
        const val FILE_OPERATION_THREAD_NAME = "ane-file-operation"
    }
}
