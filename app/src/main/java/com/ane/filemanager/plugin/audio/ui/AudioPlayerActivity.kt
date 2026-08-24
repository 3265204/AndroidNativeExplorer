package com.ane.filemanager.plugin.audio.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.plugin.audio.AudioPlaylist
import java.io.File
import java.util.Locale
import kotlin.math.min

class AudioPlayerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapSystem(base))
    }
    private var player: MediaPlayer? = null
    private var artwork: Bitmap? = null
    private lateinit var playlist: AudioPlaylist
    private lateinit var titleLabel: TextView
    private lateinit var positionLabel: TextView
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
        val file = (state?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_FILE_PATH))?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.audio_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = AudioPlaylist.create(file, ::accepts)
        resumePosition = state?.getInt(STATE_POSITION) ?: 0
        val palette = AudioPalette.from(this)
        applyAudioSystemBars(palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            applyAudioSystemInsets()
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            setBackgroundColor(palette.surface)
        }
        val back = Button(this).apply {
            text = getString(R.string.audio_back_symbol)
            textSize = 28f
            setTextColor(palette.text)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.audio_screen_back)
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
        top.addView(back, LinearLayout.LayoutParams(dp(52), -1))
        top.addView(titleLabel, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(positionLabel, LinearLayout.LayoutParams(-2, -1))

        val stage = FrameLayout(this)
        val artSize = min((resources.displayMetrics.widthPixels * .54f).toInt(), dp(270))
        albumView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(palette.surface)
            }
            visibility = View.GONE
        }
        noteView = TextView(this).apply {
            text = getString(R.string.audio_note_symbol)
            textSize = 96f
            gravity = Gravity.CENTER
            setTextColor(palette.primary)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(palette.surface)
            }
        }
        stage.addView(albumView, FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER))
        stage.addView(noteView, FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(20))
        }
        val times = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        elapsed = TextView(this).apply { setText(R.string.audio_zero_time); setTextColor(palette.muted); textSize = 12f }
        durationLabel = TextView(this).apply { setText(R.string.audio_zero_time); setTextColor(palette.muted); textSize = 12f; gravity = Gravity.END }
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
        playButton = Button(this).apply {
            text = getString(R.string.audio_play_symbol)
            textSize = 23f
            setTextColor(Color.WHITE)
            isEnabled = false
            contentDescription = getString(R.string.audio_play)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.primary)
            }
            setOnClickListener { togglePlayback() }
        }
        previousButton = audioSwitchButton(getString(R.string.audio_previous_symbol), R.string.audio_previous, palette).apply {
            setOnClickListener { switchAudio(-1) }
        }
        nextButton = audioSwitchButton(getString(R.string.audio_next_symbol), R.string.audio_next, palette).apply {
            setOnClickListener { switchAudio(1) }
        }
        val playbackRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(previousButton, LinearLayout.LayoutParams(dp(72), dp(64)))
            addView(playButton, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                leftMargin = dp(18); rightMargin = dp(18)
            })
            addView(nextButton, LinearLayout.LayoutParams(dp(72), dp(64)))
        }
        controls.addView(times, LinearLayout.LayoutParams(-1, -2))
        controls.addView(seekBar, LinearLayout.LayoutParams(-1, -2))
        controls.addView(playbackRow, LinearLayout.LayoutParams(-1, dp(68)).apply { topMargin = dp(4) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        showCurrentAudio()
        handler.post(progressUpdate)
    }

    private fun switchAudio(delta: Int) {
        if (playlist.moveBy(delta) == null) return
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
        titleLabel.text = playlist.current.name
        positionLabel.text = playlist.positionLabel
        updateNavigation()
        loadArtwork(playlist.current)
        preparePlayer(playlist.current)
    }

    private fun updateNavigation() {
        previousButton.isEnabled = playlist.hasPrevious
        previousButton.alpha = if (playlist.hasPrevious) 1f else .34f
        nextButton.isEnabled = playlist.hasNext
        nextButton.alpha = if (playlist.hasNext) 1f else .34f
    }

    private fun audioSwitchButton(symbol: String, description: Int, palette: AudioPalette) = Button(this).apply {
        text = symbol
        textSize = 34f
        setTextColor(palette.text)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = getString(description)
        setPadding(0, 0, 0, dp(3))
    }

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
        state.putString(STATE_PATH, playlist.current.absolutePath)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdate)
        artworkGeneration++
        player?.release()
        player = null
        artwork?.recycle()
        artwork = null
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    internal companion object {
        const val EXTRA_FILE_PATH = "audio_file_path"
        val EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mid", "midi")
        fun accepts(file: File) = file.isFile && file.extension.lowercase() in EXTENSIONS
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        const val STATE_POSITION = "audio_position"
        const val STATE_PATH = "audio_path"
    }
}
