package com.ane.filemanager.viewer.image

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.viewer.MediaFolderPlaylist
import com.ane.filemanager.viewer.MediaKind
import com.ane.filemanager.viewer.ViewerContract
import com.ane.filemanager.viewer.ViewerPalette
import com.ane.filemanager.viewer.applyViewerSystemBars
import com.ane.filemanager.viewer.applyViewerSystemInsets
import java.io.File

class ImageViewerActivity : Activity() {
    private var bitmap: Bitmap? = null
    private lateinit var playlist: MediaFolderPlaylist
    private lateinit var imageView: ZoomableImageView
    private lateinit var progress: ProgressBar
    private lateinit var titleLabel: TextView
    private lateinit var position: TextView
    private var loadGeneration = 0
    private var switching = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = (state?.getString(STATE_PATH) ?: intent.getStringExtra(ViewerContract.EXTRA_PATH))?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.viewer_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = MediaFolderPlaylist.create(file, MediaKind.IMAGE)
        val palette = ViewerPalette.from(this)
        applyViewerSystemBars(palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            applyViewerSystemInsets()
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            setBackgroundColor(palette.surface)
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(palette.text)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = getString(R.string.viewer_back)
            setOnClickListener { finish() }
        }
        titleLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        position = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(4), 0)
        }
        top.addView(back, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(position, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        val stage = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        imageView = ZoomableImageView(this)
        imageView.onSwipeLeft = { switchImage(1) }
        imageView.onSwipeRight = { switchImage(-1) }
        progress = ProgressBar(this)
        stage.addView(imageView, android.widget.FrameLayout.LayoutParams(-1, -1))
        stage.addView(progress, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadImage(playlist.current, 0)
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
        val exit = if (delta > 0) -stageWidth * .16f else stageWidth * .16f
        imageView.animate().translationX(exit).alpha(0f)
            .setDuration(IMAGE_EXIT_ANIMATION_DURATION_MS).withEndAction {
            imageView.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
            loadImage(file, delta)
        }.start()
    }

    private fun loadImage(file: File, direction: Int) {
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
                    Toast.makeText(this, R.string.viewer_image_failed, Toast.LENGTH_SHORT).show()
                } else {
                    bitmap = loaded
                    imageView.setImageBitmap(loaded)
                    if (direction != 0) {
                        val stageWidth = imageView.width.coerceAtLeast(resources.displayMetrics.widthPixels)
                        imageView.translationX = if (direction > 0) stageWidth * .12f else -stageWidth * .12f
                        imageView.alpha = 0f
                        imageView.animate().translationX(0f).alpha(1f)
                            .setDuration(IMAGE_ENTER_ANIMATION_DURATION_MS).withEndAction {
                            switching = false
                        }.start()
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
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val rotation = readRotation(file)
        if (rotation == 0) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true).also {
            if (it !== decoded) decoded.recycle()
        }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val IMAGE_EXIT_ANIMATION_DURATION_MS = 110L
        const val IMAGE_ENTER_ANIMATION_DURATION_MS = 150L
        const val STATE_PATH = "image_path"
    }
}
