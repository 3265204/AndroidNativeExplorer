package com.ane.filemanager.ui.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.ane.filemanager.R
import com.ane.filemanager.operation.TransferTargetPolicy
import com.ane.filemanager.plugin.api.PluginFileIcon
import com.ane.filemanager.ui.model.FileHit
import com.ane.filemanager.ui.model.MenuHit
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.RenderState
import com.ane.filemanager.ui.model.TabHit
import com.ane.filemanager.ui.model.TabMotionStart
import java.io.File
import kotlin.math.min

/** Canvas boundary that coordinates focused renderers without mutating browser state. */
internal class FileManagerRenderer(
    context: Context,
    pluginFileIcon: (File) -> PluginFileIcon?,
    onInvalidate: () -> Unit
) {
    private val drawing = RenderDrawingContext(context, onInvalidate)
    private val icons = IconPainter(drawing, pluginFileIcon)
    private val topBar = TopBarRenderer(drawing)
    private val files = FileGridRenderer(drawing, icons, onInvalidate)
    private val dockBar = DockBarRenderer(drawing)
    private val menuPanels = MenuPanelRenderer(drawing)
    private val paint get() = drawing.paint
    private val stroke get() = drawing.stroke

    private val fabShadow by lazy {
        val size = dp(84f).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = 42
                maskFilter = BlurMaskFilter(dp(7f), BlurMaskFilter.Blur.NORMAL)
            }
            Canvas(bitmap).drawCircle(size / 2f, size / 2f, dp(27f), shadowPaint)
        }
    }

    val fileHits: List<FileHit> get() = files.fileHits
    val tabHits: List<TabHit> get() = dockBar.tabHits
    val tabSlotHits: List<TabHit> get() = dockBar.tabSlotHits
    val tabCloseHits: List<TabHit> get() = dockBar.tabCloseHits
    val menuHits: List<MenuHit> get() = menuPanels.menuHits
    val maxScroll get() = files.maxScroll
    val maxDockScroll get() = dockBar.maxScroll

    val topHeight get() = drawing.topHeight
    val bottomHeight get() = drawing.bottomHeight
    val fabOffset get() = drawing.fabOffset
    val appMenuHitWidth get() = dp(50f)
    val navigateUpHitWidth get() = dp(90f)

    fun isSortButton(viewWidth: Int, x: Float, rightInset: Int = 0): Boolean =
        x >= viewWidth - rightInset - dp(58f)

    fun contentBottom(viewHeight: Int, bottomInset: Int = 0) =
        viewHeight - bottomInset - bottomHeight

    fun isFab(
        viewWidth: Int,
        viewHeight: Int,
        x: Float,
        y: Float,
        rightInset: Int = 0,
        bottomInset: Int = 0
    ): Boolean {
        val cx = viewWidth - rightInset - fabOffset
        val cy = contentBottom(viewHeight, bottomInset) - fabOffset
        val hitRadius = dp(33f)
        return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= hitRadius * hitRadius
    }

    fun fileAt(x: Float, y: Float): File? = files.fileAt(x, y)

    fun tabVisualStarts(): List<TabMotionStart> = dockBar.visualStarts()

    fun restartFileMarquee(file: File) = drawing.overflow.restartFile(file)

    fun restartTabMarquee(index: Int) = drawing.overflow.restartTab(index)

    fun scrollToRevealTab(index: Int, currentScroll: Float): Float =
        dockBar.scrollToReveal(index, currentScroll)

    fun scrollToRevealFile(file: File, currentScroll: Float): Float =
        files.scrollToReveal(file, currentScroll)

    fun selectionHandleFile(x: Float, y: Float): File? = files.selectionHandleFile(x, y)

    fun draw(canvas: Canvas, renderState: RenderState) {
        drawing.beginFrame(renderState, canvas.width, canvas.height)
        canvas.drawColor(color("bg"))
        topBar.draw(canvas)
        files.draw(canvas)
        dockBar.draw(canvas)
        if (renderState.dragReady || renderState.dragging) drawDragPreview(canvas)
        drawFab(canvas)
        if (renderState.menuKind != MenuKind.NONE) menuPanels.draw(canvas) else menuPanels.clearHits()
        renderState.busyText?.let { drawBusy(canvas, it) }
    }

    fun surfaceColor(dark: Boolean): Int = drawing.surfaceColor(dark)

    fun close() = files.close()

    fun onDirectoryContentsChanged() = files.onDirectoryContentsChanged()

    private fun drawFab(canvas: Canvas) {
        val state = drawing.state
        val cx = drawing.contentRight - fabOffset
        val cy = drawing.contentBottom - fabOffset
        paint.alpha = 255
        canvas.drawBitmap(
            fabShadow,
            cx - fabShadow.width / 2f,
            cy - fabShadow.height / 2f + dp(3f),
            paint
        )
        val cancelHover = state.dragging && isFab(
            drawing.width,
            drawing.height,
            state.dragX,
            state.dragY,
            state.insets.right,
            state.insets.bottom
        )
        paint.color = if (state.dragging) Color.rgb(239, 68, 68) else color("primary")
        val normalRadius = 26f
        canvas.drawCircle(cx, cy, dp(if (cancelHover) normalRadius + 3f else normalRadius), paint)
        if (state.dragging) {
            paint.color = Color.WHITE
            canvas.drawRoundRect(
                RectF(cx - dp(7f), cy - dp(7f), cx + dp(7f), cy + dp(7f)),
                dp(2f),
                dp(2f),
                paint
            )
            if (cancelHover) drawCancelDragHint(canvas, cx, cy)
        } else {
            stroke.color = Color.WHITE
            stroke.alpha = 255
            stroke.strokeWidth = dp(2.4f)
            stroke.strokeCap = Paint.Cap.ROUND
            val arm = dp(7f)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, stroke)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, stroke)
        }
    }

    private fun drawCancelDragHint(canvas: Canvas, fabX: Float, fabY: Float) {
        val label = drawing.context.getString(R.string.drag_release_to_cancel)
        val desiredWidth = drawing.textWidth(label, 12.5f, true) + dp(24f)
        val availableWidth = (fabX - drawing.contentLeft - dp(48f)).coerceAtLeast(0f)
        val hintWidth = min(desiredWidth, availableWidth)
        if (hintWidth <= 0f) return
        val rect = RectF(
            fabX - dp(38f) - hintWidth,
            fabY - dp(20f),
            fabX - dp(38f),
            fabY + dp(20f)
        )
        paint.color = if (drawing.state.appearance.dark) 0xEE272F3B.toInt() else 0xEEFFFFFF.toInt()
        canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
        drawing.overflow.draw(
            canvas,
            "static:cancel-drag",
            label,
            RectF(rect.left + dp(10f), rect.top, rect.right - dp(10f), rect.bottom),
            rect.centerY(),
            12.5f,
            color("text"),
            Paint.Align.CENTER,
            true,
            false
        )
    }

    private fun drawDragPreview(canvas: Canvas) {
        val state = drawing.state
        canvas.save()
        canvas.clipRect(
            drawing.contentLeft,
            drawing.topBarBottom,
            drawing.contentRight,
            drawing.contentBottom
        )
        if (state.dragging) {
            files.fileHits.firstOrNull {
                files.isDirectory(it.file) && it.rect.contains(state.dragX, state.dragY) &&
                    TransferTargetPolicy.accepts(state.dragSources, it.file)
            }?.let {
                stroke.color = color("primary")
                stroke.strokeWidth = dp(3f)
                canvas.drawRoundRect(it.rect, dp(10f), dp(10f), stroke)
            }
        }
        canvas.restore()
        paint.color = 0xD92972D2.toInt()
        val halfWidth = dp(65f)
        val halfHeight = dp(21f)
        val preview = RectF(
            state.dragX - halfWidth,
            state.dragY - halfHeight,
            state.dragX + halfWidth,
            state.dragY + halfHeight
        )
        canvas.drawRoundRect(preview, dp(9f), dp(9f), paint)
        drawing.overflow.draw(
            canvas,
            "static:drag-count",
            drawing.context.getString(R.string.move_count, state.dragCount),
            RectF(preview.left + dp(8f), preview.top, preview.right - dp(8f), preview.bottom),
            state.dragY,
            13f,
            Color.WHITE,
            Paint.Align.CENTER,
            true,
            false
        )
    }

    private fun drawBusy(canvas: Canvas, message: String) {
        paint.color = 0x66000000
        canvas.drawRect(0f, 0f, drawing.width.toFloat(), drawing.height.toFloat(), paint)
        val halfWidth = min(
            dp(100f),
            ((drawing.contentRight - drawing.contentLeft) / 2f - dp(16f)).coerceAtLeast(0f)
        )
        val centerX = (drawing.contentLeft + drawing.contentRight) / 2f
        val rect = RectF(
            centerX - halfWidth,
            drawing.height / 2f - dp(32f),
            centerX + halfWidth,
            drawing.height / 2f + dp(32f)
        )
        paint.color = color("surface")
        canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
        val horizontalPad = min(dp(12f), rect.width() / 3f)
        drawing.overflow.draw(
            canvas,
            "static:busy",
            message,
            RectF(rect.left + horizontalPad, rect.top, rect.right - horizontalPad, rect.bottom),
            rect.centerY(),
            15f,
            color("text"),
            Paint.Align.CENTER,
            true,
            false
        )
    }

    private fun dp(value: Float) = drawing.dp(value)

    private fun color(name: String) = drawing.color(name)
}
