package com.ane.filemanager.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ane.filemanager.R
import com.ane.filemanager.ui.model.FileHit
import com.ane.filemanager.ui.model.LayoutMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Owns virtualized list/grid layout, file hit regions, metadata, and thumbnail drawing. */
internal class FileGridRenderer(
    private val drawing: RenderDrawingContext,
    private val icons: IconPainter,
    onInvalidate: () -> Unit
) {
    private val paint get() = drawing.paint
    private val stroke get() = drawing.stroke
    private val iconPath get() = drawing.iconPath
    private val state get() = drawing.state
    private val context get() = drawing.context
    private val thumbnails = ThumbnailLoader(onInvalidate)
    private val fileMetadata = hashMapOf<String, FileMetadata>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    val fileHits = mutableListOf<FileHit>()
    var maxScroll = 0f
        private set

    fun draw(canvas: Canvas) {
        fileHits.clear()
        thumbnails.setLoadingDeferred(state.deferPreviews)
        val top = drawing.topBarBottom
        val bottom = drawing.contentBottom
        val tab = state.tabs[state.activeTab]
        canvas.save()
        canvas.clipRect(drawing.contentLeft, top, drawing.contentRight, bottom)
        val contentProgress = state.dockMotion.contentProgress.coerceIn(0f, 1f)
        val animateContent = !state.directoryTransitioning && contentProgress < 1f
        if (animateContent) {
            canvas.translate(dp(12f) * state.dockMotion.direction * (1f - contentProgress), 0f)
            canvas.saveLayerAlpha(
                drawing.contentLeft - dp(16f), top, drawing.contentRight + dp(16f), bottom,
                (255f * contentProgress).toInt().coerceIn(0, 255)
            )
        }
        when {
            !state.canAccessStorage -> {
                drawEmpty(
                    canvas,
                    context.getString(R.string.storage_permission_required),
                    context.getString(R.string.storage_permission_hint)
                )
                maxScroll = 0f
            }
            !state.canReadDirectory -> {
                drawEmpty(canvas, context.getString(R.string.cannot_read_directory), tab.directory.absolutePath)
                maxScroll = 0f
            }
            state.items.isEmpty() -> {
                drawEmpty(
                    canvas,
                    context.getString(R.string.empty_directory),
                    context.getString(R.string.empty_directory_hint)
                )
                maxScroll = 0f
            }
            state.appearance.layoutMode == LayoutMode.LIST -> drawList(canvas, top, bottom)
            else -> drawGrid(canvas, top, bottom)
        }
        // The previous directory remains as a visual snapshot until the async list is
        // committed. It must not remain a hit target for the newly active directory.
        if (state.directoryTransitioning) fileHits.clear()
        if (animateContent) {
            fileHits.clear()
            canvas.restore()
        }
        canvas.restore()
    }

    fun close() = thumbnails.close()

    fun onDirectoryContentsChanged() {
        fileMetadata.clear()
        thumbnails.onDirectoryContentsChanged()
    }

    fun fileAt(x: Float, y: Float): File? = fileHits.lastOrNull { it.rect.contains(x, y) }?.file

    fun isDirectory(file: File): Boolean = metadata(file).directory

    fun selectionHandleFile(x: Float, y: Float): File? {
        if (!state.multiSelect) return null
        val hit = fileHits.lastOrNull { it.rect.contains(x, y) } ?: return null
        val inHandle = if (state.appearance.layoutMode == LayoutMode.LIST) {
            x >= hit.rect.right - dp(52f)
        } else {
            x >= hit.rect.right - dp(44f) && y <= hit.rect.top + dp(44f)
        }
        return hit.file.takeIf { inHandle }
    }

    fun scrollToReveal(file: File, currentScroll: Float): Float {
        val index = state.items.indexOf(file)
        if (index < 0) return currentScroll
        val viewportTop = drawing.topBarBottom
        val viewportBottom = drawing.contentBottom
        val itemTop: Float
        val itemBottom: Float
        if (state.appearance.layoutMode == LayoutMode.LIST) {
            val rowHeight = dp(max(54, state.appearance.iconDp + state.appearance.spacingDp * 2).toFloat())
            itemTop = viewportTop + dp(5f) + index * rowHeight - currentScroll
            itemBottom = itemTop + rowHeight
        } else {
            val availableWidth = drawing.contentRight - drawing.contentLeft
            val minCell = dp(max(124, state.appearance.iconDp * 2 + 44).toFloat())
            val columns = max(1, (availableWidth / minCell).toInt())
            val cellHeight = dp(max(state.appearance.iconDp + 54, 108).toFloat())
            val rowStride = cellHeight + dp(state.appearance.spacingDp.toFloat())
            itemTop = viewportTop + dp(10f) + (index / columns) * rowStride - currentScroll
            itemBottom = itemTop + cellHeight
        }
        return when {
            itemTop < viewportTop -> currentScroll - (viewportTop - itemTop)
            itemBottom > viewportBottom -> currentScroll + (itemBottom - viewportBottom)
            else -> currentScroll
        }.coerceIn(0f, maxScroll)
    }

    private fun drawEmpty(canvas: Canvas, title: String, detail: String) {
        val centerX = (drawing.contentLeft + drawing.contentRight) / 2f
        val cy = (drawing.topBarBottom + drawing.contentBottom) / 2
        icons.drawFolder(canvas, centerX - dp(27f), cy - dp(65f), dp(54f), color("muted"))
        val bounds = RectF(
            drawing.contentLeft + dp(16f),
            drawing.topBarBottom,
            drawing.contentRight - dp(16f),
            drawing.contentBottom
        )
        overflow(canvas, "static:empty-title", title, bounds, cy + dp(9f), 18f, color("text"), Paint.Align.CENTER, true, false)
        overflow(canvas, "static:empty-detail", detail, bounds, cy + dp(42f), 13f, color("muted"), Paint.Align.CENTER, false, false)
    }

    private fun drawList(canvas: Canvas, top: Float, bottom: Float) {
        val appearance = state.appearance
        val rowHeight = dp(max(54, appearance.iconDp + appearance.spacingDp * 2).toFloat())
        val startY = top + dp(5f) - state.scrollY
        val firstIndex = ((top - startY) / rowHeight).toInt().coerceIn(state.items.indices)
        val lastIndex = ((bottom - startY) / rowHeight).toInt().coerceIn(state.items.indices)
        for (index in firstIndex..lastIndex) {
            val file = state.items[index]
            val row = RectF(
                drawing.contentLeft + dp(8f),
                startY + index * rowHeight,
                drawing.contentRight - dp(8f),
                startY + (index + 1) * rowHeight
            )
            if (row.bottom < top || row.top > bottom) continue
            val metadata = metadata(file)
            RectF(row).takeIf {
                it.intersect(drawing.contentLeft, top, drawing.contentRight, bottom)
            }?.let { fileHits += FileHit(file, it) }
            if (file.absolutePath in state.selected) drawSelection(canvas, row)
            val iconSize = dp(appearance.iconDp.toFloat())
            val ix = row.left + dp(12f)
            val iy = row.centerY() - iconSize / 2
            val visualType = if (metadata.directory) null else icons.visualType(file)
            if (metadata.directory) {
                icons.drawFolder(canvas, ix, iy, iconSize, Color.rgb(245, 176, 65))
            } else if (!drawPreview(canvas, file, RectF(ix, iy, ix + iconSize, iy + iconSize))) {
                icons.drawFileType(canvas, ix, iy, iconSize, visualType!!, file.extension)
            }
            val tx = ix + iconSize + dp(15f)
            val nameRight = row.right - if (state.multiSelect) dp(45f) else dp(10f)
            overflow(
                canvas, drawing.overflow.fileKey(file), file.name,
                RectF(tx, row.top, nameRight, row.centerY() + dp(1f)),
                row.centerY() - dp(8f), appearance.textSp.toFloat(), color("text"),
                Paint.Align.LEFT, metadata.directory, file.absolutePath in state.selected
            )
            val timestamp = dateFormat.format(Date(metadata.lastModified))
            val detail = if (metadata.directory) context.getString(R.string.folder_detail, timestamp)
            else context.getString(
                R.string.file_detail_with_type,
                icons.typeLabel(visualType!!),
                FileSizeFormatter.format(metadata.size),
                timestamp
            )
            overflow(
                canvas, "static:file-detail:${file.absolutePath}", detail,
                RectF(tx, row.centerY(), nameRight, row.bottom), row.centerY() + dp(14f),
                11f, if (metadata.directory) color("muted") else color("text"),
                Paint.Align.LEFT, false, false
            )
            if (state.multiSelect) {
                if (file.absolutePath in state.selected) drawCheck(canvas, row.right - dp(21f), row.centerY())
                else drawEmptyCheck(canvas, row.right - dp(21f), row.centerY())
            }
        }
        maxScroll = max(0f, state.items.size * rowHeight + dp(10f) - (bottom - top))
    }

    private fun drawGrid(canvas: Canvas, top: Float, bottom: Float) {
        val appearance = state.appearance
        val availableWidth = drawing.contentRight - drawing.contentLeft
        val minCell = dp(max(124, appearance.iconDp * 2 + 44).toFloat())
        val cols = max(1, (availableWidth / minCell).toInt())
        val cellW = availableWidth / cols
        val cellH = dp(max(appearance.iconDp + 54, 108).toFloat())
        val rowGap = dp(appearance.spacingDp.toFloat())
        val rowStride = cellH + rowGap
        val startY = top + dp(10f) - state.scrollY
        val rows = ceil(state.items.size / cols.toFloat()).toInt()
        val firstRow = ((top - startY) / rowStride).toInt().coerceIn(0, rows - 1)
        val lastRow = ((bottom - startY) / rowStride).toInt().coerceIn(0, rows - 1)
        val firstIndex = firstRow * cols
        val lastIndexExclusive = min(state.items.size, (lastRow + 1) * cols)
        for (index in firstIndex until lastIndexExclusive) {
            val file = state.items[index]
            val col = index % cols
            val rowIndex = index / cols
            val rowTop = startY + rowIndex * rowStride
            val rect = RectF(
                drawing.contentLeft + col * cellW + dp(6f),
                rowTop,
                drawing.contentLeft + (col + 1) * cellW - dp(6f),
                rowTop + cellH
            )
            if (rect.bottom < top || rect.top > bottom) continue
            val metadata = metadata(file)
            RectF(rect).takeIf {
                it.intersect(drawing.contentLeft, top, drawing.contentRight, bottom)
            }?.let { fileHits += FileHit(file, it) }
            if (file.absolutePath in state.selected) drawSelection(canvas, rect)
            val iconSize = dp(appearance.iconDp.toFloat())
            val previewBottom = min(rect.top + dp(74f), rect.bottom - dp(38f))
            val previewRect = RectF(rect.left + dp(8f), rect.top + dp(8f), rect.right - dp(8f), previewBottom)
            val hasPreview = !metadata.directory && drawPreview(canvas, file, previewRect)
            val visualType = if (metadata.directory) null else icons.visualType(file)
            val iconY = if (thumbnails.isPreviewable(file)) previewRect.centerY() - iconSize / 2
            else rect.top + dp(12f)
            if (!hasPreview) {
                val ix = rect.centerX() - iconSize / 2
                if (metadata.directory) {
                    icons.drawFolder(canvas, ix, iconY, iconSize, Color.rgb(245, 176, 65))
                } else {
                    icons.drawFileType(canvas, ix, iconY, iconSize, visualType!!, file.extension)
                }
            }
            overflow(
                canvas, drawing.overflow.fileKey(file), file.name,
                RectF(rect.left + dp(7f), rect.bottom - dp(44f), rect.right - dp(7f), rect.bottom - dp(17f)),
                rect.bottom - dp(29f), appearance.textSp.toFloat(), color("text"),
                Paint.Align.CENTER, metadata.directory, file.absolutePath in state.selected
            )
            if (!metadata.directory) {
                overflow(
                    canvas,
                    "static:file-size:${file.absolutePath}",
                    context.getString(
                        R.string.file_grid_detail,
                        icons.typeLabel(visualType!!),
                        FileSizeFormatter.format(metadata.size)
                    ),
                    RectF(rect.left + dp(7f), rect.bottom - dp(20f), rect.right - dp(7f), rect.bottom),
                    rect.bottom - dp(10f), 10f, color("text"), Paint.Align.CENTER, false, false
                )
            }
            if (state.multiSelect) {
                if (file.absolutePath in state.selected) drawCheck(canvas, rect.right - dp(13f), rect.top + dp(14f))
                else drawEmptyCheck(canvas, rect.right - dp(13f), rect.top + dp(14f))
            }
        }
        val contentHeight = rows * cellH + max(0, rows - 1) * rowGap
        maxScroll = max(0f, contentHeight + dp(20f) - (bottom - top))
    }

    private fun drawSelection(canvas: Canvas, rect: RectF) {
        paint.color = color("selected")
        paint.alpha = if (state.multiSelect) 210 else 145
        canvas.drawRoundRect(rect, dp(9f), dp(9f), paint)
        paint.alpha = 255
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = color("primary")
        canvas.drawCircle(cx, cy, dp(10f), paint)
        stroke.color = Color.WHITE
        stroke.strokeWidth = dp(2f)
        stroke.strokeCap = Paint.Cap.ROUND
        iconPath.reset()
        iconPath.moveTo(cx - dp(4f), cy)
        iconPath.lineTo(cx - dp(1f), cy + dp(3f))
        iconPath.lineTo(cx + dp(5f), cy - dp(4f))
        canvas.drawPath(iconPath, stroke)
    }

    private fun drawEmptyCheck(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = color("surface")
        paint.alpha = 210
        canvas.drawCircle(cx, cy, dp(10f), paint)
        paint.alpha = 255
        stroke.color = color("muted")
        stroke.alpha = 125
        stroke.strokeWidth = dp(1.4f)
        canvas.drawCircle(cx, cy, dp(9f), stroke)
        stroke.alpha = 255
    }

    private fun drawPreview(canvas: Canvas, file: File, destination: RectF): Boolean {
        if (!thumbnails.isPreviewable(file)) return false
        val bitmap = thumbnails.get(
            file,
            destination.width().toInt(),
            destination.height().toInt()
        ) ?: return false
        val sourceRatio = bitmap.width.toFloat() / bitmap.height
        val targetRatio = destination.width() / destination.height()
        val source = if (sourceRatio > targetRatio) {
            val wanted = (bitmap.height * targetRatio).toInt()
            val left = (bitmap.width - wanted) / 2
            Rect(left, 0, left + wanted, bitmap.height)
        } else {
            val wanted = (bitmap.width / targetRatio).toInt()
            val top = (bitmap.height - wanted) / 2
            Rect(0, top, bitmap.width, top + wanted)
        }
        canvas.save()
        iconPath.reset()
        iconPath.addRoundRect(destination, dp(8f), dp(8f), Path.Direction.CW)
        canvas.clipPath(iconPath)
        paint.alpha = 255
        canvas.drawBitmap(bitmap, source, destination, paint)
        canvas.restore()
        if (thumbnails.isVideo(file)) {
            paint.color = 0xAA111827.toInt()
            canvas.drawCircle(destination.centerX(), destination.centerY(), dp(13f), paint)
            paint.color = Color.WHITE
            iconPath.reset()
            iconPath.moveTo(destination.centerX() - dp(4f), destination.centerY() - dp(7f))
            iconPath.lineTo(destination.centerX() + dp(7f), destination.centerY())
            iconPath.lineTo(destination.centerX() - dp(4f), destination.centerY() + dp(7f))
            iconPath.close()
            canvas.drawPath(iconPath, paint)
        }
        return true
    }

    private fun overflow(
        canvas: Canvas,
        key: String,
        value: String,
        bounds: RectF,
        centerY: Float,
        sizeSp: Float,
        tint: Int,
        align: Paint.Align,
        bold: Boolean,
        animate: Boolean
    ) = drawing.overflow.draw(canvas, key, value, bounds, centerY, sizeSp, tint, align, bold, animate)

    private fun metadata(file: File): FileMetadata = fileMetadata.getOrPut(file.absolutePath) {
        FileMetadata(file.isDirectory, file.length(), file.lastModified())
    }

    private fun dp(value: Float) = drawing.dp(value)

    private fun color(name: String) = drawing.color(name)

    private data class FileMetadata(
        val directory: Boolean,
        val size: Long,
        val lastModified: Long
    )
}
