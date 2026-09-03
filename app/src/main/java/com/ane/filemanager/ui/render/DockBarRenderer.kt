package com.ane.filemanager.ui.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ane.filemanager.operation.TransferTargetPolicy
import com.ane.filemanager.ui.model.TabHit
import com.ane.filemanager.ui.model.TabMotionStart
import kotlin.math.max
import kotlin.math.min

/** Renders the bottom dock, its motion states, management buttons, and hit regions. */
internal class DockBarRenderer(private val drawing: RenderDrawingContext) {
    private val paint get() = drawing.paint
    private val stroke get() = drawing.stroke
    private val state get() = drawing.state

    val tabHits = mutableListOf<TabHit>()
    val tabSlotHits = mutableListOf<TabHit>()
    val tabCloseHits = mutableListOf<TabHit>()
    var maxScroll = 0f
        private set
    private var visualTabStarts = emptyList<TabMotionStart>()

    fun draw(canvas: Canvas) {
        val bottom = drawing.height - state.insets.bottom.toFloat()
        val top = bottom - drawing.bottomHeight
        paint.color = color("surface")
        canvas.drawRect(drawing.contentLeft, top, drawing.contentRight, bottom, paint)
        paint.color = color("line")
        canvas.drawRect(drawing.contentLeft, top, drawing.contentRight, top + dp(1f), paint)
        tabHits.clear()
        tabSlotHits.clear()
        tabCloseHits.clear()
        val viewportWidth = drawing.contentRight - drawing.contentLeft
        if (viewportWidth <= 0f) {
            maxScroll = 0f
            return
        }
        val minTabWidth = min(dp(86f), viewportWidth)
        val maxTabWidth = max(minTabWidth, min(dp(220f), viewportWidth * .72f))
        val widths = state.tabs.map { tab ->
            drawing.textWidth(tab.label, 12.5f, false)
                .plus(dp(if (state.dockEditing) 54f else 36f))
                .coerceIn(minTabWidth, maxTabWidth)
        }.toMutableList()
        val measuredWidth = widths.sum()
        if (measuredWidth < viewportWidth && widths.isNotEmpty()) {
            val extra = (viewportWidth - measuredWidth) / widths.size
            widths.indices.forEach { widths[it] += extra }
        }
        val totalWidth = widths.sum()
        maxScroll = max(0f, totalWidth - viewportWidth)
        var logicalLeft = drawing.contentLeft
        val targetRects = widths.map { tabWidth ->
            RectF(
                logicalLeft - state.dockScrollX,
                top,
                logicalLeft + tabWidth - state.dockScrollX,
                bottom
            ).also { logicalLeft += tabWidth }
        }
        val reorderProgress = state.dockMotion.reorderProgress.coerceIn(0f, 1f)
        val tabRects = targetRects.mapIndexed { index, target ->
            val previousLeft = state.dockMotion.reorderStarts
                .firstOrNull { it.tab === state.tabs[index] }?.left
            RectF(target).apply {
                if (previousLeft != null && reorderProgress < 1f) {
                    offset((previousLeft - target.left) * (1f - reorderProgress), 0f)
                }
            }
        }
        visualTabStarts = state.tabs.mapIndexed { index, tab -> TabMotionStart(tab, tabRects[index].left) }
        canvas.save()
        canvas.clipRect(drawing.contentLeft, top, drawing.contentRight, bottom)
        drawActiveTabIndicator(canvas, tabRects)
        state.tabs.forEachIndexed { index, tab ->
            val rect = tabRects[index]
            val targetRect = targetRects[index]
            if (rect.right >= drawing.contentLeft && rect.left <= drawing.contentRight) {
                tabHits += TabHit(index, RectF(rect))
            }
            if (targetRect.right >= drawing.contentLeft && targetRect.left <= drawing.contentRight) {
                tabSlotHits += TabHit(index, RectF(targetRect))
            }
            val dragTarget = state.dragging && rect.contains(state.dragX, state.dragY) &&
                TransferTargetPolicy.accepts(state.dragSources, tab.directory)
            val tabBeingDragged = state.tabDragging && index == state.draggedTabIndex
            if (dragTarget || tabBeingDragged) {
                paint.color = if (dragTarget) color("selected") else color("surface2")
                val lift = if (tabBeingDragged) dp(3f) else 0f
                val highlight = RectF(
                    rect.left + dp(5f), rect.top + dp(8f) - lift,
                    rect.right - dp(5f), rect.bottom - dp(7f) - lift
                )
                canvas.drawRoundRect(highlight, dp(10f), dp(10f), paint)
                paint.color = color("primary")
                canvas.drawRoundRect(
                    RectF(rect.left + dp(19f), rect.top + dp(5f), rect.right - dp(19f), rect.top + dp(8f)),
                    dp(2f), dp(2f), paint
                )
                if (tabBeingDragged) {
                    stroke.color = color("primary")
                    stroke.strokeWidth = dp(1.5f)
                    canvas.drawRoundRect(highlight, dp(10f), dp(10f), stroke)
                }
            }
            val labelRightPadding = if (state.dockEditing && index > 0) 29f else 13f
            drawing.overflow.draw(
                canvas,
                drawing.overflow.tabKey(index),
                tab.label,
                RectF(
                    rect.left + dp(13f), rect.top + dp(7f),
                    rect.right - dp(labelRightPadding), rect.bottom - dp(6f)
                ),
                rect.centerY(),
                12.5f,
                when {
                    index == state.activeTab -> color("primary")
                    tab.pinned -> color("muted")
                    else -> color("text")
                },
                Paint.Align.CENTER,
                index == state.activeTab,
                index == state.activeTab || dragTarget || tabBeingDragged
            )
            if (state.dockEditing && index > 0) drawTabManagementButton(canvas, index, rect)
        }
        canvas.restore()
    }

