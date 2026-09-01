package com.ane.filemanager.plugin.video.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import com.ane.filemanager.R
import com.ane.filemanager.core.file.SiblingFileSequence
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.ui.AneMediaSequenceNavigation
import com.ane.filemanager.plugin.api.ui.AneMediaSequenceStage
import com.ane.filemanager.plugin.api.ui.applyAneMediaSystemBars
import com.ane.filemanager.plugin.api.ui.applyAneSystemInsets
import com.ane.filemanager.plugin.video.VideoPluginFiles
import com.ane.filemanager.ui.HostUi
import java.io.File

class VideoPlayerActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapSystem(base))
    }
    private lateinit var videoView: VideoView
    private lateinit var playlist: SiblingFileSequence
    private lateinit var sequenceStage: AneMediaSequenceStage
    private var resumePosition = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val file = (state?.getString(STATE_PATH)
            ?: intent.getStringExtra(AneIntentPluginEntry.EXTRA_FILE_PATH))?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.video_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = SiblingFileSequence.create(file, VideoPluginFiles::accepts)
        resumePosition = state?.getInt(STATE_POSITION) ?: 0
        val palette = HostUi.theme(this)
        applyAneMediaSystemBars(palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            HostUi.configureMediaRoot(this)
            applyAneSystemInsets()
        }
        sequenceStage = HostUi.mediaSequenceStage(
            context = this,
            theme = palette,
            navigationLabel = getString(R.string.video_back_symbol),
            navigationDescription = getString(R.string.video_screen_back),
            onNavigate = ::finish,
            navigation = sequenceNavigation(),
            onMoved = { switchVideo() }
        )

        val stage = sequenceStage.stage
        videoView = VideoView(this)
        stage.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        sequenceStage.attachSwitchButtons(
            getString(R.string.video_previous_symbol),
            getString(R.string.video_previous),
            getString(R.string.video_next_symbol),
            getString(R.string.video_next)
        )
        sequenceStage.attachTo(root)
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
        sequenceStage.refresh()
        showCurrentVideo()
    }

    private fun switchVideo() {
        resumePosition = 0
        videoView.stopPlayback()
        showCurrentVideo()
    }

    private fun showCurrentVideo() {
        videoView.setVideoPath(playlist.current.absolutePath)
        videoView.requestFocus()
    }

    private fun sequenceNavigation() = AneMediaSequenceNavigation(
        currentTitle = { playlist.current.name },
        positionLabel = { playlist.positionLabel },
        hasPrevious = { playlist.hasPrevious },
        hasNext = { playlist.hasNext },
        moveBy = { playlist.moveBy(it) != null }
    )

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
        const val STATE_POSITION = "video_position"
        const val STATE_PATH = "video_path"
    }
}
