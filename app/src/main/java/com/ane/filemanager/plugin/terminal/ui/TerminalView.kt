package com.ane.filemanager.plugin.terminal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.ane.filemanager.R
import com.ane.filemanager.plugin.terminal.TerminalEmulator
import com.ane.filemanager.plugin.api.input.AnePluginInput
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.motion.GestureTiming
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

@SuppressLint("ViewConstructor")
internal class TerminalView(
    context: Context,
    private val palette: AneTheme,
    private val input: AnePluginInput,
    initialTextSizeSp: Int,
    private val copySelection: (String) -> Unit,
    private val paste: () -> Unit
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
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartScroll = 0
    private var touchMoved = false
    private var longPressTriggered = false
    private var mouseSelecting = false
    private var mouseSelectionAnchor: TerminalTextPosition? = null
    private var lastSecondaryClickTime = 0L
    private var lastSecondaryClickX = 0f
    private var lastSecondaryClickY = 0f
    private var actionAnchorX = 0f
    private var actionAnchorY = 0f
    private var textSelection: TerminalTextSelection? = null
    private var selectionActionMode: ActionMode? = null
    private var composingText = ""
    private val longPressRunnable = Runnable(::showLongPressActions)

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
        clearSelectionActions()
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
                if (textSelection?.contains(row, column) == true) {
                    paint.color = palette.selected
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
        if (handleSecondaryMousePress(event)) return true
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouseTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearSelectionActions()
                touchStartX = event.x
                touchStartY = event.y
                touchStartScroll = scrollOffset
                touchMoved = false
                longPressTriggered = false
                gestureHandler.postDelayed(
                    longPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = max(abs(event.x - touchStartX), abs(event.y - touchStartY))
                if (!longPressTriggered && distance > touchSlop) {
                    touchMoved = true
                    gestureHandler.removeCallbacks(longPressRunnable)
                }
                if (longPressTriggered && distance > touchSlop) {
                    touchMoved = true
                    textSelection = textSelection?.withFocus(positionAt(event.x, event.y))
                    selectionActionMode?.invalidate()
                    selectionActionMode?.invalidateContentRect()
                } else if (touchMoved) {
                    val lines = ((event.y - touchStartY) / lineHeight).toInt()
                    val maximum = emulator.snapshot(scrollOffset).maximumScrollOffset
                    scrollOffset = (touchStartScroll + lines).coerceIn(0, maximum)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                gestureHandler.removeCallbacks(longPressRunnable)
                if (!touchMoved && !longPressTriggered) {
                    showKeyboard()
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureHandler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleSecondaryMousePress(event)) return true
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) {
            val lines = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) *
                MOUSE_SCROLL_LINES_PER_NOTCH).toInt()
            if (lines != 0) {
                clearSelectionActions()
                val maximum = emulator.snapshot(scrollOffset).maximumScrollOffset
                scrollOffset = (scrollOffset + lines).coerceIn(0, maximum)
                invalidate()
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun onMouseTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                clearSelectionActions()
                touchStartX = event.x
                touchStartY = event.y
                touchMoved = false
                mouseSelecting = true
                mouseSelectionAnchor = positionAt(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mouseSelecting) return true
                val distance = max(abs(event.x - touchStartX), abs(event.y - touchStartY))
                if (distance > touchSlop) {
                    touchMoved = true
                    val anchor = mouseSelectionAnchor ?: positionAt(touchStartX, touchStartY)
                    textSelection = TerminalTextSelection(anchor, positionAt(event.x, event.y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mouseSelecting && !touchMoved) textSelection = null
                mouseSelecting = false
                mouseSelectionAnchor = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleSecondaryMousePress(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val secondaryPress = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> event.actionButton == MotionEvent.BUTTON_SECONDARY
            MotionEvent.ACTION_DOWN -> event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
            else -> false
        }
        if (!secondaryPress) return false

        val duplicate = event.eventTime - lastSecondaryClickTime <
            GestureTiming.SECONDARY_CLICK_DEDUP_TIMEOUT_MS &&
            max(abs(event.x - lastSecondaryClickX), abs(event.y - lastSecondaryClickY)) < touchSlop
        if (!duplicate) {
            lastSecondaryClickTime = event.eventTime
            lastSecondaryClickX = event.x
            lastSecondaryClickY = event.y
            requestFocus()
            gestureHandler.removeCallbacks(longPressRunnable)
            actionAnchorX = event.x
            actionAnchorY = event.y
            if (selectionActionMode == null) {
                selectionActionMode = startActionMode(
                    selectionActionCallback,
                    ActionMode.TYPE_FLOATING
                )
            } else {
                selectionActionMode?.invalidate()
                selectionActionMode?.invalidateContentRect()
            }
        }
        return true
    }

    private fun showLongPressActions() {
        if (touchMoved || !isAttachedToWindow) return
        longPressTriggered = true
        actionAnchorX = touchStartX
        actionAnchorY = touchStartY
        val snapshot = emulator.snapshot(scrollOffset)
        textSelection = TerminalSelectionText.wordAt(
            snapshot.lines,
            positionAt(touchStartX, touchStartY)
        )
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectionActionMode = startActionMode(selectionActionCallback, ActionMode.TYPE_FLOATING)
        invalidate()
    }

    private fun positionAt(x: Float, y: Float): TerminalTextPosition {
        val row = floor((y - paddingTop) / lineHeight).toInt().coerceIn(0, emulator.rows - 1)
        val column = floor((x - paddingLeft) / cellWidth).toInt().coerceIn(0, emulator.columns - 1)
        return TerminalTextPosition(row, column)
    }

    private val selectionActionCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, ACTION_COPY, 0, context.getString(R.string.terminal_copy))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_SELECT_ALL, 1, context.getString(R.string.terminal_select_all))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_PASTE, 2, context.getString(R.string.terminal_paste))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val hasSelection = textSelection != null
            menu.findItem(ACTION_COPY)?.isVisible = hasSelection
            menu.findItem(ACTION_SELECT_ALL)?.isVisible =
                TerminalSelectionText.all(emulator.snapshot(scrollOffset).lines) != null
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ACTION_COPY -> {
                    val snapshot = emulator.snapshot(scrollOffset)
                    val value = textSelection?.let { TerminalSelectionText.extract(snapshot.lines, it) }
                    if (!value.isNullOrEmpty()) copySelection(value)
                    mode.finish()
                }
                ACTION_SELECT_ALL -> {
                    textSelection = TerminalSelectionText.all(emulator.snapshot(scrollOffset).lines)
                    mode.invalidate()
                    mode.invalidateContentRect()
                    invalidate()
                }
                ACTION_PASTE -> {
                    paste()
                    mode.finish()
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (selectionActionMode === mode) selectionActionMode = null
            invalidate()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val selection = textSelection
            if (selection == null) {
                val half = dp(1)
                outRect.set(
                    (actionAnchorX - half).toInt(),
                    (actionAnchorY - half).toInt(),
                    (actionAnchorX + half).toInt(),
                    (actionAnchorY + half).toInt()
                )
                return
            }
            outRect.set(
                (paddingLeft + selection.start.column * cellWidth).toInt(),
                (paddingTop + selection.start.row * lineHeight).toInt(),
                (paddingLeft + (selection.end.column + 1) * cellWidth).toInt(),
                (paddingTop + (selection.end.row + 1) * lineHeight).toInt()
            )
        }
    }

    private fun clearSelectionActions() {
        gestureHandler.removeCallbacks(longPressRunnable)
        selectionActionMode?.finish()
        selectionActionMode = null
        textSelection = null
    }

    override fun onDetachedFromWindow() {
        clearSelectionActions()
        super.onDetachedFromWindow()
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
        val textMetaState = event.metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        input.terminalHardware(
            keyCode = keyCode,
            metaState = event.metaState,
            unicodeCodePoint = event.getUnicodeChar(textMetaState)
        )?.let {
            send(it)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val characters = event.characters
        if (!characters.isNullOrEmpty()) {
            send(input.terminalCharacters(characters, event.metaState))
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
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
        const val ACTION_COPY = 1
        const val ACTION_SELECT_ALL = 2
        const val ACTION_PASTE = 3
        const val MOUSE_SCROLL_LINES_PER_NOTCH = 3f
        const val MIN_TEXT_SP = 10
        const val MAX_TEXT_SP = 22
        const val CELL_WIDTH_SAMPLE = "0"
    }
}
