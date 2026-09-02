package com.ane.filemanager.plugin.image.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.AnePluginHostSessions
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.file.AnePluginFileSequence
import com.ane.filemanager.plugin.api.file.fileQueries
import com.ane.filemanager.plugin.api.ui.AneMediaDirection
import com.ane.filemanager.plugin.api.ui.AneMediaSequenceNavigation
import com.ane.filemanager.plugin.api.ui.AneMediaSequenceStage
import com.ane.filemanager.plugin.api.ui.AnePluginUi
import com.ane.filemanager.plugin.api.ui.mediaSequenceStage
import com.ane.filemanager.plugin.api.ui.ui
import com.ane.filemanager.plugin.api.ui.applyAneSystemBars
import com.ane.filemanager.plugin.api.ui.applyAneSystemInsets
import com.ane.filemanager.plugin.image.ImagePluginFiles
import java.io.File

class ImageActivity : Activity() {
    private var bitmap: Bitmap? = null
    private lateinit var playlist: AnePluginFileSequence
    private lateinit var pluginUi: AnePluginUi
    private var pluginSessionId: String? = null
    private lateinit var sequenceStage: AneMediaSequenceStage
    private lateinit var imageView: ZoomableImageView
    private lateinit var progress: ProgressBar
    private var loadGeneration = 0
    private var switching = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pluginSessionId = intent.getStringExtra(AneIntentPluginEntry.EXTRA_HOST_SESSION_ID)
        val host = AnePluginHostSessions.resolve(pluginSessionId)
        val file = (state?.getString(STATE_PATH)
            ?: intent.getStringExtra(AneIntentPluginEntry.EXTRA_FILE_PATH))
            ?.let { path -> host?.fileQueries?.resolve(path) }
        if (host == null || file == null || !file.toFile().isFile || !ImagePluginFiles.supports(file)) {
            Toast.makeText(this, R.string.image_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = host.fileQueries.siblingSequence(file, ImagePluginFiles::supports)
        pluginUi = host.ui
        val palette = pluginUi.theme
        applyAneSystemBars(palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            applyAneSystemInsets()
        }
        sequenceStage = pluginUi.mediaSequenceStage(
            context = this,
            navigationLabel = getString(R.string.image_back_symbol),
            navigationDescription = getString(R.string.image_screen_back),
            onNavigate = ::finish,
            navigation = sequenceNavigation(),
            onMoved = ::animateImageChange
        )

        val stage = sequenceStage.stage
        imageView = ZoomableImageView(this)
        imageView.onSwipeLeft = { switchImage(1) }
        imageView.onSwipeRight = { switchImage(-1) }
        stage.addView(imageView, android.widget.FrameLayout.LayoutParams(-1, -1))
        progress = pluginUi.attachMediaProgress(this, stage)
        sequenceStage.attachTo(root)
        setContentView(root)
        sequenceStage.refresh()
        loadImage(playlist.current, null)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString(STATE_PATH, playlist.current.path)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        loadGeneration++
        bitmap?.recycle()
        bitmap = null
        if (!isChangingConfigurations) AnePluginHostSessions.release(pluginSessionId)
        super.onDestroy()
    }

    private fun switchImage(delta: Int) {
        if (switching || imageView.isZoomed) return
        sequenceStage.moveBy(delta)
    }

    private fun animateImageChange(direction: AneMediaDirection) {
        switching = true
        val stageWidth = imageView.width.coerceAtLeast(resources.displayMetrics.widthPixels)
        pluginUi.animateMediaExit(imageView, direction, stageWidth) {
            imageView.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
            loadImage(playlist.current, direction)
        }
    }

    private fun loadImage(file: PluginFile, direction: AneMediaDirection?) {
        val generation = ++loadGeneration
        progress.visibility = android.view.View.VISIBLE
        Thread {
            val loaded = try { decodeSampled(file.toFile(), 4096) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) {
                    loaded?.recycle()
                    return@runOnUiThread
                }
                progress.visibility = android.view.View.GONE
                if (loaded == null) {
                    switching = false
                    Toast.makeText(this, R.string.image_decode_failed, Toast.LENGTH_SHORT).show()
                } else {
                    bitmap = loaded
                    imageView.setImageBitmap(loaded)
                    if (direction != null) {
                        val stageWidth = imageView.width.coerceAtLeast(resources.displayMetrics.widthPixels)
                        pluginUi.animateMediaEnter(imageView, direction, stageWidth) {
                            switching = false
                        }
                    } else {
                        imageView.translationX = 0f
                        imageView.alpha = 1f
                        switching = false
                    }
                }
            }
        }.start()
    }

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } catch (_: OutOfMemoryError) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = ImageDecodePolicy.sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
        val rotation = readRotation(file)
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (sample <= maxDimension) {
            val decoded = try {
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: return null
            } catch (_: OutOfMemoryError) {
                sample = ImageDecodePolicy.nextSample(sample)
                continue
            }
            if (rotation == 0) return decoded
            try {
                return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } catch (_: OutOfMemoryError) {
                decoded.recycle()
                sample = ImageDecodePolicy.nextSample(sample)
            }
        }
        return null
    }

    private fun readRotation(file: File): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            when (android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun sequenceNavigation() = AneMediaSequenceNavigation(
        currentTitle = { playlist.current.name },
        positionLabel = { playlist.positionLabel },
        hasPrevious = { playlist.hasPrevious },
        hasNext = { playlist.hasNext },
        moveBy = { playlist.moveBy(it) != null }
    )

    internal companion object {
        const val STATE_PATH = "image_path"
    }
}

/** Bounds ARGB_8888 decoding by both edge length and total pixel memory. */
internal object ImageDecodePolicy {
    private const val MAX_DECODE_PIXELS = 10_000_000L

    fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (scaled(width, sample) > maxSide || scaled(height, sample) > maxSide ||
            scaled(width, sample).toLong() * scaled(height, sample) > MAX_DECODE_PIXELS
        ) {
            sample = nextSample(sample)
        }
        return sample
    }

    fun nextSample(sample: Int): Int =
        if (sample > Int.MAX_VALUE / 2) Int.MAX_VALUE else sample * 2

    private fun scaled(value: Int, sample: Int): Int =
        (value.toLong() + sample - 1L).div(sample).toInt()
}
