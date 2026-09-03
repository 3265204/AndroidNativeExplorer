package com.ane.filemanager.ui.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.SystemClock
import com.ane.filemanager.R
import com.ane.filemanager.operation.TransferTargetPolicy
import com.ane.filemanager.plugin.api.PluginFileIcon
import com.ane.filemanager.ui.model.FileHit
import com.ane.filemanager.ui.model.LayoutMode
import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuHit
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.RenderState
import com.ane.filemanager.ui.model.TabHit
import com.ane.filemanager.ui.model.TabMotionStart
import com.ane.filemanager.plugin.api.ui.AneTheme
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
    private var resolvedPalette: AneTheme? = null
    private lateinit var state: RenderState
    private var width = 0
    private var height = 0
    private val contentLeft get() = state.insets.left.toFloat()
    private val contentRight get() = width - state.insets.right.toFloat()
    private val topBarTop get() = state.insets.top.toFloat()
    private val topBarBottom get() = topBarTop + topHeight

    val fileHits = mutableListOf<FileHit>()
    val tabHits = mutableListOf<TabHit>()
    val tabSlotHits = mutableListOf<TabHit>()
    val tabCloseHits = mutableListOf<TabHit>()
    val menuHits = mutableListOf<MenuHit>()
    var maxScroll = 0f
        private set
    var maxDockScroll = 0f
        private set
    private var visualTabStarts = emptyList<TabMotionStart>()

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

    fun tabVisualStarts(): List<TabMotionStart> = visualTabStarts.toList()

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
        if (state.dragReady || state.dragging) drawDragPreview(canvas)
        drawFab(canvas)
        if (state.menuKind != MenuKind.NONE) drawMenu(canvas) else menuHits.clear()
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
        "danger" -> palette().danger
        else -> Color.MAGENTA
    }

    fun surfaceColor(dark: Boolean): Int = AneTheme.resolve(context, dark).surface

    private fun palette(): AneTheme {
        val current = resolvedPalette
        if (current != null && current.dark == state.appearance.dark) return current
        return AneTheme.resolve(context, state.appearance.dark).also { resolvedPalette = it }
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
        drawMenuIcon(canvas, menuX, centerY)
        drawNavigateUpIcon(canvas, upX, centerY,
            if (tab.directory.parentFile != null) color("text") else color("muted"))
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
        drawSortIcon(canvas, sortX, centerY)
        canvas.restore()
    }

    private fun drawMenuIcon(canvas: Canvas, cx: Float, cy: Float) {
        prepareTopBarIconStroke(color("text"))
        val half = dp(9f)
        for (offset in floatArrayOf(-6f, 0f, 6f)) {
            canvas.drawLine(cx - half, cy + dp(offset), cx + half, cy + dp(offset), stroke)
        }
    }

    private fun drawNavigateUpIcon(canvas: Canvas, cx: Float, cy: Float, tint: Int) {
        prepareTopBarIconStroke(tint)
        canvas.drawLine(cx, cy + dp(9f), cx, cy - dp(8f), stroke)
        canvas.drawLine(cx, cy - dp(8f), cx - dp(6f), cy - dp(2f), stroke)
        canvas.drawLine(cx, cy - dp(8f), cx + dp(6f), cy - dp(2f), stroke)
    }

    private fun drawSortIcon(canvas: Canvas, cx: Float, cy: Float) {
        prepareTopBarIconStroke(color("text"))
        canvas.drawLine(cx - dp(9f), cy - dp(6f), cx + dp(9f), cy - dp(6f), stroke)
        canvas.drawLine(cx - dp(6f), cy, cx + dp(6f), cy, stroke)
        canvas.drawLine(cx - dp(3f), cy + dp(6f), cx + dp(3f), cy + dp(6f), stroke)
    }

    private fun prepareTopBarIconStroke(tint: Int) {
        stroke.color = tint
        stroke.alpha = 255
        stroke.strokeWidth = dp(2.1f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.style = Paint.Style.STROKE
    }

    private fun drawFiles(canvas: Canvas) {
        fileHits.clear()
        val top = topBarBottom
        val bottom = contentBottom(height, state.insets.bottom)
        val tab = state.tabs[state.activeTab]
        canvas.save()
        canvas.clipRect(contentLeft, top, contentRight, bottom)
        val contentProgress = state.dockMotion.contentProgress.coerceIn(0f, 1f)
        val animateContent = !state.directoryTransitioning && contentProgress < 1f
        if (animateContent) {
            canvas.translate(dp(12f) * state.dockMotion.direction * (1f - contentProgress), 0f)
            canvas.saveLayerAlpha(
                contentLeft - dp(16f), top, contentRight + dp(16f), bottom,
                (255f * contentProgress).toInt().coerceIn(0, 255)
            )
        }
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
        if (animateContent) {
            fileHits.clear()
            canvas.restore()
        }
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
            val visualType = if (metadata.directory) null else fileVisualType(file)
            if (metadata.directory) {
                drawFolderIcon(canvas, ix, iy, iconSize, Color.rgb(245, 176, 65))
            } else if (!drawPreview(canvas, file, RectF(ix, iy, ix + iconSize, iy + iconSize))) {
                drawFileTypeIcon(canvas, ix, iy, iconSize, visualType!!, file.extension)
            }
            val tx = ix + iconSize + dp(15f)
            val nameRight = row.right - if (state.multiSelect) dp(45f) else dp(10f)
            overflowText(canvas, fileMarqueeKey(file), file.name,
                RectF(tx, row.top, nameRight, row.centerY() + dp(1f)),
                row.centerY() - dp(8f), appearance.textSp.toFloat(), color("text"),
                Paint.Align.LEFT, metadata.directory, file.absolutePath in state.selected)
            val timestamp = dateFormat.format(Date(metadata.lastModified))
            val detail = if (metadata.directory) context.getString(R.string.folder_detail, timestamp)
            else context.getString(
                R.string.file_detail_with_type,
                fileTypeLabel(visualType!!),
                FileSizeFormatter.format(metadata.size),
                timestamp
            )
            overflowText(canvas, "static:file-detail:${file.absolutePath}", detail,
                RectF(tx, row.centerY(), nameRight, row.bottom), row.centerY() + dp(14f),
                11f, if (metadata.directory) color("muted") else color("text"),
                Paint.Align.LEFT, false, false)
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
            val visualType = if (metadata.directory) null else fileVisualType(file)
            val iconY = if (thumbnails.isPreviewable(file)) previewRect.centerY() - iconSize / 2
            else rect.top + dp(12f)
            if (!hasPreview) {
                val ix = rect.centerX() - iconSize / 2
                if (metadata.directory) {
                    drawFolderIcon(canvas, ix, iconY, iconSize, Color.rgb(245, 176, 65))
                } else {
                    drawFileTypeIcon(canvas, ix, iconY, iconSize, visualType!!, file.extension)
                }
            }
            overflowText(canvas, fileMarqueeKey(file), file.name,
                RectF(rect.left + dp(7f), rect.bottom - dp(44f), rect.right - dp(7f), rect.bottom - dp(17f)),
                rect.bottom - dp(29f), appearance.textSp.toFloat(), color("text"),
                Paint.Align.CENTER, metadata.directory, file.absolutePath in state.selected)
            if (!metadata.directory) overflowText(canvas, "static:file-size:${file.absolutePath}",
                context.getString(
                    R.string.file_grid_detail,
                    fileTypeLabel(visualType!!),
                    FileSizeFormatter.format(metadata.size)
                ),
                RectF(rect.left + dp(7f), rect.bottom - dp(20f), rect.right - dp(7f), rect.bottom),
                rect.bottom - dp(10f), 10f, color("text"), Paint.Align.CENTER, false, false)
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
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1.1f), size * .035f)
        stroke.style = Paint.Style.STROKE
        canvas.drawRoundRect(
            RectF(x, y + size * .38f, x + size, y + size * .92f),
            size * .09f,
            size * .09f,
            stroke
        )
    }

    private fun drawFileTypeIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        type: FileVisualType,
        extension: String
    ) {
        when (type) {
            FileVisualType.INSTALLER -> drawInstallerIcon(canvas, x, y, size)
            FileVisualType.AUDIO -> drawAudioIcon(canvas, x, y, size)
            FileVisualType.VIDEO -> drawVideoIcon(canvas, x, y, size)
            FileVisualType.IMAGE -> drawImageIcon(canvas, x, y, size)
            FileVisualType.ARCHIVE -> drawArchiveIcon(canvas, x, y, size)
            FileVisualType.PRESENTATION -> drawPresentationIcon(canvas, x, y, size)
            else -> drawDocumentIcon(canvas, x, y, size, type, extension)
        }
    }

    private fun drawDocumentBase(canvas: Canvas, x: Float, y: Float, size: Float, tint: Int) {
        paint.color = blend(color("surface"), tint, if (state.appearance.dark) .42f else .20f)
        iconPath.reset(); iconPath.moveTo(x + size * .13f, y + size * .05f); iconPath.lineTo(x + size * .66f, y + size * .05f)
        iconPath.lineTo(x + size * .9f, y + size * .29f); iconPath.lineTo(x + size * .9f, y + size * .95f)
        iconPath.lineTo(x + size * .13f, y + size * .95f); iconPath.close(); canvas.drawPath(iconPath, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1f), size * .035f)
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(iconPath, stroke)
        paint.color = color("surface")
        iconPath.reset(); iconPath.moveTo(x + size * .66f, y + size * .05f); iconPath.lineTo(x + size * .66f, y + size * .3f)
        iconPath.lineTo(x + size * .9f, y + size * .3f); iconPath.close(); canvas.drawPath(iconPath, paint)
        stroke.color = color("text")
        canvas.drawLine(x + size * .66f, y + size * .05f, x + size * .9f, y + size * .29f, stroke)
        canvas.drawLine(x + size * .66f, y + size * .05f, x + size * .66f, y + size * .3f, stroke)
    }

    private fun drawDocumentIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        type: FileVisualType,
        extension: String
    ) {
        drawDocumentBase(canvas, x, y, size, fileColor(type))
        val ink = color("text")
        stroke.color = ink
        stroke.strokeWidth = max(dp(1.1f), size * .045f)
        stroke.strokeCap = Paint.Cap.ROUND
        when (type) {
            FileVisualType.TEXT -> {
                for (offset in floatArrayOf(.48f, .62f, .76f)) {
                    canvas.drawLine(x + size * .27f, y + size * offset,
                        x + size * if (offset == .76f) .62f else .75f, y + size * offset, stroke)
                }
            }
            FileVisualType.CODE -> {
                canvas.drawLine(x + size * .43f, y + size * .51f, x + size * .29f, y + size * .64f, stroke)
                canvas.drawLine(x + size * .29f, y + size * .64f, x + size * .43f, y + size * .77f, stroke)
                canvas.drawLine(x + size * .61f, y + size * .51f, x + size * .75f, y + size * .64f, stroke)
                canvas.drawLine(x + size * .75f, y + size * .64f, x + size * .61f, y + size * .77f, stroke)
            }
            FileVisualType.SPREADSHEET -> {
                val grid = RectF(x + size * .27f, y + size * .45f, x + size * .76f, y + size * .8f)
                canvas.drawRect(grid, stroke)
                canvas.drawLine(grid.centerX(), grid.top, grid.centerX(), grid.bottom, stroke)
                canvas.drawLine(grid.left, grid.centerY(), grid.right, grid.centerY(), stroke)
            }
            FileVisualType.DOCUMENT -> {
                paint.color = ink
                canvas.drawRect(x + size * .27f, y + size * .46f, x + size * .39f, y + size * .58f, paint)
                canvas.drawLine(x + size * .46f, y + size * .49f, x + size * .75f, y + size * .49f, stroke)
                canvas.drawLine(x + size * .27f, y + size * .68f, x + size * .75f, y + size * .68f, stroke)
                canvas.drawLine(x + size * .27f, y + size * .79f, x + size * .64f, y + size * .79f, stroke)
            }
            FileVisualType.PDF -> drawIconText(canvas, "PDF", x + size * .52f, y + size * .65f, size * .20f)
            else -> {
                val badge = extension.uppercase().take(3).ifBlank { "FILE" }
                drawIconText(canvas, badge, x + size * .52f, y + size * .65f,
                    size * if (badge.length > 3) .17f else .21f)
            }
        }
    }

    private fun drawInstallerIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val body = RectF(x + size * .12f, y + size * .42f, x + size * .88f, y + size * .9f)
        paint.color = blend(color("surface"), fileColor(FileVisualType.INSTALLER),
            if (state.appearance.dark) .44f else .22f)
        canvas.drawRoundRect(body, size * .09f, size * .09f, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1.1f), size * .04f)
        canvas.drawRoundRect(body, size * .09f, size * .09f, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + size * .5f, y + size * .08f, x + size * .5f, y + size * .59f, stroke)
        canvas.drawLine(x + size * .34f, y + size * .44f, x + size * .5f, y + size * .6f, stroke)
        canvas.drawLine(x + size * .66f, y + size * .44f, x + size * .5f, y + size * .6f, stroke)
        canvas.drawLine(x + size * .29f, y + size * .74f, x + size * .71f, y + size * .74f, stroke)
    }

    private fun drawAudioIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        paint.color = blend(color("surface"), fileColor(FileVisualType.AUDIO),
            if (state.appearance.dark) .45f else .22f)
        canvas.drawCircle(x + size * .5f, y + size * .52f, size * .43f, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1.1f), size * .04f)
        canvas.drawCircle(x + size * .5f, y + size * .52f, size * .43f, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + size * .57f, y + size * .29f, x + size * .57f, y + size * .68f, stroke)
        canvas.drawLine(x + size * .57f, y + size * .3f, x + size * .76f, y + size * .36f, stroke)
        canvas.drawCircle(x + size * .45f, y + size * .7f, size * .12f, stroke)
    }

    private fun drawVideoIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val frame = RectF(x + size * .06f, y + size * .18f, x + size * .94f, y + size * .83f)
        paint.color = blend(color("surface"), fileColor(FileVisualType.VIDEO),
            if (state.appearance.dark) .45f else .22f)
        canvas.drawRoundRect(frame, size * .1f, size * .1f, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1.1f), size * .04f)
        canvas.drawRoundRect(frame, size * .1f, size * .1f, stroke)
        paint.color = color("text")
        iconPath.reset()
        iconPath.moveTo(x + size * .42f, y + size * .36f)
        iconPath.lineTo(x + size * .7f, y + size * .51f)
        iconPath.lineTo(x + size * .42f, y + size * .68f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
    }

    private fun drawImageIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val frame = RectF(x + size * .08f, y + size * .1f, x + size * .92f, y + size * .9f)
        paint.color = blend(color("surface"), fileColor(FileVisualType.IMAGE),
            if (state.appearance.dark) .43f else .20f)
        canvas.drawRoundRect(frame, size * .08f, size * .08f, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1.1f), size * .04f)
        canvas.drawRoundRect(frame, size * .08f, size * .08f, stroke)
        canvas.drawCircle(x + size * .67f, y + size * .31f, size * .09f, stroke)
        iconPath.reset()
        iconPath.moveTo(x + size * .18f, y + size * .76f)
        iconPath.lineTo(x + size * .4f, y + size * .51f)
        iconPath.lineTo(x + size * .54f, y + size * .66f)
        iconPath.lineTo(x + size * .66f, y + size * .54f)
        iconPath.lineTo(x + size * .84f, y + size * .76f)
        canvas.drawPath(iconPath, stroke)
    }

    private fun drawArchiveIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val tint = fileColor(FileVisualType.ARCHIVE)
        val left = x + size * .08f
        val right = x + size * .92f
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1f), size * .035f)
        repeat(3) { index ->
            val top = y + size * (.1f + index * .27f)
            val layer = RectF(left, top, right, top + size * .22f)
            paint.color = blend(color("surface"), tint,
                if (state.appearance.dark) .34f + index * .08f else .12f + index * .07f)
            canvas.drawRoundRect(layer, size * .06f, size * .06f, paint)
            canvas.drawRoundRect(layer, size * .06f, size * .06f, stroke)
        }
        paint.color = color("text")
        for (index in 0..4) {
            val top = y + size * (.12f + index * .145f)
            canvas.drawRect(x + size * .46f, top, x + size * .56f, top + size * .075f, paint)
        }
    }

    private fun drawPresentationIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val screen = RectF(x + size * .08f, y + size * .12f, x + size * .92f, y + size * .72f)
        paint.color = blend(color("surface"), fileColor(FileVisualType.PRESENTATION),
            if (state.appearance.dark) .44f else .22f)
        canvas.drawRoundRect(screen, size * .06f, size * .06f, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1.1f), size * .04f)
        canvas.drawRoundRect(screen, size * .06f, size * .06f, stroke)
        canvas.drawLine(x + size * .5f, y + size * .72f, x + size * .5f, y + size * .88f, stroke)
        canvas.drawLine(x + size * .35f, y + size * .88f, x + size * .65f, y + size * .88f, stroke)
        paint.color = color("text")
        canvas.drawRect(x + size * .23f, y + size * .28f, x + size * .36f, y + size * .57f, paint)
        canvas.drawRect(x + size * .43f, y + size * .38f, x + size * .56f, y + size * .57f, paint)
        canvas.drawRect(x + size * .63f, y + size * .22f, x + size * .76f, y + size * .57f, paint)
    }

    private fun drawIconText(canvas: Canvas, value: String, cx: Float, cy: Float, textSize: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color("text")
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(value, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
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

    private fun fileVisualType(file: File): FileVisualType = FileVisualType.from(
        fileName = file.name,
        archiveHint = pluginFileIcon(file) == PluginFileIcon.ARCHIVE
    )

    private fun fileTypeLabel(type: FileVisualType): String = context.getString(when (type) {
        FileVisualType.INSTALLER -> R.string.file_type_installer
        FileVisualType.TEXT -> R.string.file_type_text
        FileVisualType.AUDIO -> R.string.file_type_audio
        FileVisualType.VIDEO -> R.string.file_type_video
        FileVisualType.IMAGE -> R.string.file_type_image
        FileVisualType.ARCHIVE -> R.string.file_type_archive
        FileVisualType.PDF -> R.string.file_type_pdf
        FileVisualType.CODE -> R.string.file_type_code
        FileVisualType.DOCUMENT -> R.string.file_type_document
        FileVisualType.SPREADSHEET -> R.string.file_type_spreadsheet
        FileVisualType.PRESENTATION -> R.string.file_type_presentation
        FileVisualType.GENERIC -> R.string.file_type_generic
    })

    /** Color is a secondary cue; silhouettes, glyphs and labels remain meaningful without it. */
    private fun fileColor(type: FileVisualType): Int = when (type) {
        FileVisualType.INSTALLER -> Color.rgb(22, 163, 74)
        FileVisualType.TEXT, FileVisualType.CODE -> Color.rgb(59, 130, 246)
        FileVisualType.AUDIO -> Color.rgb(20, 184, 166)
        FileVisualType.VIDEO -> Color.rgb(236, 72, 153)
        FileVisualType.IMAGE -> Color.rgb(168, 85, 247)
        FileVisualType.ARCHIVE -> Color.rgb(245, 158, 11)
        FileVisualType.PDF -> Color.rgb(239, 68, 68)
        FileVisualType.DOCUMENT -> Color.rgb(37, 99, 235)
        FileVisualType.SPREADSHEET -> Color.rgb(22, 163, 74)
        FileVisualType.PRESENTATION -> Color.rgb(234, 88, 12)
        FileVisualType.GENERIC -> Color.rgb(100, 116, 139)
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
        tabSlotHits.clear()
        tabCloseHits.clear()
        val viewportWidth = contentRight - contentLeft
        if (viewportWidth <= 0f) {
            maxDockScroll = 0f
            return
        }
        val minTabWidth = min(dp(86f), viewportWidth)
        val maxTabWidth = max(minTabWidth, min(dp(220f), viewportWidth * .72f))
        val widths = state.tabs.map { tab ->
            textWidth(tab.label, 12.5f, false).plus(dp(if (state.dockEditing) 54f else 36f))
                .coerceIn(minTabWidth, maxTabWidth)
        }.toMutableList()
        val measuredWidth = widths.sum()
        if (measuredWidth < viewportWidth && widths.isNotEmpty()) {
            val extra = (viewportWidth - measuredWidth) / widths.size
            widths.indices.forEach { widths[it] += extra }
        }
        val totalWidth = widths.sum()
        maxDockScroll = max(0f, totalWidth - viewportWidth)
        var logicalLeft = contentLeft
        val targetRects = widths.map { tabWidth ->
            RectF(logicalLeft - state.dockScrollX, top,
                logicalLeft + tabWidth - state.dockScrollX, bottom).also { logicalLeft += tabWidth }
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
        canvas.clipRect(contentLeft, top, contentRight, bottom)
        drawActiveTabIndicator(canvas, tabRects)
        state.tabs.forEachIndexed { index, tab ->
            val rect = tabRects[index]
            val targetRect = targetRects[index]
            if (rect.right >= contentLeft && rect.left <= contentRight) tabHits += TabHit(index, RectF(rect))
            if (targetRect.right >= contentLeft && targetRect.left <= contentRight) {
                tabSlotHits += TabHit(index, RectF(targetRect))
            }
            val dragTarget = state.dragging && rect.contains(state.dragX, state.dragY) &&
                TransferTargetPolicy.accepts(state.dragSources, tab.directory)
            val tabBeingDragged = state.tabDragging && index == state.draggedTabIndex
            if (dragTarget || tabBeingDragged) {
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
            val labelRightPadding = if (state.dockEditing && index > 0) 29f else 13f
            overflowText(canvas, tabMarqueeKey(index), tab.label,
                RectF(rect.left + dp(13f), rect.top + dp(7f), rect.right - dp(labelRightPadding), rect.bottom - dp(6f)),
                rect.centerY(), 12.5f, when {
                    index == state.activeTab -> color("primary")
                    tab.pinned -> color("muted")
                    else -> color("text")
                }, Paint.Align.CENTER, index == state.activeTab,
                index == state.activeTab || dragTarget || tabBeingDragged)
            if (state.dockEditing && index > 0) drawTabManagementButton(canvas, index, rect)
        }
        canvas.restore()
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

    private fun lerp(start: Float, end: Float, progress: Float) = start + (end - start) * progress

    private fun drawTabManagementButton(
        canvas: Canvas,
        index: Int,
        tabRect: RectF
    ) {
        val cx = tabRect.right - dp(13f)
        val cy = tabRect.top + dp(15f)
        val accent = color("danger")
        val softAccent = blend(desaturate(accent, .48f), color("muted"), .16f)
        val badgeColor = blend(
            color("surface2"),
            softAccent,
            if (state.appearance.dark) .32f else .20f
        )
        paint.color = badgeColor
        paint.alpha = 255
        canvas.drawCircle(cx, cy, dp(8.5f), paint)
        stroke.color = blend(color("line"), softAccent, .58f)
        stroke.alpha = 255
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, dp(8f), stroke)
        stroke.color = softAccent
        stroke.strokeWidth = dp(1.65f)
        stroke.strokeCap = Paint.Cap.ROUND
        val arm = dp(2.8f)
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, stroke)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, stroke)
        tabCloseHits += TabHit(index, RectF(
            cx - dp(16f), tabRect.top, cx + dp(16f), tabRect.top + dp(37f)
        ))
    }

    private fun drawDragPreview(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(contentLeft, topBarBottom, contentRight,
            contentBottom(height, state.insets.bottom))
        if (state.dragging) {
            fileHits.firstOrNull {
                metadata(it.file).directory && it.rect.contains(state.dragX, state.dragY) &&
                    TransferTargetPolicy.accepts(state.dragSources, it.file)
            }?.let {
                stroke.color = color("primary"); stroke.strokeWidth = dp(3f)
                canvas.drawRoundRect(it.rect, dp(10f), dp(10f), stroke)
            }
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
            (contentRight - contentLeft - horizontalMargin * 2f).coerceAtLeast(0f)
        val availableMenuHeight = (height - state.insets.top - state.insets.bottom - verticalMargin * 2f)
            .coerceAtLeast(0f)
        val minLeft = contentLeft + horizontalMargin
        val maxRight = contentRight - horizontalMargin
        val minTop = state.insets.top + verticalMargin
        val maxBottom = height - state.insets.bottom - verticalMargin

        data class Panel(
            val actions: List<MenuAction>,
            val rect: RectF,
            val itemHeight: Float,
            val parent: MenuAction?,
            val direction: Int
        )

        fun itemHeight(actions: List<MenuAction>): Float = if (actions.isEmpty()) 0f else min(
            dp(48f),
            ((availableMenuHeight - panelPadding) / actions.size).coerceAtLeast(0f)
        )

        fun panelHeight(actions: List<MenuAction>, rowHeight: Float) =
            rowHeight * actions.size + panelPadding

        fun desiredPanelWidth(actions: List<MenuAction>): Float {
            configureText(15f, color("text"), false)
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
                    paint.color = fadeColor(color("surface2"), highlightProgress)
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
                overflowText(
                    canvas,
                    "static:menu:$layerIndex:$index",
                    action.label,
                    RectF(textLeft, rect.top, textRight, rect.bottom),
                    rect.centerY(),
                    15f,
                    fadeColor(
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

    private fun preferredSubmenuDirection(
        panel: RectF,
        menuWidth: Float,
        panelGap: Float,
        horizontalMargin: Float
    ): Int {
        val minLeft = contentLeft + horizontalMargin
        val maxRight = contentRight - horizontalMargin
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
        stroke.color = fadeColor(if (enabled) color("text") else color("muted"), progress)
        stroke.alpha = 255
        stroke.strokeWidth = dp(1.6f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val tipX = centerX + direction * armX
        val tailX = centerX - direction * armX
        canvas.drawLine(tailX, centerY - armY, tipX, centerY, stroke)
        canvas.drawLine(tipX, centerY, tailX, centerY + armY, stroke)
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

    private fun desaturate(source: Int, amount: Float): Int {
        val gray = (Color.red(source) * .299f + Color.green(source) * .587f +
            Color.blue(source) * .114f).toInt()
        return blend(source, Color.rgb(gray, gray, gray), amount.coerceIn(0f, 1f))
    }

    private fun fadeColor(color: Int, progress: Float): Int = Color.argb(
        (Color.alpha(color) * progress.coerceIn(0f, 1f)).toInt(),
        Color.red(color), Color.green(color), Color.blue(color)
    )
}
