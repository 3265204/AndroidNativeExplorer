package com.ane.filemanager.operation

import com.ane.filemanager.core.file.TextFileService
import com.ane.filemanager.plugin.api.file.PluginTextEncoding
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Session-wide owner of file mutation serialization, reversible history, and trash payloads.
 *
 * UI controllers and plugins share this service. The single worker intentionally serializes only
 * final filesystem mutations and their history updates. Expensive plugin computation belongs on
 * the plugin executor before it enters this queue.
 */
internal class FileTransactionService(rootDirectory: File) {
    internal val files = FileOperationService()
    internal val history = FileHistoryController()
    internal val trashDirectory = File(rootDirectory, ".ane-filemanager-trash")

    private val closed = AtomicBoolean(false)
    private val workerThread = AtomicReference<Thread?>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, FILE_OPERATION_THREAD_NAME).also(workerThread::set)
    }

    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    init {
        execute { files.cleanupTrash(trashDirectory) }
    }

    /** Queues an asynchronous mutation; callers must not put unrelated long-running work here. */
    fun execute(task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /**
     * Suspends without occupying a caller thread while the mutation runs on the serialized queue.
     * Cancellation only stops delivery of the result: an accepted filesystem transaction is
     * deliberately allowed to finish so it cannot leave a half-mutated tree behind.
     */
    suspend fun <T> await(task: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (closed.get()) {
            continuation.resumeWith(Result.failure(IOException("File transaction service is closed")))
            return@suspendCancellableCoroutine
        }
        try {
            executor.execute {
                val result = runCatching(task)
                if (continuation.isActive) continuation.resumeWith(result)
            }
        } catch (_: RejectedExecutionException) {
            continuation.resumeWith(Result.failure(IOException("File transaction service is closed")))
        }
    }

    /**
     * Runs a synchronous mutation on the shared worker without creating a second queue.
     *
     * The worker identity check is required for reentrancy: code already running via [execute]
     * may reach [call] through a nested commit. Submitting and then waiting on this same
     * single-thread executor would deadlock, so do not remove or replace the direct-call branch.
     */
    fun <T> call(task: () -> T): T {
        if (Thread.currentThread() === workerThread.get()) return task()
        if (closed.get()) throw IOException("File transaction service is closed")
        val future = try {
            executor.submit<T> { task() }
        } catch (_: RejectedExecutionException) {
            throw IOException("File transaction service is closed")
        }
        return try {
            future.get()
        } catch (error: ExecutionException) {
            val cause = error.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IOException(cause?.message ?: "File transaction failed", cause)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for a file transaction", error)
        }
    }

    fun writeText(file: File, text: String, encoding: PluginTextEncoding, label: String) {
        call {
            if (!file.isFile) throw IOException("Text source is missing: ${file.name}")
            val before = file.readBytes()
            val after = TextFileService.encode(text, encoding)
            TextFileService.writeBytes(file, after)
            history.push(FileHistoryAction(
                label = label,
                undo = { restoreBytes(file, before) },
                redo = { restoreBytes(file, after) }
            ))
        }
    }

    /** Records an output created by a plugin as one reversible history node. */
    fun registerCreatedOutput(output: File, label: String = output.name) {
        if (!output.exists()) return
        var trashRecords = emptyList<TrashRecord>()
        history.push(FileHistoryAction(
            label = label,
            undo = {
                if (!output.exists()) {
                    FileResult.Failure(FileProblem(FileFailure.SOURCE_MISSING, output.name))
                } else {
                    files.deleteToTrash(listOf(output), trashDirectory).mapValue { records ->
                        trashRecords = records
                    }
                }
            },
            redo = { files.restoreTrash(trashRecords) }
        ))
    }

    fun commitStagedOutput(staging: File, parent: File, suggestedName: String): File = call {
        if (!staging.exists()) throw IOException("Plugin output was not created")
        if (!parent.isDirectory) throw IOException("Output parent is unavailable")
        val target = FileOps.availableTarget(parent, suggestedName)
        try {
            FileOps.move(staging, target)
        } catch (error: Exception) {
            if (target.exists()) FileOps.delete(target)
            throw IOException("Could not commit plugin output", error)
        }
        registerCreatedOutput(target)
        target
    }

    fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    private fun restoreBytes(file: File, bytes: ByteArray): FileResult<Unit> = try {
        TextFileService.writeBytes(file, bytes)
        FileResult.Success(Unit)
    } catch (_: Exception) {
        FileResult.Failure(FileProblem(FileFailure.WRITE_FAILED, file.name))
    }

    private inline fun <T> FileResult<T>.mapValue(onSuccess: (T) -> Unit): FileResult<Unit> =
        when (this) {
            is FileResult.Success -> {
                onSuccess(value)
                FileResult.Success(Unit)
            }
            is FileResult.Failure -> this
        }

    private companion object {
        const val FILE_OPERATION_THREAD_NAME = "ane-file-operation"
    }
}
