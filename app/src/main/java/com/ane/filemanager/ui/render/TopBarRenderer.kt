package com.ane.filemanager.ui.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ane.filemanager.R
import kotlin.math.min

/** Renders the app menu, parent navigation, address field, and sort affordance. */
internal class TopBarRenderer(private val drawing: RenderDrawingContext) {
    private val paint get() = drawing.paint
    private val stroke get() = drawing.stroke
    private fun dp(value: Float) = drawing.dp(value)
    private fun color(name: String) = drawing.color(name)

    fun draw(canvas: Canvas) {
        val state = drawing.state
        val tab = state.tabs[state.activeTab]
        val centerY = (drawing.topBarTop + drawing.topBarBottom) / 2f
        val menuX = drawing.contentLeft + dp(25f)
        val upX = drawing.contentLeft + dp(70f)
        paint.color = color("surface")
        canvas.drawRect(
            drawing.contentLeft,
            drawing.topBarTop,
            drawing.contentRight,
            drawing.topBarBottom,
            paint
        )
        paint.color = color("line")
        canvas.drawRect(
            drawing.contentLeft,
            drawing.topBarBottom - dp(1f),
            drawing.contentRight,
            drawing.topBarBottom,
            paint
        )
        canvas.save()
        canvas.clipRect(
            drawing.contentLeft,
            drawing.topBarTop,
            drawing.contentRight,
            drawing.topBarBottom
        )
        drawMenuIcon(canvas, menuX, centerY)
        drawNavigateUpIcon(
            canvas,
            upX,
            centerY,
            if (tab.directory.parentFile != null) color("text") else color("muted")
        )
        val title = if (state.multiSelect) {
            drawing.context.getString(R.string.multi_select_count, state.selected.size)
        } else state.addressOverride ?: tab.directory.absolutePath
        val verticalPad = dp(8f)
        val sortX = drawing.contentRight - dp(29f)
        val addressRight = (drawing.contentRight - dp(62f)).coerceAtLeast(drawing.contentLeft)
        val addressLeft = (drawing.contentLeft + dp(90f)).coerceAtMost(addressRight)
        val address = RectF(
            addressLeft,
            drawing.topBarTop + verticalPad,
            addressRight,
            drawing.topBarBottom - verticalPad
        )
        paint.color = color("surface2")
        canvas.drawRoundRect(address, dp(10f), dp(10f), paint)
        stroke.color = color("line")
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(address, dp(10f), dp(10f), stroke)
        val horizontalPad = min(dp(14f), address.width() / 3f)
        drawing.overflow.draw(
            canvas,
            "static:address",
            title,
            RectF(address.left + horizontalPad, address.top, address.right - horizontalPad, address.bottom),
            centerY,
            if (state.multiSelect) 15f else 14f,
            color("text"),
            Paint.Align.LEFT,
            state.multiSelect,
            false
        )
        drawSortIcon(canvas, sortX, centerY)
        canvas.restore()
    }

    private fun drawMenuIcon(canvas: Canvas, cx: Float, cy: Float) {
        prepareIconStroke(color("text"))
        val half = dp(9f)
        for (offset in floatArrayOf(-6f, 0f, 6f)) {
            canvas.drawLine(cx - half, cy + dp(offset), cx + half, cy + dp(offset), stroke)
        }
    }

    private fun drawNavigateUpIcon(canvas: Canvas, cx: Float, cy: Float, tint: Int) {
        prepareIconStroke(tint)
        canvas.drawLine(cx, cy + dp(9f), cx, cy - dp(8f), stroke)
        canvas.drawLine(cx, cy - dp(8f), cx - dp(6f), cy - dp(2f), stroke)
        canvas.drawLine(cx, cy - dp(8f), cx + dp(6f), cy - dp(2f), stroke)
    }

    private fun drawSortIcon(canvas: Canvas, cx: Float, cy: Float) {
        prepareIconStroke(color("text"))
        canvas.drawLine(cx - dp(9f), cy - dp(6f), cx + dp(9f), cy - dp(6f), stroke)
        canvas.drawLine(cx - dp(6f), cy, cx + dp(6f), cy, stroke)
        canvas.drawLine(cx - dp(3f), cy + dp(6f), cx + dp(3f), cy + dp(6f), stroke)
    }

    private fun prepareIconStroke(tint: Int) {
        stroke.color = tint
        stroke.alpha = 255
        stroke.strokeWidth = dp(2.1f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.style = Paint.Style.STROKE
    }
}
