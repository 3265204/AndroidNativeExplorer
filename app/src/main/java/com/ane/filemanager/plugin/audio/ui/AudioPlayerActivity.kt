package com.ane.filemanager.plugin.audio.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.AnePluginHostSessions
import com.ane.filemanager.plugin.api.file.AnePluginFileSequence
import com.ane.filemanager.plugin.api.file.fileQueries
import com.ane.filemanager.plugin.api.ui.AneMediaSequenceNavigation
import com.ane.filemanager.plugin.api.ui.AneMediaSequenceStage
import com.ane.filemanager.plugin.api.ui.AneMediaStageStyle
import com.ane.filemanager.plugin.api.ui.AneTextRole
import com.ane.filemanager.plugin.api.ui.AneTextTone
import com.ane.filemanager.plugin.api.ui.AnePluginUi
import com.ane.filemanager.plugin.api.ui.mediaSequenceStage
import com.ane.filemanager.plugin.api.ui.ui
import com.ane.filemanager.plugin.api.ui.applyAneSystemBars
import com.ane.filemanager.plugin.api.ui.applyAneSystemInsets
import com.ane.filemanager.plugin.audio.AudioPluginFiles
import java.io.File
import java.util.Locale

class AudioPlayerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var artwork: Bitmap? = null
    private lateinit var playlist: AnePluginFileSequence
    private lateinit var pluginUi: AnePluginUi
    private var pluginSessionId: String? = null
    private lateinit var sequenceStage: AneMediaSequenceStage
    private lateinit var albumView: ImageView
    private lateinit var noteView: TextView
    private lateinit var playButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var durationLabel: TextView
    private var draggingSeek = false
    private var resumePosition = 0
    private var artworkGeneration = 0
    private val progressUpdate = object : Runnable {
        override fun run() {
            val current = player
            if (current != null) {
                try {
                    if (!draggingSeek) {
                        val position = current.currentPosition.coerceAtLeast(0)
                        seekBar.progress = position
                        elapsed.text = formatTime(position)
                    }
                } catch (_: IllegalStateException) { }
            }
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pluginSessionId = intent.getStringExtra(AneIntentPluginEntry.EXTRA_HOST_SESSION_ID)
        val host = AnePluginHostSessions.resolve(pluginSessionId)
        val file = (state?.getString(STATE_PATH)
            ?: intent.getStringExtra(AneIntentPluginEntry.EXTRA_FILE_PATH))
            ?.let { path -> host?.fileQueries?.resolve(path) }
        if (host == null || file == null || !file.toFile().isFile || !AudioPluginFiles.supports(file)) {
            Toast.makeText(this, R.string.audio_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = host.fileQueries.siblingSequence(file, AudioPluginFiles::supports)
        resumePosition = state?.getInt(STATE_POSITION) ?: 0
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
            navigationLabel = getString(R.string.audio_back_symbol),
            navigationDescription = getString(R.string.audio_screen_back),
            onNavigate = ::finish,
            navigation = sequenceNavigation(),
            stageStyle = AneMediaStageStyle.CONTENT,
            onMoved = { switchAudio() }
        )

        val stage = sequenceStage.stage
        val artworkViews = pluginUi.attachMediaArtwork(
            this,
            stage,
            getString(R.string.audio_note_symbol)
        )
        albumView = artworkViews.image
        noteView = artworkViews.placeholder

        val controls = LinearLayout(this).apply {
            pluginUi.configureMediaControls(this)
        }
        val times = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        elapsed = pluginUi.text(
            this,
            getString(R.string.audio_zero_time),
            AneTextRole.CAPTION,
            AneTextTone.MUTED
        )
        durationLabel = pluginUi.text(
            this,
            getString(R.string.audio_zero_time),
            AneTextRole.CAPTION,
            AneTextTone.MUTED
        ).apply { gravity = Gravity.END }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(durationLabel, LinearLayout.LayoutParams(0, -2, 1f))
        seekBar = SeekBar(this).apply {
            max = 1
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { draggingSeek = true }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = formatTime(value)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    try { player?.seekTo(bar.progress) } catch (_: IllegalStateException) { }
                    draggingSeek = false
                }
            })
        }
        val playback = pluginUi.mediaPlaybackControls(
            context = this,
            previousSymbol = getString(R.string.audio_previous_symbol),
            previousDescription = getString(R.string.audio_previous),
            playSymbol = getString(R.string.audio_play_symbol),
            playDescription = getString(R.string.audio_play),
            nextSymbol = getString(R.string.audio_next_symbol),
            nextDescription = getString(R.string.audio_next),
            onPrevious = { sequenceStage.moveBy(-1) },
            onPlay = ::togglePlayback,
            onNext = { sequenceStage.moveBy(1) }
        )
        previousButton = playback.previous
        playButton = playback.play
        nextButton = playback.next
        sequenceStage.bindNavigationButtons(previousButton, nextButton)
        controls.addView(times, LinearLayout.LayoutParams(-1, -2))
        controls.addView(seekBar, LinearLayout.LayoutParams(-1, -2))
        controls.addView(playback.view, LinearLayout.LayoutParams(-1, -2))
        sequenceStage.attachTo(root)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        sequenceStage.refresh()
        showCurrentAudio()
        handler.post(progressUpdate)
    }

    private fun switchAudio() {
        resumePosition = 0
        showCurrentAudio()
    }

    private fun showCurrentAudio() {
        player?.release()
        player = null
        artworkGeneration++
        artwork?.recycle()
        artwork = null
        albumView.setImageDrawable(null)
        albumView.visibility = View.GONE
        noteView.visibility = View.VISIBLE
        seekBar.progress = 0
        seekBar.max = 1
        seekBar.isEnabled = false
        elapsed.setText(R.string.audio_zero_time)
        durationLabel.setText(R.string.audio_zero_time)
        playButton.isEnabled = false
        updatePlayButton(false)
        loadArtwork(playlist.current.toFile())
        preparePlayer(playlist.current.toFile())
    }

    private fun sequenceNavigation() = AneMediaSequenceNavigation(
        currentTitle = { playlist.current.name },
        positionLabel = { playlist.positionLabel },
        hasPrevious = { playlist.hasPrevious },
        hasNext = { playlist.hasNext },
        moveBy = { playlist.moveBy(it) != null }
    )

    private fun preparePlayer(file: File) {
        player = try { MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build())
            setDataSource(file.absolutePath)
            setOnPreparedListener { ready ->
                if (ready !== player) return@setOnPreparedListener
                seekBar.max = ready.duration.coerceAtLeast(1)
                seekBar.isEnabled = true
                durationLabel.text = formatTime(ready.duration)
                if (resumePosition > 0) ready.seekTo(resumePosition.coerceAtMost(ready.duration))
                playButton.isEnabled = true
                ready.start()
                updatePlayButton(true)
            }
            setOnCompletionListener {
                seekBar.progress = seekBar.max
                updatePlayButton(false)
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@AudioPlayerActivity, R.string.audio_decode_failed, Toast.LENGTH_SHORT).show()
                updatePlayButton(false)
                true
            }
            prepareAsync()
        } } catch (_: Exception) {
            player?.release()
            Toast.makeText(this, R.string.audio_decode_failed, Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun togglePlayback() {
        val current = player ?: return
        try {
            if (current.isPlaying) {
                current.pause()
                updatePlayButton(false)
            } else {
                if (current.currentPosition >= current.duration) current.seekTo(0)
                current.start()
                updatePlayButton(true)
            }
        } catch (_: IllegalStateException) { }
    }

    private fun updatePlayButton(playing: Boolean) {
        playButton.setText(if (playing) R.string.audio_pause_symbol else R.string.audio_play_symbol)
        playButton.contentDescription = getString(if (playing) R.string.audio_pause else R.string.audio_play)
    }

    private fun loadArtwork(file: File) {
        val generation = artworkGeneration
        Thread {
            val loaded = try {
                val bytes = MediaMetadataRetriever().run {
                    setDataSource(file.absolutePath)
                    val picture = embeddedPicture
                    release()
                    picture
                }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != artworkGeneration) {
                    loaded?.recycle()
                } else if (loaded != null) {
                    artwork = loaded
                    albumView.setImageBitmap(loaded)
                    albumView.visibility = View.VISIBLE
                    noteView.visibility = View.GONE
                }
            }
        }.start()
    }

    override fun onPause() {
        val current = player
        if (current != null) try {
            resumePosition = current.currentPosition
            if (current.isPlaying) {
                current.pause()
                updatePlayButton(false)
            }
        } catch (_: IllegalStateException) { }
        super.onPause()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt(STATE_POSITION, try { player?.currentPosition ?: resumePosition }
            catch (_: IllegalStateException) { resumePosition })
        state.putString(STATE_PATH, playlist.current.path)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdate)
        artworkGeneration++
        player?.release()
        player = null
        artwork?.recycle()
        artwork = null
        if (!isChangingConfigurations) AnePluginHostSessions.release(pluginSessionId)
        super.onDestroy()
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    internal companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        const val STATE_POSITION = "audio_position"
        const val STATE_PATH = "audio_path"
    }
}
