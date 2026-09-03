package com.ane.filemanager.ui.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import java.io.File

/** Draws fitting, ellipsized, or animated marquee text and owns marquee timing state. */
internal class TextOverflow(
    private val drawing: RenderDrawingContext,
    private val onInvalidate: () -> Unit
) {
    private val marqueeStarts = mutableMapOf<String, Long>()

    fun draw(
        canvas: Canvas,
        marqueeKey: String,
        value: String,
        bounds: RectF,
        centerY: Float,
        sizeSp: Float,
        color: Int,
        alignWhenFits: Paint.Align,
        bold: Boolean,
        animateOverflow: Boolean
    ) {
        val paint = drawing.paint
        drawing.configureText(sizeSp, color, bold)
        if (bounds.width() <= 0f || bounds.height() <= 0f) {
            marqueeStarts.remove(marqueeKey)
            return
        }
        val available = bounds.width().coerceAtLeast(0f)
        val measured = paint.measureText(value)
        if (measured <= available) {
            marqueeStarts.remove(marqueeKey)
            val x = when (alignWhenFits) {
                Paint.Align.CENTER -> bounds.centerX()
                Paint.Align.RIGHT -> bounds.right
                else -> bounds.left
            }
            paint.textAlign = alignWhenFits
            canvas.drawText(value, x, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
            return
        }
        canvas.save()
        canvas.clipRect(bounds)
        paint.textAlign = Paint.Align.LEFT
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        if (animateOverflow) {
            val gap = drawing.dp(32f)
            val cycleDistance = measured + gap
            val now = SystemClock.uptimeMillis()
            val storedStart = marqueeStarts[marqueeKey]
            val startedAt = if (storedStart == null || storedStart == MARQUEE_RESTART_PENDING) {
                marqueeStarts[marqueeKey] = now
                now
            } else storedStart
            val elapsed = (now - startedAt).coerceAtLeast(0L)
            val offset = (elapsed * drawing.dp(34f) / 1000f) % cycleDistance
            canvas.drawText(value, bounds.left - offset, baseline, paint)
            canvas.drawText(value, bounds.left - offset + cycleDistance, baseline, paint)
        } else {
            marqueeStarts.remove(marqueeKey)
            canvas.drawText(ellipsizeToWidth(value, available), bounds.left, baseline, paint)
        }
        canvas.restore()
        if (animateOverflow) onInvalidate()
    }

    fun restartFile(file: File) {
        marqueeStarts[fileKey(file)] = MARQUEE_RESTART_PENDING
    }

    fun restartTab(index: Int) {
        marqueeStarts[tabKey(index)] = MARQUEE_RESTART_PENDING
    }

    fun fileKey(file: File) = "file:${file.absolutePath}"

    fun tabKey(index: Int) = "tab:$index"

    private fun ellipsizeToWidth(value: String, width: Float): String {
        val paint = drawing.paint
        if (paint.measureText(value) <= width) return value
        val suffix = "…"
        var end = value.length
        while (end > 0 && paint.measureText(value, 0, end) + paint.measureText(suffix) > width) end--
        return if (end == 0) suffix else value.substring(0, end) + suffix
    }

    private companion object {
        const val MARQUEE_RESTART_PENDING = -1L
    }
}
