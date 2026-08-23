package com.ane.filemanager.viewer.video

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.ane.filemanager.R
import com.ane.filemanager.viewer.MediaFolderPlaylist
import com.ane.filemanager.viewer.MediaKind
import com.ane.filemanager.viewer.ViewerContract
import com.ane.filemanager.viewer.ViewerPalette
import com.ane.filemanager.viewer.applyViewerSystemBars
import com.ane.filemanager.viewer.applyViewerSystemInsets
import java.io.File

class VideoViewerActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var playlist: MediaFolderPlaylist
    private lateinit var titleLabel: TextView
    private lateinit var positionLabel: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private var resumePosition = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = (state?.getString(STATE_PATH) ?: intent.getStringExtra(ViewerContract.EXTRA_PATH))?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.viewer_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = MediaFolderPlaylist.create(file, MediaKind.VIDEO)
        resumePosition = state?.getInt(STATE_POSITION) ?: 0
        val palette = ViewerPalette.from(this)
        applyViewerSystemBars(palette, navigationColor = Color.BLACK, lightNavigationBars = false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
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
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.viewer_back)
            setOnClickListener { finish() }
        }
        titleLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        positionLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(4), 0)
        }
        top.addView(back, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(positionLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoView = VideoView(this)
        stage.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        previousButton = mediaSwitchButton("‹", R.string.viewer_previous).apply {
            setOnClickListener { switchVideo(-1) }
        }
        nextButton = mediaSwitchButton("›", R.string.viewer_next).apply {
            setOnClickListener { switchVideo(1) }
        }
        stage.addView(previousButton, FrameLayout.LayoutParams(dp(54), dp(68),
            Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12) })
        stage.addView(nextButton, FrameLayout.LayoutParams(dp(54), dp(68),
            Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(12) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val controls = MediaController(this).apply { setAnchorView(videoView) }
        videoView.setMediaController(controls)
        videoView.setOnPreparedListener { player ->
            player.setScreenOnWhilePlaying(true)
            if (resumePosition > 0) videoView.seekTo(resumePosition)
            videoView.start()
            controls.show(2500)
        }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.viewer_video_failed, Toast.LENGTH_SHORT).show()
            true
        }
        videoView.requestFocus()
        showCurrentVideo()
    }

    private fun switchVideo(delta: Int) {
        if (playlist.moveBy(delta) == null) return
        resumePosition = 0
        videoView.stopPlayback()
        showCurrentVideo()
    }

    private fun showCurrentVideo() {
        titleLabel.text = playlist.current.name
        positionLabel.text = playlist.positionLabel
        updateNavigation()
        videoView.setVideoPath(playlist.current.absolutePath)
        videoView.requestFocus()
    }

    private fun updateNavigation() {
        previousButton.isEnabled = playlist.hasPrevious
        previousButton.alpha = if (playlist.hasPrevious) 1f else .34f
        nextButton.isEnabled = playlist.hasNext
        nextButton.alpha = if (playlist.hasNext) 1f else .34f
    }

    private fun mediaSwitchButton(symbol: String, description: Int) = Button(this).apply {
        text = symbol
        textSize = 32f
        setTextColor(Color.WHITE)
        contentDescription = getString(description)
        setPadding(0, 0, 0, dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(Color.argb(150, 18, 22, 29))
        }
    }

    override fun onPause() {
        if (::videoView.isInitialized) {
            resumePosition = videoView.currentPosition
            videoView.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt(STATE_POSITION, if (::videoView.isInitialized) videoView.currentPosition else resumePosition)
        state.putString(STATE_PATH, playlist.current.absolutePath)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        if (::videoView.isInitialized) videoView.stopPlayback()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val STATE_POSITION = "video_position"
        const val STATE_PATH = "video_path"
    }
}
