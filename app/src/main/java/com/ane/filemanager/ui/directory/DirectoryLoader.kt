package com.ane.filemanager.ui.directory

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Lists and sorts one directory at a time without blocking the View's UI thread. */
internal class DirectoryLoader(
    private val onLoaded: (directory: File, files: List<File>) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ane-directory-loader")
    }
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val requestLock = Any()
    private var request: Future<*>? = null

    fun load(directory: File, showHidden: Boolean, sorter: (List<File>) -> List<File>) {
        if (closed.get()) return
        val requestGeneration = generation.incrementAndGet()
        synchronized(requestLock) {
            request?.cancel(true)
            request = try {
                executor.submit {
                    val listed = directory.listFiles()?.filter {
                        showHidden || !it.name.startsWith('.')
                    }.orEmpty()
                    if (Thread.currentThread().isInterrupted) return@submit
                    val sorted = sorter(listed)
                    if (!closed.get() && generation.get() == requestGeneration) {
                        onLoaded(directory, sorted)
                    }
                }
            } catch (_: RejectedExecutionException) {
                null
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        synchronized(requestLock) {
            request?.cancel(true)
            request = null
        }
        executor.shutdownNow()
    }
}
