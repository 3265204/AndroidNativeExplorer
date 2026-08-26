package com.ane.filemanager.plugin.terminal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.ane.filemanager.plugin.terminal.TerminalEmulator
import kotlin.math.floor

@SuppressLint("ViewConstructor")
internal class TerminalView(
    context: Context,
    private val palette: TerminalPalette,
    initialTextSizeSp: Int
) : View(context) {
    private var writer: (ByteArray) -> Unit = {}
    private var resizeListener: (Int, Int) -> Unit = { _, _ -> }
    private val emulator = TerminalEmulator { writer(it) }
    private val paint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    ).apply {
        typeface = Typeface.create("sans-serif-monospace", Typeface.NORMAL)
    }
    private var textSizeSp = initialTextSizeSp.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
    private var cellWidth = 1f
    private var lineHeight = 1f
    private var baselineOffset = 1f
    private var scrollOffset = 0
    private var touchStartY = 0f
    private var touchStartScroll = 0
    private var composingText = ""

    val currentRows: Int
        get() = emulator.rows
    val currentColumns: Int
        get() = emulator.columns
    val currentTextSizeSp: Int
        get() = textSizeSp

    init {
        recalculateMetrics()
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(palette.surface)
    }

    fun attach(
        writer: (ByteArray) -> Unit,
        resize: (rows: Int, columns: Int) -> Unit
    ) {
        this.writer = writer
        resizeListener = resize
        updateSize(width, height)
    }

    fun feed(bytes: ByteArray) {
        emulator.feed(bytes)
        if (scrollOffset == 0) postInvalidate() else {
            val maximum = emulator.snapshot(scrollOffset).maximumScrollOffset
            scrollOffset = scrollOffset.coerceAtMost(maximum)
            postInvalidate()
        }
    }

    fun send(text: String) = writer(text.toByteArray(Charsets.UTF_8))
    fun send(bytes: ByteArray) = writer(bytes)

    fun setTextSizeSp(value: Int) {
        val next = value.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        if (next == textSizeSp) return
        textSizeSp = next
        recalculateMetrics()
        updateSize(width, height)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateSize(width, height)
    }

    private fun updateSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val columns = floor(contentWidth / cellWidth).toInt().coerceAtLeast(2)
        val rows = floor(contentHeight / lineHeight).toInt().coerceAtLeast(2)
        if (rows != emulator.rows || columns != emulator.columns) {
            emulator.resize(rows, columns)
            resizeListener(rows, columns)
            invalidate()
        }
    }

    private fun recalculateMetrics() {
        paint.textSize = sp(textSizeSp).toFloat()
        paint.textScaleX = 1f
        cellWidth = paint.measureText(CELL_WIDTH_SAMPLE).coerceAtLeast(1f)
        val metrics = paint.fontMetrics
        val glyphHeight = metrics.descent - metrics.ascent
        lineHeight = glyphHeight.coerceAtLeast(1f)
        baselineOffset = -metrics.ascent
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = emulator.snapshot(scrollOffset)
        snapshot.lines.forEachIndexed { row, line ->
            val top = paddingTop + row * lineHeight
            line.forEachIndexed { column, cell ->
                if (cell.width == 0) return@forEachIndexed
                val left = paddingLeft + column * cellWidth
                val inverse = cell.flags and TerminalEmulator.FLAG_INVERSE != 0
                var foreground = resolveColor(if (inverse) cell.background else cell.foreground)
                val background = resolveColor(if (inverse) cell.foreground else cell.background)
                if (background != palette.surface) {
                    paint.color = background
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(
                        left,
                        top,
                        left + cell.width.coerceAtLeast(1) * cellWidth,
                        top + lineHeight,
                        paint
                    )
                }
                if (cell.flags and TerminalEmulator.FLAG_HIDDEN != 0 || cell.text.isBlank()) {
                    return@forEachIndexed
                }
                if (cell.flags and TerminalEmulator.FLAG_DIM != 0) {
                    foreground = (foreground and 0x00ffffff) or (0x99000000.toInt())
                }
                paint.color = foreground
                paint.style = Paint.Style.FILL
                paint.isFakeBoldText = cell.flags and TerminalEmulator.FLAG_BOLD != 0
                paint.textSkewX = if (cell.flags and TerminalEmulator.FLAG_ITALIC != 0) -.2f else 0f
                paint.isUnderlineText = cell.flags and TerminalEmulator.FLAG_UNDERLINE != 0
                paint.isStrikeThruText = cell.flags and TerminalEmulator.FLAG_STRIKE != 0
                val occupiedWidth = cell.width.coerceAtLeast(1) * cellWidth
                val glyphLeft = left + (occupiedWidth - paint.measureText(cell.text)) / 2f
                canvas.drawText(cell.text, glyphLeft, top + baselineOffset, paint)
            }
        }
        resetPaintStyle()
        if (snapshot.cursorShown && snapshot.cursorRow in snapshot.lines.indices) {
            paint.color = palette.primary
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f).toFloat()
            canvas.drawRect(
                paddingLeft + snapshot.cursorColumn * cellWidth,
                paddingTop + snapshot.cursorRow * lineHeight,
                paddingLeft + (snapshot.cursorColumn + 1) * cellWidth,
                paddingTop + (snapshot.cursorRow + 1) * lineHeight,
                paint
            )
            if (composingText.isNotEmpty()) {
                paint.style = Paint.Style.FILL
                paint.color = palette.primary
                canvas.drawText(
                    composingText,
                    paddingLeft + snapshot.cursorColumn * cellWidth,
                    paddingTop + snapshot.cursorRow * lineHeight + baselineOffset,
                    paint
                )
            }
        }
        resetPaintStyle()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                touchStartScroll = scrollOffset
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val lines = ((event.y - touchStartY) / lineHeight).toInt()
                val maximum = emulator.snapshot(scrollOffset).maximumScrollOffset
                scrollOffset = (touchStartScroll + lines).coerceIn(0, maximum)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(event.y - touchStartY) < dp(8).toFloat()) showKeyboard()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        return true
    }

    private fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(attributes: EditorInfo): InputConnection {
        attributes.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        attributes.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        keySequence(keyCode, event)?.let {
            send(it)
            return true
        }
        val unicode = event.unicodeChar
        if (unicode != 0) {
            var text = String(Character.toChars(unicode))
            if (event.isAltPressed) text = "\u001b$text"
            send(text)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val characters = event.characters
        if (!characters.isNullOrEmpty()) {
            send(characters)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun keySequence(keyCode: Int, event: KeyEvent): String? {
        if (event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return ((keyCode - KeyEvent.KEYCODE_A + 1).toChar()).toString()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            else -> null
        }
    }

    private fun resolveColor(color: Int): Int = when (color) {
        TerminalEmulator.DEFAULT_FOREGROUND -> palette.text
        TerminalEmulator.DEFAULT_BACKGROUND -> palette.surface
        else -> color
    }

    private fun resetPaintStyle() {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1f
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.isUnderlineText = false
        paint.isStrikeThruText = false
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingText = ""
            if (!text.isNullOrEmpty()) send(text.toString())
            invalidate()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingText = text?.toString().orEmpty()
            invalidate()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composingText.isNotEmpty()) send(composingText)
            composingText = ""
            invalidate()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtLeast(0)) { send("\u007f") }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean =
            this@TerminalView.dispatchKeyEvent(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            send("\r")
            return true
        }
    }

    private fun sp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value.toFloat(),
        resources.displayMetrics
    )
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val MIN_TEXT_SP = 10
        const val MAX_TEXT_SP = 22
        const val CELL_WIDTH_SAMPLE = "0"
    }
}
