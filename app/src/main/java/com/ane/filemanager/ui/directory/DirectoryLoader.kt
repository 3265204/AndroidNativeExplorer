package com.ane.filemanager.ui.directory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Lists and sorts one directory at a time without blocking the View's UI thread. */
internal class DirectoryLoader(
    private val onLoaded: (directory: File, files: List<File>) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val requestLock = Any()
    private var request: Job? = null

    fun load(directory: File, showHidden: Boolean, sorter: (List<File>) -> List<File>) {
        if (closed.get()) return
        val requestGeneration = generation.incrementAndGet()
        synchronized(requestLock) {
            request?.cancel()
            request = scope.launch {
                runInterruptible {
                    val listed = directory.listFiles()?.filter {
                        showHidden || !it.name.startsWith('.')
                    }.orEmpty()
                    ensureActive()
                    val sorted = sorter(listed)
                    ensureActive()
                    if (!closed.get() && generation.get() == requestGeneration) {
                        onLoaded(directory, sorted)
                    }
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        synchronized(requestLock) {
            request?.cancel()
            request = null
        }
        scope.cancel()
    }
}