    fun visualStarts(): List<TabMotionStart> = visualTabStarts.toList()

    fun scrollToReveal(index: Int, currentScroll: Float): Float {
        val rect = tabHits.firstOrNull { it.index == index }?.rect ?: return currentScroll
        return when {
            rect.left < drawing.contentLeft -> currentScroll - (drawing.contentLeft - rect.left)
            rect.right > drawing.contentRight -> currentScroll + (rect.right - drawing.contentRight)
            else -> currentScroll
        }.coerceIn(0f, maxScroll)
    }

    private fun drawActiveTabIndicator(canvas: Canvas, rects: List<RectF>) {
        val activeRect = rects.getOrNull(state.activeTab) ?: return
        val motion = state.dockMotion
        val from = rects.getOrNull(motion.fromTab)
        val to = rects.getOrNull(motion.toTab)
        val rect = if (from != null && to != null && motion.indicatorProgress < 1f) {
            val progress = motion.indicatorProgress.coerceIn(0f, 1f)
            RectF(
                lerp(from.left, to.left, progress),
                lerp(from.top, to.top, progress),
                lerp(from.right, to.right, progress),
                lerp(from.bottom, to.bottom, progress)
            )
        } else activeRect
        paint.color = color("surface2")
        canvas.drawRoundRect(
            RectF(rect.left + dp(5f), rect.top + dp(8f), rect.right - dp(5f), rect.bottom - dp(7f)),
            dp(10f), dp(10f), paint
        )
        paint.color = color("primary")
        canvas.drawRoundRect(
            RectF(rect.left + dp(19f), rect.top + dp(5f), rect.right - dp(19f), rect.top + dp(8f)),
            dp(2f), dp(2f), paint
        )
    }

    private fun drawTabManagementButton(canvas: Canvas, index: Int, tabRect: RectF) {
        val cx = tabRect.right - dp(13f)
        val cy = tabRect.top + dp(15f)
        val accent = color("danger")
        val softAccent = drawing.blend(drawing.desaturate(accent, .48f), color("muted"), .16f)
        val badgeColor = drawing.blend(
            color("surface2"),
            softAccent,
            if (state.appearance.dark) .32f else .20f
        )
        paint.color = badgeColor
        paint.alpha = 255
        canvas.drawCircle(cx, cy, dp(8.5f), paint)
        stroke.color = drawing.blend(color("line"), softAccent, .58f)
        stroke.alpha = 255
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, dp(8f), stroke)
        stroke.color = softAccent
        stroke.strokeWidth = dp(1.65f)
        stroke.strokeCap = Paint.Cap.ROUND
        val arm = dp(2.8f)
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, stroke)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, stroke)
        tabCloseHits += TabHit(
            index,
            RectF(cx - dp(16f), tabRect.top, cx + dp(16f), tabRect.top + dp(37f))
        )
    }

    private fun lerp(start: Float, end: Float, progress: Float) = start + (end - start) * progress

    private fun dp(value: Float) = drawing.dp(value)

    private fun color(name: String) = drawing.color(name)
}
