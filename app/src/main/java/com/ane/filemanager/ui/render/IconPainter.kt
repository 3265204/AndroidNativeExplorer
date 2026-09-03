package com.ane.filemanager.ui.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.PluginFileIcon
import java.io.File
import kotlin.math.max

/** Paints folder and file-type silhouettes. It does not own layout or hit testing. */
internal class IconPainter(
    private val drawing: RenderDrawingContext,
    private val pluginFileIcon: (File) -> PluginFileIcon?
) {
    private val paint get() = drawing.paint
    private val stroke get() = drawing.stroke
    private val iconPath get() = drawing.iconPath
    private fun dp(value: Float) = drawing.dp(value)
    private fun color(name: String) = drawing.color(name)
    private fun blend(a: Int, b: Int, ratio: Float) = drawing.blend(a, b, ratio)

    fun drawFolder(canvas: Canvas, x: Float, y: Float, size: Float, tint: Int) {
        paint.color = tint
        canvas.drawRoundRect(
            RectF(x, y + size * .25f, x + size, y + size * .88f),
            size * .1f,
            size * .1f,
            paint
        )
        canvas.drawRoundRect(
            RectF(x + size * .08f, y + size * .13f, x + size * .53f, y + size * .43f),
            size * .08f,
            size * .08f,
            paint
        )
        paint.color = blend(tint, Color.WHITE, .16f)
        canvas.drawRoundRect(
            RectF(x, y + size * .38f, x + size, y + size * .92f),
            size * .09f,
            size * .09f,
            paint
        )
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

    fun drawFileType(
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

    fun visualType(file: File): FileVisualType = FileVisualType.from(
        fileName = file.name,
        archiveHint = pluginFileIcon(file) == PluginFileIcon.ARCHIVE
    )

    fun typeLabel(type: FileVisualType): String = drawing.context.getString(when (type) {
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

    private fun drawDocumentBase(canvas: Canvas, x: Float, y: Float, size: Float, tint: Int) {
        paint.color = blend(
            color("surface"),
            tint,
            if (drawing.state.appearance.dark) .42f else .20f
        )
        iconPath.reset()
        iconPath.moveTo(x + size * .13f, y + size * .05f)
        iconPath.lineTo(x + size * .66f, y + size * .05f)
        iconPath.lineTo(x + size * .9f, y + size * .29f)
        iconPath.lineTo(x + size * .9f, y + size * .95f)
        iconPath.lineTo(x + size * .13f, y + size * .95f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        stroke.color = color("text")
        stroke.strokeWidth = max(dp(1f), size * .035f)
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(iconPath, stroke)
        paint.color = color("surface")
        iconPath.reset()
        iconPath.moveTo(x + size * .66f, y + size * .05f)
        iconPath.lineTo(x + size * .66f, y + size * .3f)
        iconPath.lineTo(x + size * .9f, y + size * .3f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
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
                    canvas.drawLine(
                        x + size * .27f,
                        y + size * offset,
                        x + size * if (offset == .76f) .62f else .75f,
                        y + size * offset,
                        stroke
                    )
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
                drawIconText(
                    canvas,
                    badge,
                    x + size * .52f,
                    y + size * .65f,
                    size * if (badge.length > 3) .17f else .21f
                )
            }
        }
    }

    private fun drawInstallerIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val body = RectF(x + size * .12f, y + size * .42f, x + size * .88f, y + size * .9f)
        paint.color = blend(
            color("surface"), fileColor(FileVisualType.INSTALLER),
            if (drawing.state.appearance.dark) .44f else .22f
        )
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
        paint.color = blend(
            color("surface"), fileColor(FileVisualType.AUDIO),
            if (drawing.state.appearance.dark) .45f else .22f
        )
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
        paint.color = blend(
            color("surface"), fileColor(FileVisualType.VIDEO),
            if (drawing.state.appearance.dark) .45f else .22f
        )
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
        paint.color = blend(
            color("surface"), fileColor(FileVisualType.IMAGE),
            if (drawing.state.appearance.dark) .43f else .20f
        )
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
            paint.color = blend(
                color("surface"), tint,
                if (drawing.state.appearance.dark) .34f + index * .08f else .12f + index * .07f
            )
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
        paint.color = blend(
            color("surface"), fileColor(FileVisualType.PRESENTATION),
            if (drawing.state.appearance.dark) .44f else .22f
        )
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
}
