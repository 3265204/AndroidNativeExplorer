package com.ane.filemanager.ui.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/** Loads real image/video previews off the UI thread and keeps a bounded in-memory cache. */
internal class ThumbnailLoader(private val onLoaded: () -> Unit) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pending = hashSetOf<String>()
    private val executor = Executors.newFixedThreadPool(2)

    fun isImage(file: File) = file.extension.lowercase() in IMAGE_EXTENSIONS
    fun isVideo(file: File) = file.extension.lowercase() in VIDEO_EXTENSIONS
    fun isPreviewable(file: File) = isImage(file) || isVideo(file)

    fun get(file: File, targetPx: Int): Bitmap? {
        val key = "${file.absolutePath}:${file.lastModified()}:$targetPx"
        cache.get(key)?.let { return it }
        synchronized(pending) { if (!pending.add(key)) return null }
        executor.execute {
            val bitmap = try {
                if (isVideo(file)) loadVideo(file, targetPx) else loadImage(file, targetPx)
            } catch (_: Exception) {
                null
            }
            synchronized(pending) { pending.remove(key) }
            if (bitmap != null) cache.put(key, bitmap)
            onLoaded()
        }
        return null
    }

    private fun loadImage(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val shortest = max(1, minOf(bounds.outWidth, bounds.outHeight))
        while (shortest / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    @Suppress("DEPRECATION")
    private fun loadVideo(file: File, targetPx: Int): Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
        ThumbnailUtils.createVideoThumbnail(file, Size(targetPx, targetPx), null)
    } else {
        ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")
    }
}
