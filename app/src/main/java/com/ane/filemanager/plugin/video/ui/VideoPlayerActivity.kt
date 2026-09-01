package com.ane.filemanager.plugin.video.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.ane.filemanager.R
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.plugin.video.VideoSequence
import com.ane.filemanager.plugin.api.ui.AneMediaDirection
import com.ane.filemanager.ui.HostUi
import java.io.File

class VideoPlayerActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapSystem(base))
    }
    private lateinit var videoView: VideoView
    private lateinit var playlist: VideoSequence
    private lateinit var titleLabel: TextView
    private lateinit var positionLabel: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private var resumePosition = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = (state?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_FILE_PATH))?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.video_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = VideoSequence.create(file, ::accepts)
        resumePosition = state?.getInt(STATE_POSITION) ?: 0
        val palette = HostUi.theme(this)
        applyVideoSystemBars(palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            HostUi.configureMediaRoot(this)
            applyVideoSystemInsets()
        }
        val top = HostUi.sequenceTopBar(
            context = this,
            theme = palette,
            navigationLabel = getString(R.string.video_back_symbol),
            navigationDescription = getString(R.string.video_screen_back),
            onNavigate = ::finish
        )
        titleLabel = top.title
        positionLabel = top.position

        val stage = HostUi.mediaStage(this)
        videoView = VideoView(this)
        stage.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        previousButton = HostUi.attachMediaSwitchButton(
            this,
            stage,
            AneMediaDirection.PREVIOUS,
            getString(R.string.video_previous_symbol),
            getString(R.string.video_previous)
        ) { switchVideo(-1) }
        nextButton = HostUi.attachMediaSwitchButton(
            this,
            stage,
            AneMediaDirection.NEXT,
            getString(R.string.video_next_symbol),
            getString(R.string.video_next)
        ) { switchVideo(1) }
        top.attachTo(root)
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
            Toast.makeText(this, R.string.video_decode_failed, Toast.LENGTH_SHORT).show()
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
        HostUi.updateMediaNavigation(previousButton, playlist.hasPrevious)
        HostUi.updateMediaNavigation(nextButton, playlist.hasNext)
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

    internal companion object {
        const val EXTRA_FILE_PATH = "video_file_path"
        val EXTENSIONS = setOf("mp4", "m4v", "3gp", "3gpp", "webm", "mkv", "m2ts", "mts", "mov", "avi")
        fun accepts(file: File) = file.isFile && file.extension.lowercase() in EXTENSIONS
        const val STATE_POSITION = "video_position"
        const val STATE_PATH = "video_path"
    }
}
