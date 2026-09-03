package com.ane.filemanager.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuHit
import com.ane.filemanager.ui.model.MenuKind
import kotlin.math.min

/** Lays out and renders the root menu plus nested submenu panels and their hit regions. */
internal class MenuPanelRenderer(private val drawing: RenderDrawingContext) {
    private val paint get() = drawing.paint
    private val stroke get() = drawing.stroke
    private val state get() = drawing.state

    val menuHits = mutableListOf<MenuHit>()

    fun draw(canvas: Canvas) {
        val progress = state.motion.menuProgress.coerceIn(0f, 1f)
        paint.color = Color.argb((0x44 * progress).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, drawing.width.toFloat(), drawing.height.toFloat(), paint)
        val layers = state.menuLayers
        if (layers.isEmpty()) {
            menuHits.clear()
            return
        }
        val horizontalMargin = dp(8f)
        val verticalMargin = dp(8f)
        val panelPadding = dp(14f)
        val panelGap = dp(6f)
        val availableMenuWidth =
            (drawing.contentRight - drawing.contentLeft - horizontalMargin * 2f).coerceAtLeast(0f)
        val availableMenuHeight =
            (drawing.height - state.insets.top - state.insets.bottom - verticalMargin * 2f)
                .coerceAtLeast(0f)
        val minLeft = drawing.contentLeft + horizontalMargin
        val maxRight = drawing.contentRight - horizontalMargin
        val minTop = state.insets.top + verticalMargin
        val maxBottom = drawing.height - state.insets.bottom - verticalMargin

        fun itemHeight(actions: List<MenuAction>): Float = if (actions.isEmpty()) 0f else min(
            dp(48f),
            ((availableMenuHeight - panelPadding) / actions.size).coerceAtLeast(0f)
        )

        fun panelHeight(actions: List<MenuAction>, rowHeight: Float) =
            rowHeight * actions.size + panelPadding

        fun desiredPanelWidth(actions: List<MenuAction>): Float {
            drawing.configureText(15f, color("text"), false)
            val labelWidth = actions.maxOfOrNull { paint.measureText(it.label) } ?: 0f
            val arrowAllowance = if (actions.any { it.children.isNotEmpty() }) dp(22f) else 0f
            return (labelWidth + dp(46f) + arrowAllowance)
                .coerceIn(dp(132f).coerceAtMost(availableMenuWidth), dp(216f))
                .coerceAtMost(availableMenuWidth)
        }

        val desiredWidths = layers.map(::desiredPanelWidth)
        val panelWidths = MenuPanelWidthPolicy.fit(
            desiredWidths = desiredWidths,
            availableWidth = availableMenuWidth,
            gap = panelGap,
            minimumWidth = dp(112f)
        )

        val panels = mutableListOf<Panel>()
        val rootActions = layers.first()
        val rootWidth = panelWidths.first()
        val rootRowHeight = itemHeight(rootActions)
        val rootHeight = panelHeight(rootActions, rootRowHeight)
        val rootMaxLeft = (maxRight - rootWidth).coerceAtLeast(minLeft)
        val rootLeft = if (state.menuKind == MenuKind.FAB) {
            val anchoredRight = (state.menuX + dp(216f)).coerceIn(minLeft + rootWidth, maxRight)
            anchoredRight - rootWidth
        } else {
            state.menuX.coerceIn(minLeft, rootMaxLeft)
        }
        val rootTop = state.menuY.coerceIn(minTop, (maxBottom - rootHeight).coerceAtLeast(minTop))
        panels += Panel(
            actions = rootActions,
            rect = RectF(rootLeft, rootTop, rootLeft + rootWidth, rootTop + rootHeight),
            itemHeight = rootRowHeight,
            parent = null,
            direction = 0
        )

        layers.drop(1).forEachIndexed { childIndex, actions ->
            val previous = panels.last()
            val childWidth = panelWidths[childIndex + 1]
            val parentIndex = previous.actions.indexOfFirst { it.children === actions }
                .takeIf { it >= 0 }
                ?: previous.actions.indexOfFirst { it.children == actions }.coerceAtLeast(0)
            val parent = previous.actions.getOrNull(parentIndex)
            val rowHeight = itemHeight(actions)
            val childHeight = panelHeight(actions, rowHeight)
            val fitsLeft = previous.rect.left - panelGap - childWidth >= minLeft
            val fitsRight = previous.rect.right + panelGap + childWidth <= maxRight
            val direction = when {
                fitsLeft -> -1
                fitsRight -> 1
                previous.rect.left - minLeft >= maxRight - previous.rect.right -> -1
                else -> 1
            }
            val requestedLeft = if (direction < 0) {
                previous.rect.left - panelGap - childWidth
            } else {
                previous.rect.right + panelGap
            }
            val childMaxLeft = (maxRight - childWidth).coerceAtLeast(minLeft)
            val left = requestedLeft.coerceIn(minLeft, childMaxLeft)
            val parentRowTop = previous.rect.top + dp(7f) + parentIndex * previous.itemHeight
            val top = (parentRowTop - dp(7f)).coerceIn(
                minTop,
                (maxBottom - childHeight).coerceAtLeast(minTop)
            )
            panels += Panel(
                actions = actions,
                rect = RectF(left, top, left + childWidth, top + childHeight),
                itemHeight = rowHeight,
                parent = parent,
                direction = direction
            )
        }

        val scale = .84f + .16f * progress
        canvas.save()
        canvas.scale(scale, scale, state.menuOriginX, state.menuOriginY)
        menuHits.clear()
        panels.forEachIndexed { layerIndex, panel ->
            val layerProgress = if (layerIndex == state.motion.animatedMenuLayer) {
                state.motion.menuLayerProgress.coerceIn(0f, 1f)
            } else {
                1f
            }
            val combinedProgress = progress * layerProgress
            canvas.save()
            if (layerIndex > 0 && layerProgress < 1f) {
                val slideDistance = dp(20f) * (1f - layerProgress)
                canvas.translate(-panel.direction * slideDistance, 0f)
                val layerScale = .96f + .04f * layerProgress
                val pivotX = if (panel.direction < 0) panel.rect.right else panel.rect.left
                canvas.scale(layerScale, layerScale, pivotX, panel.rect.centerY())
            }
            val hitTransform = Matrix()
            canvas.getMatrix(hitTransform)
            paint.color = Color.BLACK
            paint.alpha = (48 * combinedProgress).toInt()
            val shadowPanel = RectF(panel.rect).apply { offset(0f, dp(4f)) }
            canvas.drawRoundRect(shadowPanel, dp(16f), dp(16f), paint)
            paint.color = color("surface")
            paint.alpha = (255 * combinedProgress).toInt()
            canvas.drawRoundRect(panel.rect, dp(14f), dp(14f), paint)

            panel.actions.forEachIndexed { index, action ->
                val rect = RectF(
                    panel.rect.left + dp(5f),
                    panel.rect.top + dp(7f) + index * panel.itemHeight,
                    panel.rect.right - dp(5f),
                    panel.rect.top + dp(7f) + (index + 1) * panel.itemHeight
                )
                val hitRect = RectF(rect)
                hitTransform.mapRect(hitRect)
                menuHits += MenuHit(action, hitRect)
                val childPanel = panels.getOrNull(layerIndex + 1)?.takeIf { it.parent === action }
                if (childPanel != null) {
                    val highlightProgress = if (layerIndex + 1 == state.motion.animatedMenuLayer) {
                        combinedProgress * state.motion.menuLayerProgress.coerceIn(0f, 1f)
                    } else {
                        combinedProgress
                    }
                    paint.color = drawing.fadeColor(color("surface2"), highlightProgress)
                    paint.alpha = (255 * highlightProgress).toInt()
                    canvas.drawRoundRect(rect, dp(9f), dp(9f), paint)
                }
                val direction = if (action.children.isEmpty()) 0 else childPanel?.direction
                    ?: preferredSubmenuDirection(
                        panel.rect,
                        desiredPanelWidth(action.children),
                        panelGap,
                        horizontalMargin
                    )
                val textLeft = rect.left + dp(if (direction < 0) 31f else 17f)
                val textRight = rect.right - dp(if (direction > 0) 31f else 12f)
                drawing.overflow.draw(
                    canvas,
                    "static:menu:$layerIndex:$index",
                    action.label,
                    RectF(textLeft, rect.top, textRight, rect.bottom),
                    rect.centerY(),
                    15f,
                    drawing.fadeColor(
                        if (action.enabled) color("text") else color("muted"),
                        combinedProgress
                    ),
                    Paint.Align.LEFT,
                    false,
                    false
                )
                if (direction != 0) {
                    drawMenuChevron(canvas, rect, direction, action.enabled, combinedProgress)
                }
            }
            canvas.restore()
        }
        canvas.restore()
        paint.alpha = 255
    }

