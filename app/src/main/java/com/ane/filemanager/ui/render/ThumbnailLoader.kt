package com.ane.filemanager.ui.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loads real image/video previews off the UI thread and keeps a bounded in-memory cache. */
internal class ThumbnailLoader(private val onLoaded: () -> Unit) {
    private val cache = object : LruCache<String, Bitmap>(cacheLimitKb()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pending = hashSetOf<String>()
    private val knownVersions = hashMapOf<String, Long>()
    private val failed = object : LinkedHashMap<String, Unit>(FAILED_CACHE_LIMIT + 1, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) =
            size > FAILED_CACHE_LIMIT
    }
    private val executor = ThreadPoolExecutor(
        THUMBNAIL_WORKERS,
        THUMBNAIL_WORKERS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_QUEUED_REQUESTS)
    ) { task -> Thread(task, "ane-thumbnail-loader") }
    private val closed = AtomicBoolean(false)
    private val deferred = AtomicBoolean(false)

    fun isImage(file: File) = file.extension.lowercase() in IMAGE_EXTENSIONS
    fun isVideo(file: File) = file.extension.lowercase() in VIDEO_EXTENSIONS
    fun isPreviewable(file: File) = isImage(file) || isVideo(file)

    fun get(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (closed.get()) return null
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        val version = synchronized(pending) {
            knownVersions.getOrPut(file.absolutePath, file::lastModified)
        }
        val key = "${file.absolutePath}:$version:${width}x$height"
        cache.get(key)?.let { return it }
        if (deferred.get()) return null
        synchronized(pending) {
            if (key in failed || !pending.add(key)) return null
        }
        val task = DecodeTask(key, file, width, height)
        try {
            dropOldestRequestsUntilSpace()
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            task.discard()
        }
        return null
    }

    fun setLoadingDeferred(value: Boolean) {
        val previous = deferred.getAndSet(value)
        if (value && !previous) cancelQueuedRequests()
    }

    /** Refreshes file versions once per directory result instead of stat'ing on every frame. */
    fun onDirectoryContentsChanged() {
        cancelQueuedRequests()
        synchronized(pending) {
            knownVersions.clear()
            failed.clear()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow().forEach { (it as? DecodeTask)?.discard() }
        cache.evictAll()
        synchronized(pending) {
            pending.clear()
            knownVersions.clear()
            failed.clear()
        }
    }

    private fun dropOldestRequestsUntilSpace() {
        while (executor.queue.remainingCapacity() == 0) {
            val dropped = executor.queue.poll() as? DecodeTask ?: break
            dropped.discard()
        }
    }

    private fun cancelQueuedRequests() {
        while (true) {
            val task = executor.queue.poll() as? DecodeTask ?: break
            task.discard()
        }
    }

    private inner class DecodeTask(
        private val key: String,
        private val file: File,
        private val width: Int,
        private val height: Int
    ) : Runnable {
        private val discarded = AtomicBoolean(false)

        override fun run() {
            if (discarded.get() || closed.get()) return
            val bitmap = try {
                if (isVideo(file)) loadVideo(file, width, height) else loadImage(file, width, height)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (bitmap != null && !closed.get()) cache.put(key, bitmap)
            synchronized(pending) {
                pending.remove(key)
                if (bitmap == null) failed[key] = Unit else failed.remove(key)
            }
            if (bitmap != null && !closed.get()) onLoaded()
        }

        fun discard() {
            if (discarded.compareAndSet(false, true)) {
                synchronized(pending) { pending.remove(key) }
            }
        }
    }

    private fun loadImage(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = ThumbnailSamplePolicy.sampleFor(
            bounds.outWidth, bounds.outHeight, targetWidth, targetHeight
        )
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        return try {
            ThumbnailUtils.extractThumbnail(
                decoded,
                targetWidth,
                targetHeight,
                ThumbnailUtils.OPTIONS_RECYCLE_INPUT
            )
        } catch (error: RuntimeException) {
            decoded.recycle()
            throw error
        } catch (error: OutOfMemoryError) {
            decoded.recycle()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun loadVideo(file: File, targetWidth: Int, targetHeight: Int): Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
        ThumbnailUtils.createVideoThumbnail(file, Size(targetWidth, targetHeight), null)
    } else {
        ThumbnailUtils.createVideoThumbnail(
            file.absolutePath,
            android.provider.MediaStore.Video.Thumbnails.MINI_KIND
        )?.let { source ->
            ThumbnailUtils.extractThumbnail(
                source,
                targetWidth,
                targetHeight,
                ThumbnailUtils.OPTIONS_RECYCLE_INPUT
            )
        }
    }

    companion object {
        private const val THUMBNAIL_WORKERS = 1
        private const val MAX_QUEUED_REQUESTS = 12
        private const val FAILED_CACHE_LIMIT = 256
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")

        private fun cacheLimitKb(): Int {
            val heapFractionKb = (Runtime.getRuntime().maxMemory() / 1024L / 16L).toInt()
            return heapFractionKb.coerceIn(4 * 1024, 24 * 1024)
        }
    }
}
