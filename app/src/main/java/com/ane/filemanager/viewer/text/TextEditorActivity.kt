package com.ane.filemanager.viewer.text

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.viewer.ViewerContract
import com.ane.filemanager.viewer.ViewerPalette
import com.ane.filemanager.viewer.applyViewerSystemBars
import com.ane.filemanager.viewer.applyViewerSystemInsets
import java.io.File

class TextEditorActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var file: File
    private lateinit var palette: ViewerPalette
    private lateinit var editor: InertialEditText
    private lateinit var title: TextView
    private lateinit var saveButton: Button
    private var encoding = TextEncoding.UTF8
    private var dirty = false
    private var revision = 0
    private var loaded = false
    private var applyingHighlight = false
    private var highlightJobToken = 0
    private val highlightRunnable = Runnable {
        val (start, end) = visibleTextRange()
        val source = editor.text.subSequence(start, end).toString()
        val sourceRevision = revision
        val token = ++highlightJobToken
        Thread {
            val ranges = CodeHighlighter.compute(source, file.extension, palette.dark, start)
            runOnUiThread {
                if (isFinishing || isDestroyed || token != highlightJobToken || revision != sourceRevision) {
                    return@runOnUiThread
                }
                applyingHighlight = true
                try {
                    CodeHighlighter.apply(editor.text, ranges)
                } finally {
                    applyingHighlight = false
                }
            }
        }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = intent.getStringExtra(ViewerContract.EXTRA_PATH)?.let(::File) ?: File("")
        if (!file.isFile) {
            Toast.makeText(this, R.string.viewer_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        palette = ViewerPalette.from(this)
        applyViewerSystemBars(palette)
        buildUi()
        loadFile()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            applyViewerSystemInsets()
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(palette.surface)
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(palette.text)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = getString(R.string.viewer_back)
            setOnClickListener { requestClose() }
        }
        title = TextView(this).apply {
            text = file.name
            textSize = 16f
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        saveButton = Button(this).apply {
            setText(R.string.editor_save)
            setTextColor(palette.primary)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isEnabled = false
            setOnClickListener { saveFile() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -1))

        editor = InertialEditText(this).apply {
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setBackgroundColor(palette.background)
            typeface = Typeface.MONOSPACE
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(14), dp(16), dp(24))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(CodeHighlighter.prefersNoWrap(file.extension))
            isHorizontalScrollBarEnabled = true
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, _, _, _ ->
                if (!loaded || applyingHighlight) return@setOnScrollChangeListener
                handler.removeCallbacks(highlightRunnable)
                highlightJobToken++
                handler.postDelayed(highlightRunnable, SCROLL_HIGHLIGHT_DEBOUNCE_MS)
            }
        }
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadFile() {
        Thread {
            val result = runCatching { TextFileCodec.load(file) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    encoding = it.encoding
                    editor.setText(it.text)
                    editor.setSelection(0)
                    loaded = true
                    installWatcher()
                    updateHeader()
                    handler.post(highlightRunnable)
                }.onFailure {
                    val message = if (it.message == "file_too_large") R.string.editor_file_too_large
                    else R.string.editor_load_failed
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun installWatcher() {
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (!loaded || applyingHighlight) return
                if (!dirty) {
                    dirty = true
                    updateHeader()
                }
                revision++
                handler.removeCallbacks(highlightRunnable)
                highlightJobToken++
                val delay = if (file.extension.lowercase() in SLOW_HIGHLIGHT_EXTENSIONS) {
                    SLOW_HIGHLIGHT_DEBOUNCE_MS
                } else {
                    EDIT_HIGHLIGHT_DEBOUNCE_MS
                }
                handler.postDelayed(highlightRunnable, delay)
            }
        })
    }

    private fun saveFile() {
        if (!dirty) return
        val text = editor.text.toString()
        val savingRevision = revision
        saveButton.isEnabled = false
        Thread {
            val result = runCatching { TextFileCodec.save(file, text, encoding) }
            runOnUiThread {
                result.onSuccess {
                    if (revision == savingRevision) dirty = false
                    Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_LONG).show()
                }
                updateHeader()
            }
        }.start()
    }

    private fun updateHeader() {
        title.text = if (dirty) getString(R.string.editor_dirty_title, file.name) else file.name
        saveButton.isEnabled = dirty
    }

    private fun visibleTextRange(): Pair<Int, Int> {
        val length = editor.text.length
        if (length == 0) return 0 to 0
        val layout = editor.layout ?: return 0 to minOf(length, 20_000)
        val firstVisible = layout.getLineForVertical(editor.scrollY.coerceAtLeast(0))
        val lastVisible = layout.getLineForVertical((editor.scrollY + editor.height).coerceAtLeast(0))
        val firstLine = (firstVisible - 24).coerceAtLeast(0)
        val lastLine = (lastVisible + 24).coerceAtMost(layout.lineCount - 1)
        val start = (layout.getLineStart(firstLine) - 1024).coerceAtLeast(0)
        val end = (layout.getLineEnd(lastLine) + 1024).coerceAtMost(length)
        return start to end
    }

    private fun requestClose() {
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.editor_discard_confirm) { _, _ -> finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = requestClose()

    override fun onDestroy() {
        handler.removeCallbacks(highlightRunnable)
        highlightJobToken++
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val SCROLL_HIGHLIGHT_DEBOUNCE_MS = 140L
        const val EDIT_HIGHLIGHT_DEBOUNCE_MS = 420L
        const val SLOW_HIGHLIGHT_DEBOUNCE_MS = 650L
        val SLOW_HIGHLIGHT_EXTENSIONS = setOf("xml", "html", "htm", "vue")
    }
}
