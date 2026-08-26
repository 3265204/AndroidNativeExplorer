package com.ane.filemanager.ui.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.SystemClock
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.PluginFileIcon
import com.ane.filemanager.ui.model.FileHit
import com.ane.filemanager.ui.model.LayoutMode
import com.ane.filemanager.ui.model.MenuHit
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.RenderState
import com.ane.filemanager.ui.model.TabHit
import com.ane.filemanager.ui.theme.AppThemePalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Canvas-only renderer. It owns visual metrics and hit regions, but never mutates browser state. */
internal class FileManagerRenderer(
    private val context: Context,
    private val pluginFileIcon: (File) -> PluginFileIcon?,
    private val onInvalidate: () -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPath = Path()
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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val thumbnails = ThumbnailLoader(onInvalidate)
    private val fileMetadata = hashMapOf<String, FileMetadata>()
    private val marqueeStarts = mutableMapOf<String, Long>()
    private var resolvedPalette: AppThemePalette? = null
    private lateinit var state: RenderState
    private var width = 0
    private var height = 0
    private val contentLeft get() = state.insets.left.toFloat()
    private val contentRight get() = width - state.insets.right.toFloat()
    private val topBarTop get() = state.insets.top.toFloat()
    private val topBarBottom get() = topBarTop + topHeight

    val fileHits = mutableListOf<FileHit>()
    val tabHits = mutableListOf<TabHit>()
    val menuHits = mutableListOf<MenuHit>()
    var maxScroll = 0f
        private set
    var maxDockScroll = 0f
        private set

    val topHeight get() = dp(56f)
    val bottomHeight get() = dp(58f)
    val fabOffset get() = dp(39f)
    val appMenuHitWidth get() = dp(50f)
    val navigateUpHitWidth get() = dp(90f)
    fun isSortButton(viewWidth: Int, x: Float, rightInset: Int = 0): Boolean =
        x >= viewWidth - rightInset - dp(58f)
    fun contentBottom(viewHeight: Int, bottomInset: Int = 0) = viewHeight - bottomInset - bottomHeight

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

    fun fileAt(x: Float, y: Float): File? = fileHits.lastOrNull { it.rect.contains(x, y) }?.file

    fun restartFileMarquee(file: File) {
        marqueeStarts[fileMarqueeKey(file)] = MARQUEE_RESTART_PENDING
    }

    fun restartTabMarquee(index: Int) {
        marqueeStarts[tabMarqueeKey(index)] = MARQUEE_RESTART_PENDING
    }

    fun scrollToRevealTab(index: Int, currentScroll: Float): Float {
        val rect = tabHits.firstOrNull { it.index == index }?.rect ?: return currentScroll
        return when {
            rect.left < contentLeft -> currentScroll - (contentLeft - rect.left)
            rect.right > contentRight -> currentScroll + (rect.right - contentRight)
            else -> currentScroll
        }.coerceIn(0f, maxDockScroll)
    }

    fun scrollToRevealFile(file: File, currentScroll: Float): Float {
        val index = state.items.indexOf(file)
        if (index < 0) return currentScroll
        val viewportTop = topBarBottom
        val viewportBottom = contentBottom(height, state.insets.bottom)
        val itemTop: Float
        val itemBottom: Float
        if (state.appearance.layoutMode == LayoutMode.LIST) {
            val rowHeight = dp(max(54, state.appearance.iconDp + state.appearance.spacingDp * 2).toFloat())
            itemTop = viewportTop + dp(5f) + index * rowHeight - currentScroll
            itemBottom = itemTop + rowHeight
        } else {
            val availableWidth = contentRight - contentLeft
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

    fun draw(canvas: Canvas, renderState: RenderState) {
        state = renderState
        width = canvas.width
        height = canvas.height
        thumbnails.setLoadingDeferred(state.deferPreviews)
        canvas.drawColor(color("bg"))
        drawTopBar(canvas)
        drawFiles(canvas)
        drawTabs(canvas)
        if (state.dragging) drawDragPreview(canvas)
        drawFab(canvas)
        if (state.menuKind != MenuKind.NONE) drawMenu(canvas)
        state.busyText?.let { drawBusy(canvas, it) }
    }

    fun color(name: String): Int = when (name) {
        "bg" -> palette().background
        "surface" -> palette().surface
        "surface2" -> palette().surface2
        "text" -> palette().text
        "muted" -> palette().muted
        "line" -> palette().outline
        "selected" -> palette().selected
        "primary" -> palette().primary
        else -> Color.MAGENTA
    }

    fun surfaceColor(dark: Boolean): Int = AppThemePalette.resolve(context, dark).surface

    private fun palette(): AppThemePalette {
        val current = resolvedPalette
        if (current != null && current.dark == state.appearance.dark) return current
        return AppThemePalette.resolve(context, state.appearance.dark).also { resolvedPalette = it }
    }

    fun close() {
        thumbnails.close()
    }

    fun onDirectoryContentsChanged() {
        fileMetadata.clear()
        thumbnails.onDirectoryContentsChanged()
    }

    private fun text(
        canvas: Canvas, value: String, x: Float, y: Float, sizeSp: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sizeSp * context.resources.displayMetrics.scaledDensity
        paint.textAlign = align
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        canvas.drawText(value, x, y - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawTopBar(canvas: Canvas) {
        val tab = state.tabs[state.activeTab]
        val centerY = (topBarTop + topBarBottom) / 2f
        val menuX = contentLeft + dp(25f)
        val upX = contentLeft + dp(70f)
        paint.color = color("surface")
        canvas.drawRect(contentLeft, topBarTop, contentRight, topBarBottom, paint)
        paint.color = color("line")
        canvas.drawRect(contentLeft, topBarBottom - dp(1f), contentRight, topBarBottom, paint)
        canvas.save()
        canvas.clipRect(contentLeft, topBarTop, contentRight, topBarBottom)
        text(canvas, context.getString(R.string.app_menu_symbol), menuX, centerY, 23f, color("text"), Paint.Align.CENTER)
        text(canvas, context.getString(R.string.navigate_up_symbol), upX, centerY, 23f,
            if (tab.directory.parentFile != null) color("text") else color("muted"), Paint.Align.CENTER)
        val title = if (state.multiSelect) {
            context.getString(R.string.multi_select_count, state.selected.size)
        } else tab.directory.absolutePath
        val verticalPad = dp(8f)
        val sortX = contentRight - dp(29f)
        val addressRight = (contentRight - dp(62f)).coerceAtLeast(contentLeft)
        val addressLeft = (contentLeft + dp(90f)).coerceAtMost(addressRight)
        val address = RectF(addressLeft, topBarTop + verticalPad, addressRight, topBarBottom - verticalPad)
        paint.color = color("surface2")
        canvas.drawRoundRect(address, dp(10f), dp(10f), paint)
        stroke.color = color("line"); stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(address, dp(10f), dp(10f), stroke)
        val horizontalPad = min(dp(14f), address.width() / 3f)
        overflowText(canvas, "static:address", title,
            RectF(address.left + horizontalPad, address.top, address.right - horizontalPad, address.bottom),
            centerY, if (state.multiSelect) 15f else 14f, color("text"),
            Paint.Align.LEFT, state.multiSelect, false)
        text(canvas, context.getString(R.string.sort_symbol), sortX, centerY, 22f,
            color("text"), Paint.Align.CENTER)
        canvas.restore()
    }

    private fun drawFiles(canvas: Canvas) {
        fileHits.clear()
        val top = topBarBottom
        val bottom = contentBottom(height, state.insets.bottom)
        val tab = state.tabs[state.activeTab]
        canvas.save()
        canvas.clipRect(contentLeft, top, contentRight, bottom)
        when {
            !state.canAccessStorage -> {
                drawEmpty(canvas, context.getString(R.string.storage_permission_required),
                    context.getString(R.string.storage_permission_hint)); maxScroll = 0f
            }
            !state.canReadDirectory -> {
                drawEmpty(canvas, context.getString(R.string.cannot_read_directory), tab.directory.absolutePath); maxScroll = 0f
            }
            state.items.isEmpty() -> {
                drawEmpty(canvas, context.getString(R.string.empty_directory),
                    context.getString(R.string.empty_directory_hint)); maxScroll = 0f
            }
            state.appearance.layoutMode == LayoutMode.LIST -> drawList(canvas, top, bottom)
            else -> drawGrid(canvas, top, bottom)
        }
        // The previous directory remains as a visual snapshot until the async list is
        // committed. It must not remain a hit target for the newly active directory.
        if (state.directoryTransitioning) fileHits.clear()
        canvas.restore()
    }

    private fun drawEmpty(canvas: Canvas, title: String, detail: String) {
        val centerX = (contentLeft + contentRight) / 2f
        val cy = (topBarBottom + contentBottom(height, state.insets.bottom)) / 2
        drawFolderIcon(canvas, centerX - dp(27f), cy - dp(65f), dp(54f), color("muted"))
        val bounds = RectF(contentLeft + dp(16f), topBarBottom,
            contentRight - dp(16f), contentBottom(height, state.insets.bottom))
        overflowText(canvas, "static:empty-title", title, bounds, cy + dp(9f),
            18f, color("text"), Paint.Align.CENTER, true, false)
        overflowText(canvas, "static:empty-detail", detail, bounds, cy + dp(42f),
            13f, color("muted"), Paint.Align.CENTER, false, false)
    }

    private fun drawList(canvas: Canvas, top: Float, bottom: Float) {
        val appearance = state.appearance
        val rowHeight = dp(max(54, appearance.iconDp + appearance.spacingDp * 2).toFloat())
        val startY = top + dp(5f) - state.scrollY
        val firstIndex = ((top - startY) / rowHeight).toInt().coerceIn(state.items.indices)
        val lastIndex = ((bottom - startY) / rowHeight).toInt().coerceIn(state.items.indices)
        for (index in firstIndex..lastIndex) {
            val file = state.items[index]
            val row = RectF(contentLeft + dp(8f), startY + index * rowHeight,
                contentRight - dp(8f), startY + (index + 1) * rowHeight)
            if (row.bottom < top || row.top > bottom) continue
            val metadata = metadata(file)
            RectF(row).takeIf { it.intersect(contentLeft, top, contentRight, bottom) }
                ?.let { fileHits += FileHit(file, it) }
            if (file.absolutePath in state.selected) {
                drawSelection(canvas, row)
            }
            val iconSize = dp(appearance.iconDp.toFloat())
            val ix = row.left + dp(12f)
            val iy = row.centerY() - iconSize / 2
            if (metadata.directory) {
                drawFolderIcon(canvas, ix, iy, iconSize, Color.rgb(245, 176, 65))
            } else if (pluginFileIcon(file) == PluginFileIcon.ARCHIVE) {
                drawArchiveIcon(canvas, ix, iy, iconSize)
            } else if (!drawPreview(canvas, file, RectF(ix, iy, ix + iconSize, iy + iconSize))) {
                drawFileIcon(canvas, ix, iy, iconSize, fileColor(file))
            }
            val tx = ix + iconSize + dp(15f)
            val nameRight = row.right - if (state.multiSelect) dp(45f) else dp(10f)
            overflowText(canvas, fileMarqueeKey(file), file.name,
                RectF(tx, row.top, nameRight, row.centerY() + dp(1f)),
                row.centerY() - dp(8f), appearance.textSp.toFloat(), color("text"),
                Paint.Align.LEFT, metadata.directory, file.absolutePath in state.selected)
            val timestamp = dateFormat.format(Date(metadata.lastModified))
            val detail = if (metadata.directory) context.getString(R.string.folder_detail, timestamp)
            else context.getString(R.string.file_detail, FileSizeFormatter.format(metadata.size), timestamp)
            overflowText(canvas, "static:file-detail:${file.absolutePath}", detail,
                RectF(tx, row.centerY(), nameRight, row.bottom), row.centerY() + dp(14f),
                11f, color("muted"), Paint.Align.LEFT, false, false)
            if (state.multiSelect) {
                if (file.absolutePath in state.selected) drawCheck(canvas, row.right - dp(21f), row.centerY())
                else drawEmptyCheck(canvas, row.right - dp(21f), row.centerY())
            }
        }
        maxScroll = max(0f, state.items.size * rowHeight + dp(10f) - (bottom - top))
    }

    private fun drawGrid(canvas: Canvas, top: Float, bottom: Float) {
        val appearance = state.appearance
        val availableWidth = contentRight - contentLeft
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
            val rect = RectF(contentLeft + col * cellW + dp(6f), rowTop,
                contentLeft + (col + 1) * cellW - dp(6f), rowTop + cellH)
            if (rect.bottom < top || rect.top > bottom) continue
            val metadata = metadata(file)
            RectF(rect).takeIf { it.intersect(contentLeft, top, contentRight, bottom) }
                ?.let { fileHits += FileHit(file, it) }
            if (file.absolutePath in state.selected) {
                drawSelection(canvas, rect)
            }
            val iconSize = dp(appearance.iconDp.toFloat())
            val previewBottom = min(rect.top + dp(74f), rect.bottom - dp(38f))
            val previewRect = RectF(rect.left + dp(8f), rect.top + dp(8f), rect.right - dp(8f), previewBottom)
            val hasPreview = !metadata.directory && drawPreview(canvas, file, previewRect)
            val iconY = if (thumbnails.isPreviewable(file)) previewRect.centerY() - iconSize / 2
            else rect.top + dp(12f)
            if (!hasPreview) {
                val ix = rect.centerX() - iconSize / 2
                if (metadata.directory) {
                    drawFolderIcon(canvas, ix, iconY, iconSize, Color.rgb(245, 176, 65))
                } else if (pluginFileIcon(file) == PluginFileIcon.ARCHIVE) {
                    drawArchiveIcon(canvas, ix, iconY, iconSize)
                } else {
                    drawFileIcon(canvas, ix, iconY, iconSize, fileColor(file))
                }
            }
            overflowText(canvas, fileMarqueeKey(file), file.name,
                RectF(rect.left + dp(7f), rect.bottom - dp(44f), rect.right - dp(7f), rect.bottom - dp(17f)),
                rect.bottom - dp(29f), appearance.textSp.toFloat(), color("text"),
                Paint.Align.CENTER, metadata.directory, file.absolutePath in state.selected)
            if (!metadata.directory) overflowText(canvas, "static:file-size:${file.absolutePath}",
                FileSizeFormatter.format(metadata.size),
                RectF(rect.left + dp(7f), rect.bottom - dp(20f), rect.right - dp(7f), rect.bottom),
                rect.bottom - dp(10f), 10f, color("muted"), Paint.Align.CENTER, false, false)
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
        paint.color = color("primary"); canvas.drawCircle(cx, cy, dp(10f), paint)
        stroke.color = Color.WHITE; stroke.strokeWidth = dp(2f); stroke.strokeCap = Paint.Cap.ROUND
        iconPath.reset(); iconPath.moveTo(cx - dp(4f), cy); iconPath.lineTo(cx - dp(1f), cy + dp(3f));
        iconPath.lineTo(cx + dp(5f), cy - dp(4f)); canvas.drawPath(iconPath, stroke)
    }

    private fun drawEmptyCheck(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = color("surface")
        paint.alpha = 210
        canvas.drawCircle(cx, cy, dp(10f), paint)
        paint.alpha = 255
        stroke.color = color("muted"); stroke.alpha = 125; stroke.strokeWidth = dp(1.4f)
        canvas.drawCircle(cx, cy, dp(9f), stroke)
        stroke.alpha = 255
    }

    private fun drawFolderIcon(canvas: Canvas, x: Float, y: Float, size: Float, tint: Int) {
        paint.color = tint
        canvas.drawRoundRect(RectF(x, y + size * .25f, x + size, y + size * .88f), size * .1f, size * .1f, paint)
        canvas.drawRoundRect(RectF(x + size * .08f, y + size * .13f, x + size * .53f, y + size * .43f),
            size * .08f, size * .08f, paint)
        paint.color = blend(tint, Color.WHITE, .16f)
        canvas.drawRoundRect(RectF(x, y + size * .38f, x + size, y + size * .92f), size * .09f, size * .09f, paint)
    }

    private fun drawFileIcon(canvas: Canvas, x: Float, y: Float, size: Float, tint: Int) {
        paint.color = tint
        iconPath.reset(); iconPath.moveTo(x + size * .13f, y + size * .05f); iconPath.lineTo(x + size * .66f, y + size * .05f)
        iconPath.lineTo(x + size * .9f, y + size * .29f); iconPath.lineTo(x + size * .9f, y + size * .95f)
        iconPath.lineTo(x + size * .13f, y + size * .95f); iconPath.close(); canvas.drawPath(iconPath, paint)
        paint.color = blend(tint, Color.WHITE, .35f)
        iconPath.reset(); iconPath.moveTo(x + size * .66f, y + size * .05f); iconPath.lineTo(x + size * .66f, y + size * .3f)
        iconPath.lineTo(x + size * .9f, y + size * .3f); iconPath.close(); canvas.drawPath(iconPath, paint)
        paint.color = blend(tint, Color.WHITE, .55f)
        canvas.drawRoundRect(RectF(x + size * .27f, y + size * .52f, x + size * .74f, y + size * .58f), dp(1f), dp(1f), paint)
        canvas.drawRoundRect(RectF(x + size * .27f, y + size * .67f, x + size * .67f, y + size * .73f), dp(1f), dp(1f), paint)
    }

    private fun drawArchiveIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val left = x + size * .08f
        val right = x + size * .92f
        val radius = size * .07f
        val layers = intArrayOf(
            Color.rgb(147, 83, 191),
            Color.rgb(49, 132, 191),
            Color.rgb(44, 155, 117)
        )
        layers.forEachIndexed { index, color ->
            val top = y + size * (.08f + index * .27f)
            paint.color = color
            canvas.drawRoundRect(
                RectF(left, top, right, top + size * .24f),
                radius,
                radius,
                paint
            )
            paint.color = blend(color, Color.WHITE, .22f)
            canvas.drawRoundRect(
                RectF(left + size * .05f, top + size * .04f, right - size * .05f, top + size * .08f),
                radius / 2f,
                radius / 2f,
                paint
            )
        }
        paint.color = Color.rgb(92, 67, 48)
        canvas.drawRoundRect(
            RectF(x + size * .44f, y + size * .05f, x + size * .61f, y + size * .92f),
            size * .035f,
            size * .035f,
            paint
        )
        paint.color = Color.rgb(245, 190, 67)
        canvas.drawRoundRect(
            RectF(x + size * .405f, y + size * .67f, x + size * .645f, y + size * .84f),
            size * .04f,
            size * .04f,
            paint
        )
        paint.color = Color.rgb(92, 67, 48)
        canvas.drawRoundRect(
            RectF(x + size * .47f, y + size * .71f, x + size * .58f, y + size * .80f),
            size * .02f,
            size * .02f,
            paint
        )
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
            paint.color = 0xAA111827.toInt(); canvas.drawCircle(destination.centerX(), destination.centerY(), dp(13f), paint)
            paint.color = Color.WHITE
            iconPath.reset(); iconPath.moveTo(destination.centerX() - dp(4f), destination.centerY() - dp(7f))
            iconPath.lineTo(destination.centerX() + dp(7f), destination.centerY())
            iconPath.lineTo(destination.centerX() - dp(4f), destination.centerY() + dp(7f)); iconPath.close()
            canvas.drawPath(iconPath, paint)
        }
        return true
    }

    private fun fileColor(file: File): Int = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif" -> Color.rgb(168, 85, 247)
        "mp4", "mkv", "mov", "avi" -> Color.rgb(236, 72, 153)
        "mp3", "wav", "flac", "m4a" -> Color.rgb(20, 184, 166)
        "zip", "rar", "7z", "gz" -> Color.rgb(245, 158, 11)
        "pdf" -> Color.rgb(239, 68, 68)
        "txt", "md", "json", "xml", "kt", "java" -> Color.rgb(59, 130, 246)
        else -> Color.rgb(100, 116, 139)
    }

    private fun metadata(file: File): FileMetadata = fileMetadata.getOrPut(file.absolutePath) {
        FileMetadata(file.isDirectory, file.length(), file.lastModified())
    }

    private data class FileMetadata(
        val directory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    private fun drawFab(canvas: Canvas) {
        val cx = contentRight - fabOffset
        val cy = contentBottom(height, state.insets.bottom) - fabOffset
        paint.alpha = 255
        canvas.drawBitmap(fabShadow, cx - fabShadow.width / 2f,
            cy - fabShadow.height / 2f + dp(3f), paint)
        val cancelHover = state.dragging && isFab(
            width, height, state.dragX, state.dragY, state.insets.right, state.insets.bottom
        )
        paint.color = if (state.dragging) Color.rgb(239, 68, 68) else color("primary")
        val normalRadius = 26f
        canvas.drawCircle(cx, cy, dp(if (cancelHover) normalRadius + 3f else normalRadius), paint)
        if (state.dragging) {
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(cx - dp(7f), cy - dp(7f), cx + dp(7f), cy + dp(7f)),
                dp(2f), dp(2f), paint)
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
        val label = context.getString(R.string.drag_release_to_cancel)
        val desiredWidth = textWidth(label, 12.5f, true) + dp(24f)
        val availableWidth = (fabX - contentLeft - dp(48f)).coerceAtLeast(0f)
        val hintWidth = min(desiredWidth, availableWidth)
        if (hintWidth <= 0f) return
        val rect = RectF(fabX - dp(38f) - hintWidth, fabY - dp(20f),
            fabX - dp(38f), fabY + dp(20f))
        paint.color = if (state.appearance.dark) 0xEE272F3B.toInt() else 0xEEFFFFFF.toInt()
        canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
        overflowText(canvas, "static:cancel-drag", label,
            RectF(rect.left + dp(10f), rect.top, rect.right - dp(10f), rect.bottom),
            rect.centerY(), 12.5f, color("text"), Paint.Align.CENTER, true, false)
    }

    private fun drawTabs(canvas: Canvas) {
        val bottom = height - state.insets.bottom.toFloat()
        val top = bottom - bottomHeight
        paint.color = color("surface"); canvas.drawRect(contentLeft, top, contentRight, bottom, paint)
        paint.color = color("line"); canvas.drawRect(contentLeft, top, contentRight, top + dp(1f), paint)
        tabHits.clear()
        val viewportWidth = contentRight - contentLeft
        if (viewportWidth <= 0f) {
            maxDockScroll = 0f
            return
        }
        val minTabWidth = min(dp(86f), viewportWidth)
        val maxTabWidth = max(minTabWidth, min(dp(220f), viewportWidth * .72f))
        val widths = state.tabs.map { tab ->
            textWidth(tab.label, 12.5f, false).plus(dp(36f)).coerceIn(minTabWidth, maxTabWidth)
        }.toMutableList()
        val measuredWidth = widths.sum()
        if (measuredWidth < viewportWidth && widths.isNotEmpty()) {
            val extra = (viewportWidth - measuredWidth) / widths.size
            widths.indices.forEach { widths[it] += extra }
        }
        val totalWidth = widths.sum()
        maxDockScroll = max(0f, totalWidth - viewportWidth)
        var logicalLeft = contentLeft
        canvas.save()
        canvas.clipRect(contentLeft, top, contentRight, bottom)
        state.tabs.forEachIndexed { index, tab ->
            val tabWidth = widths[index]
            val rect = RectF(logicalLeft - state.dockScrollX, top,
                logicalLeft + tabWidth - state.dockScrollX, bottom)
            logicalLeft += tabWidth
            if (rect.right >= contentLeft && rect.left <= contentRight) tabHits += TabHit(index, RectF(rect))
            val dragTarget = state.dragging && rect.contains(state.dragX, state.dragY)
            val tabBeingDragged = state.tabDragging && index == state.draggedTabIndex
            if (index == state.activeTab || dragTarget || tabBeingDragged) {
                paint.color = if (dragTarget) color("selected") else color("surface2")
                val lift = if (tabBeingDragged) dp(3f) else 0f
                val highlight = RectF(rect.left + dp(5f), rect.top + dp(8f) - lift,
                    rect.right - dp(5f), rect.bottom - dp(7f) - lift)
                canvas.drawRoundRect(highlight,
                    dp(10f), dp(10f), paint)
                paint.color = color("primary")
                canvas.drawRoundRect(RectF(rect.left + dp(19f), rect.top + dp(5f), rect.right - dp(19f), rect.top + dp(8f)),
                    dp(2f), dp(2f), paint)
                if (tabBeingDragged) {
                    stroke.color = color("primary")
                    stroke.strokeWidth = dp(1.5f)
                    canvas.drawRoundRect(highlight, dp(10f), dp(10f), stroke)
                }
            }
            overflowText(canvas, tabMarqueeKey(index), tab.label,
                RectF(rect.left + dp(13f), rect.top + dp(7f), rect.right - dp(13f), rect.bottom - dp(6f)),
                rect.centerY(), 12.5f, when {
                    index == state.activeTab -> color("primary")
                    tab.pinned -> color("muted")
                    else -> color("text")
                }, Paint.Align.CENTER, index == state.activeTab,
                index == state.activeTab || dragTarget || tabBeingDragged)
        }
        canvas.restore()
    }

    private fun drawDragPreview(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(contentLeft, topBarBottom, contentRight,
            contentBottom(height, state.insets.bottom))
        fileHits.firstOrNull { metadata(it.file).directory && it.rect.contains(state.dragX, state.dragY) }?.let {
            stroke.color = color("primary"); stroke.strokeWidth = dp(3f)
            canvas.drawRoundRect(it.rect, dp(10f), dp(10f), stroke)
        }
        canvas.restore()
        paint.color = 0xD92972D2.toInt()
        val halfWidth = dp(65f)
        val halfHeight = dp(21f)
        val preview = RectF(state.dragX - halfWidth, state.dragY - halfHeight,
            state.dragX + halfWidth, state.dragY + halfHeight)
        canvas.drawRoundRect(preview,
            dp(9f), dp(9f), paint)
        overflowText(canvas, "static:drag-count", context.getString(R.string.move_count, state.dragCount),
            RectF(preview.left + dp(8f), preview.top, preview.right - dp(8f), preview.bottom),
            state.dragY, 13f, Color.WHITE, Paint.Align.CENTER, true, false)
    }

    private fun drawMenu(canvas: Canvas) {
        val progress = state.motion.menuProgress.coerceIn(0f, 1f)
        paint.color = Color.argb((0x44 * progress).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val horizontalMargin = dp(8f)
        val menuWidth = min(dp(216f),
            (contentRight - contentLeft - horizontalMargin * 2f).coerceAtLeast(0f))
        val verticalMargin = dp(8f)
        val panelPadding = dp(14f)
        val availableMenuHeight = (height - state.insets.top - state.insets.bottom - verticalMargin * 2f)
            .coerceAtLeast(0f)
        val itemH = if (state.menuActions.isEmpty()) 0f else min(dp(48f),
            ((availableMenuHeight - panelPadding) / state.menuActions.size).coerceAtLeast(0f))
        val menuHeight = itemH * state.menuActions.size + panelPadding
        val minLeft = contentLeft + horizontalMargin
        val maxLeft = (contentRight - menuWidth - horizontalMargin).coerceAtLeast(minLeft)
        val left = state.menuX.coerceIn(minLeft, maxLeft)
        val minTop = state.insets.top + verticalMargin
        val maxTop = (height - state.insets.bottom - menuHeight - verticalMargin).coerceAtLeast(minTop)
        val top = state.menuY.coerceIn(minTop, maxTop)
        val panel = RectF(left, top, left + menuWidth, top + menuHeight)
        val scale = .84f + .16f * progress
        canvas.save()
        canvas.scale(scale, scale, state.menuOriginX, state.menuOriginY)
        paint.color = Color.BLACK
        paint.alpha = (48 * progress).toInt()
        val shadowPanel = RectF(panel).apply { offset(0f, dp(4f)) }
        canvas.drawRoundRect(shadowPanel, dp(16f), dp(16f), paint)
        paint.color = color("surface")
        paint.alpha = (255 * progress).toInt()
        canvas.drawRoundRect(panel, dp(14f), dp(14f), paint)
        menuHits.clear()
        state.menuActions.forEachIndexed { index, action ->
            val rect = RectF(left + dp(5f), top + dp(7f) + index * itemH,
                left + menuWidth - dp(5f), top + dp(7f) + (index + 1) * itemH)
            menuHits += MenuHit(action, rect)
            overflowText(canvas, "static:menu:$index", action.label,
                RectF(rect.left + dp(17f), rect.top, rect.right - dp(12f), rect.bottom),
                rect.centerY(), 15f, fadeColor(
                    if (action.enabled) color("text") else color("muted"), progress
                ),
                Paint.Align.LEFT, false, false)
        }
        canvas.restore()
        paint.alpha = 255
    }

    private fun drawBusy(canvas: Canvas, message: String) {
        paint.color = 0x66000000; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val halfWidth = min(dp(100f), ((contentRight - contentLeft) / 2f - dp(16f)).coerceAtLeast(0f))
        val centerX = (contentLeft + contentRight) / 2f
        val rect = RectF(centerX - halfWidth, height / 2f - dp(32f),
            centerX + halfWidth, height / 2f + dp(32f))
        paint.color = color("surface"); canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
        val horizontalPad = min(dp(12f), rect.width() / 3f)
        overflowText(canvas, "static:busy", message,
            RectF(rect.left + horizontalPad, rect.top, rect.right - horizontalPad, rect.bottom),
            rect.centerY(), 15f, color("text"), Paint.Align.CENTER, true, false)
    }

    private fun overflowText(
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
        configureText(sizeSp, color, bold)
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
            val gap = dp(32f)
            val cycleDistance = measured + gap
            val now = SystemClock.uptimeMillis()
            val storedStart = marqueeStarts[marqueeKey]
            val startedAt = if (storedStart == null || storedStart == MARQUEE_RESTART_PENDING) {
                marqueeStarts[marqueeKey] = now
                now
            } else storedStart
            val elapsed = (now - startedAt).coerceAtLeast(0L)
            val offset = (elapsed * dp(34f) / 1000f) % cycleDistance
            canvas.drawText(value, bounds.left - offset, baseline, paint)
            canvas.drawText(value, bounds.left - offset + cycleDistance, baseline, paint)
        } else {
            marqueeStarts.remove(marqueeKey)
            canvas.drawText(ellipsizeToWidth(value, available), bounds.left, baseline, paint)
        }
        canvas.restore()
        if (animateOverflow) onInvalidate()
    }

    private fun fileMarqueeKey(file: File) = "file:${file.absolutePath}"

    private fun tabMarqueeKey(index: Int) = "tab:$index"

    private companion object {
        const val MARQUEE_RESTART_PENDING = -1L
    }

    private fun configureText(sizeSp: Float, color: Int, bold: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sizeSp * context.resources.displayMetrics.scaledDensity
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }

    private fun textWidth(value: String, sizeSp: Float, bold: Boolean): Float {
        configureText(sizeSp, Color.WHITE, bold)
        return paint.measureText(value)
    }

    private fun ellipsizeToWidth(value: String, width: Float): String {
        if (paint.measureText(value) <= width) return value
        val suffix = "…"
        var end = value.length
        while (end > 0 && paint.measureText(value, 0, end) + paint.measureText(suffix) > width) end--
        return if (end == 0) suffix else value.substring(0, end) + suffix
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(a) * inverse + Color.red(b) * ratio).toInt(),
            (Color.green(a) * inverse + Color.green(b) * ratio).toInt(),
            (Color.blue(a) * inverse + Color.blue(b) * ratio).toInt()
        )
    }

    private fun fadeColor(color: Int, progress: Float): Int = Color.argb(
        (Color.alpha(color) * progress.coerceIn(0f, 1f)).toInt(),
        Color.red(color), Color.green(color), Color.blue(color)
    )
}