    fun clearHits() = menuHits.clear()

    private fun preferredSubmenuDirection(
        panel: RectF,
        menuWidth: Float,
        panelGap: Float,
        horizontalMargin: Float
    ): Int {
        val minLeft = drawing.contentLeft + horizontalMargin
        val maxRight = drawing.contentRight - horizontalMargin
        return when {
            panel.left - panelGap - menuWidth >= minLeft -> -1
            panel.right + panelGap + menuWidth <= maxRight -> 1
            panel.left - minLeft >= maxRight - panel.right -> -1
            else -> 1
        }
    }

    private fun drawMenuChevron(
        canvas: Canvas,
        rect: RectF,
        direction: Int,
        enabled: Boolean,
        progress: Float
    ) {
        val centerX = if (direction < 0) rect.left + dp(16f) else rect.right - dp(16f)
        val centerY = rect.centerY()
        val armX = dp(3.2f)
        val armY = dp(4.6f)
        stroke.color = drawing.fadeColor(if (enabled) color("text") else color("muted"), progress)
        stroke.alpha = 255
        stroke.strokeWidth = dp(1.6f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val tipX = centerX + direction * armX
        val tailX = centerX - direction * armX
        canvas.drawLine(tailX, centerY - armY, tipX, centerY, stroke)
        canvas.drawLine(tipX, centerY, tailX, centerY + armY, stroke)
    }

    private fun dp(value: Float) = drawing.dp(value)

    private fun color(name: String) = drawing.color(name)

    private data class Panel(
        val actions: List<MenuAction>,
        val rect: RectF,
        val itemHeight: Float,
        val parent: MenuAction?,
        val direction: Int
    )
}
