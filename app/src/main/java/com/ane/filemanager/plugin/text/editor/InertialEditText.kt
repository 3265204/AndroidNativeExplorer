package com.ane.filemanager.plugin.text.editor

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An EditText that keeps the platform cursor and selection behavior while adding
 * a predictable touch fling. TextView's own touch scrolling differs considerably
 * between Android versions and some desktop/DeX environments.
 */
class InertialEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {
    private val scroller = OverScroller(context).apply {
        setFriction(ViewConfiguration.getScrollFriction() * .82f)
    }
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onFling(
            down: MotionEvent?,
            up: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (down == null || abs(velocityY) <= abs(velocityX) ||
                abs(velocityY) < minimumFlingVelocity
            ) return false

            val maxY = verticalScrollLimit()
            if (maxY <= 0) return false
            val flingVelocity = (-velocityY).coerceIn(
                -maximumFlingVelocity.toFloat(),
                maximumFlingVelocity.toFloat()
            ).roundToInt()
            scroller.fling(
                scrollX,
                scrollY.coerceIn(0, maxY),
                0,
                flingVelocity,
                scrollX,
                scrollX,
                0,
                maxY
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed) {
            if (event.isShiftPressed) decreaseIndent() else increaseIndent()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed) {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !scroller.isFinished) {
            scroller.abortAnimation()
        }
        // Let EditText update the cursor/selection first. Starting our fling after
        // ACTION_UP prevents its bookkeeping from immediately cancelling it.
        val handled = super.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return handled
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollTo(scrollX, scroller.currY.coerceIn(0, verticalScrollLimit()))
            postInvalidateOnAnimation()
        }
    }

    private fun verticalScrollLimit(): Int {
        val textLayout = layout ?: return 0
        val viewport = height - compoundPaddingTop - compoundPaddingBottom
        return (textLayout.height - viewport).coerceAtLeast(0)
    }

    private fun increaseIndent() {
        val editable = text ?: return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(start)
        if (start == end) {
            editable.insert(start, INDENT)
            setSelection(start + INDENT.length)
            return
        }

        val lineStarts = selectedLineStarts(start, end)
        beginBatchEdit()
        try {
            lineStarts.asReversed().forEach { editable.insert(it, INDENT) }
            val adjustedStart = start + lineStarts.count { it <= start } * INDENT.length
            val adjustedEnd = end + lineStarts.count { it < end } * INDENT.length
            setSelection(adjustedStart, adjustedEnd)
        } finally {
            endBatchEdit()
        }
    }

    private fun decreaseIndent() {
        val editable = text ?: return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(start)
        val lineStarts = selectedLineStarts(start, end)
        val removals = lineStarts.mapNotNull { lineStart ->
            val length = when {
                lineStart >= editable.length -> 0
                editable[lineStart] == '\t' -> 1
                else -> {
                    var spaces = 0
                    while (spaces < INDENT.length && lineStart + spaces < editable.length &&
                        editable[lineStart + spaces] == ' '
                    ) spaces++
                    spaces
                }
            }
            if (length > 0) lineStart to length else null
        }
        if (removals.isEmpty()) return

        fun offsetAfterRemoval(offset: Int): Int = offset - removals.sumOf { (position, length) ->
            when {
                offset <= position -> 0
                offset >= position + length -> length
                else -> offset - position
            }
        }

        beginBatchEdit()
        try {
            removals.asReversed().forEach { (position, length) ->
                editable.delete(position, position + length)
            }
            setSelection(offsetAfterRemoval(start), offsetAfterRemoval(end))
        } finally {
            endBatchEdit()
        }
    }

    private fun selectedLineStarts(start: Int, end: Int): List<Int> {
        val editable = text ?: return emptyList()
        val first = editable.lastIndexOf('\n', start - 1).let {
            if (it < 0) 0 else it + 1
        }
        // A selection ending exactly at the next line's first character does not
        // include that line, matching common desktop and Android code editors.
        val effectiveEnd = if (end > start && end > 0 && editable[end - 1] == '\n') end - 1 else end
        val starts = mutableListOf(first)
        var newline = editable.indexOf('\n', first)
        while (newline >= 0 && newline + 1 < effectiveEnd) {
            starts += newline + 1
            newline = editable.indexOf('\n', newline + 1)
        }
        return starts
    }

    private companion object {
        const val INDENT = "    "
    }
}
