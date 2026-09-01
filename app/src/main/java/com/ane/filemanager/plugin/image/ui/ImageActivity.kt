package com.ane.filemanager.plugin.image.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.plugin.api.ui.AneMediaDirection
import com.ane.filemanager.plugin.image.ImageSequence
import com.ane.filemanager.ui.HostUi
import java.io.File

class ImageActivity : Activity() {
    private var bitmap: Bitmap? = null
    private lateinit var playlist: ImageSequence
    private lateinit var imageView: ZoomableImageView
    private lateinit var progress: ProgressBar
    private lateinit var titleLabel: TextView
    private lateinit var position: TextView
    private var loadGeneration = 0
    private var switching = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapSystem(base))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = (state?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_FILE_PATH))?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.image_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = ImageSequence.create(file, ::accepts)
        val palette = HostUi.theme(this)
        applyImageSystemBars(palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            applyImageSystemInsets()
        }
        val top = HostUi.sequenceTopBar(
            context = this,
            theme = palette,
            navigationLabel = getString(R.string.image_back_symbol),
            navigationDescription = getString(R.string.image_screen_back),
            onNavigate = ::finish
        )
        titleLabel = top.title
        position = top.position

        val stage = HostUi.mediaStage(this)
        imageView = ZoomableImageView(this)
        imageView.onSwipeLeft = { switchImage(1) }
        imageView.onSwipeRight = { switchImage(-1) }
        stage.addView(imageView, android.widget.FrameLayout.LayoutParams(-1, -1))
        progress = HostUi.attachMediaProgress(this, stage)
        top.attachTo(root)
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadImage(playlist.current, null)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString(STATE_PATH, playlist.current.absolutePath)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        loadGeneration++
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    private fun switchImage(delta: Int) {
        if (switching || imageView.isZoomed) return
        val file = playlist.moveBy(delta) ?: return
        switching = true
        val stageWidth = imageView.width.coerceAtLeast(resources.displayMetrics.widthPixels)
        val direction = if (delta > 0) AneMediaDirection.NEXT else AneMediaDirection.PREVIOUS
        HostUi.animateMediaExit(imageView, direction, stageWidth) {
            imageView.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
            loadImage(file, direction)
        }
    }

    private fun loadImage(file: File, direction: AneMediaDirection?) {
        val generation = ++loadGeneration
        titleLabel.text = file.name
        position.text = playlist.positionLabel
        progress.visibility = android.view.View.VISIBLE
        Thread {
            val loaded = try { decodeSampled(file, 4096) } catch (_: Exception) { null }
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
                        HostUi.animateMediaEnter(imageView, direction, stageWidth) {
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

    internal companion object {
        const val EXTRA_FILE_PATH = "image_file_path"
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        fun accepts(file: File) = file.isFile && file.extension.lowercase() in EXTENSIONS
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
