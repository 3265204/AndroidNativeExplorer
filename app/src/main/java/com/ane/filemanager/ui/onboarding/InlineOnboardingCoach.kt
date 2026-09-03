package com.ane.filemanager.ui.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.onboarding.TutorialProgress.Action
import com.ane.filemanager.ui.onboarding.TutorialProgress.Step
import java.io.File

/** Coach marks over the real file manager while mutations stay in a disposable workspace. */
internal class InlineOnboardingCoach(
    private val context: Context,
    val workspace: OnboardingWorkspace?,
    private val onCompleted: () -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics
    )

    private val palette = AneTheme.resolve(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progress = TutorialProgress()
    private var enabled = workspace != null

    var targetPath: String? = workspace?.sample?.absolutePath
        private set

    val active: Boolean get() = enabled && progress.step != Step.COMPLETE
    val step: Step get() = progress.step

    fun selected(file: File) {
        if (step == Step.SELECT && same(file, workspace?.sample)) progress.accept(Action.TAP_ITEM)
    }

    fun acceptsMoveTo(directory: File?): Boolean =
        step == Step.MOVE_TO_DOCK && same(directory, workspace?.moveTarget)

    fun moveCompleted(sources: List<File>, target: File) {
        val tutorial = workspace ?: return
        if (step != Step.MOVE_TO_DOCK || !same(target, tutorial.moveTarget) ||
            sources.none { it.name == tutorial.sample.name }) return
        targetPath = tutorial.movedSample.absolutePath
        progress.accept(Action.MOVE_TO_DOCK)
    }

    fun longPressMenuOpened(file: File) {
        if (step == Step.LONG_PRESS_MENU && file.absolutePath == targetPath) {
            progress.accept(Action.LONG_PRESS_MENU)
        }
    }

    fun copied() {
        if (step == Step.COPY_CHOOSE) progress.accept(Action.COPY)
    }

    fun menuOpened() {
        if (step == Step.PASTE_OPEN_MENU) progress.accept(Action.OPEN_MENU)
    }

    fun pasteCompleted(target: File) {
        val tutorial = workspace ?: return
        if (step != Step.PASTE_CHOOSE || !same(target, tutorial.copyTarget)) return
        targetPath = tutorial.copiedSample.absolutePath
        progress.accept(Action.PASTE)
    }

    fun opened(file: File) {
        if (step == Step.OPEN && file.absolutePath == targetPath) {
            progress.accept(Action.DOUBLE_TAP_ITEM)
        }
    }

    fun tabSwitched(directory: File) {
        val tutorial = workspace ?: return
        when (step) {
            Step.OPEN_MOVE_DESTINATION -> if (same(directory, tutorial.moveTarget)) {
                progress.accept(Action.SWITCH_TO_MOVED_FILE)
            }
            Step.OPEN_COPY_DESTINATION -> if (same(directory, tutorial.copyTarget)) {
                progress.accept(Action.SWITCH_TO_COPY_DESTINATION)
            }
            Step.TABS -> if (progress.accept(Action.SWITCH_TAB)) {
                enabled = false
                onCompleted()
            }
            else -> Unit
        }
    }

    fun draw(
        canvas: Canvas,
        primary: RectF?,
        secondary: RectF?,
        dragging: Boolean,
        longPressActive: Boolean
    ) {
        if (!active) return
        if (primary == null) {
            paint.color = SCRIM
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            drawBubble(canvas, null, context.getString(R.string.tutorial_waiting_for_items))
            return
        }

        val holes = buildList {
            add(RectF(primary).apply { inset(-dp(6f), -dp(6f)) })
            secondary?.let { add(RectF(it).apply { inset(-dp(6f), -dp(6f)) }) }
        }
        val scrim = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), Path.Direction.CW)
            holes.forEach { addRoundRect(it, dp(14f), dp(14f), Path.Direction.CW) }
        }
        paint.style = Paint.Style.FILL
        paint.color = SCRIM
        canvas.drawPath(scrim, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = palette.primary
        holes.forEach { canvas.drawRoundRect(it, dp(14f), dp(14f), paint) }

        if (longPressActive && (step == Step.MOVE_TO_DOCK || step == Step.LONG_PRESS_MENU)) return
        drawBubble(canvas, secondary ?: holes.first(), instruction(dragging))
    }

    private fun title(): String = context.getString(when (step) {
        Step.SELECT -> R.string.tutorial_select_title
        Step.MOVE_TO_DOCK -> R.string.tutorial_dock_drag_title
        Step.OPEN_MOVE_DESTINATION -> R.string.tutorial_find_moved_title
        Step.LONG_PRESS_MENU, Step.COPY_CHOOSE -> R.string.tutorial_long_press_menu_title
        Step.OPEN_COPY_DESTINATION, Step.PASTE_OPEN_MENU, Step.PASTE_CHOOSE ->
            R.string.tutorial_copy_paste_title
        Step.OPEN -> R.string.tutorial_open_title
        Step.TABS, Step.COMPLETE -> R.string.tutorial_tabs_title
    })

    private fun instruction(dragging: Boolean): String {
        val tutorial = workspace ?: return context.getString(R.string.tutorial_waiting_for_items)
        val name = tutorial.sample.name
        return when (step) {
            Step.SELECT -> context.getString(R.string.tutorial_virtual_select, name)
            Step.MOVE_TO_DOCK -> context.getString(
                if (dragging) R.string.tutorial_virtual_move_release else R.string.tutorial_virtual_move,
                name, tutorial.moveTargetLabel
            )
            Step.OPEN_MOVE_DESTINATION -> context.getString(
                R.string.tutorial_virtual_find_moved, tutorial.moveTargetLabel, name
            )
            Step.LONG_PRESS_MENU -> context.getString(R.string.tutorial_virtual_long_press, name)
            Step.COPY_CHOOSE -> context.getString(R.string.tutorial_virtual_choose_copy, name)
            Step.OPEN_COPY_DESTINATION -> context.getString(
                R.string.tutorial_virtual_choose_copy_destination, tutorial.copyTargetLabel
            )
            Step.PASTE_OPEN_MENU -> context.getString(
                R.string.tutorial_virtual_open_paste, tutorial.copyTargetLabel
            )
            Step.PASTE_CHOOSE -> context.getString(
                R.string.tutorial_virtual_choose_paste, name, tutorial.copyTargetLabel
            )
            Step.OPEN -> context.getString(R.string.tutorial_virtual_open, name)
            Step.TABS, Step.COMPLETE -> context.getString(R.string.tutorial_virtual_tabs)
        }
    }

    private fun stepNumber(): Int = when (step) {
        Step.SELECT -> 1
        Step.MOVE_TO_DOCK -> 2
        Step.OPEN_MOVE_DESTINATION, Step.LONG_PRESS_MENU, Step.COPY_CHOOSE -> 3
        Step.OPEN_COPY_DESTINATION, Step.PASTE_OPEN_MENU, Step.PASTE_CHOOSE -> 4
        Step.OPEN -> 5
        Step.TABS, Step.COMPLETE -> 6
    }

    private fun drawBubble(canvas: Canvas, anchor: RectF?, instruction: String) {
        val margin = dp(16f)
        val width = (canvas.width - margin * 2f).coerceAtMost(dp(420f))
        val height = dp(126f)
        val left = canvas.width / 2f - width / 2f
        val top = when {
            anchor == null -> canvas.height / 2f - height / 2f
            anchor.centerY() > canvas.height / 2f -> margin
            else -> canvas.height - margin - height
        }
        val bubble = RectF(left, top, left + width, top + height)
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRoundRect(bubble, dp(18f), dp(18f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = palette.outline
        canvas.drawRoundRect(bubble, dp(18f), dp(18f), paint)
        text(canvas, title(), bubble.left + dp(18f), bubble.top + dp(28f), 16f,
            palette.text, true, Paint.Align.LEFT)
        text(canvas, context.getString(R.string.tutorial_inline_progress, stepNumber(), 6),
            bubble.right - dp(18f), bubble.top + dp(28f), 12f,
            palette.primary, true, Paint.Align.RIGHT)
        wrappedText(canvas, instruction, bubble.left + dp(18f), bubble.top + dp(51f),
            bubble.width() - dp(36f), 14f, palette.muted, 3)
    }

    private fun text(
        canvas: Canvas, value: String, x: Float, centerY: Float, sizeSp: Float,
        color: Int, bold: Boolean, align: Paint.Align
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sp(sizeSp)
        paint.textAlign = align
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        canvas.drawText(value, x, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun wrappedText(
        canvas: Canvas, value: String, left: Float, top: Float, maxWidth: Float,
        sizeSp: Float, color: Int, maxLines: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sp(sizeSp)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT
        val lines = mutableListOf<String>()
        var remaining = value.trim()
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val breakAt = remaining.substring(0, count).lastIndexOf(' ')
                if (breakAt > count / 2) count = breakAt
            }
            var line = remaining.substring(0, count).trim()
            remaining = remaining.substring(count).trim()
            if (lines.size == maxLines - 1 && remaining.isNotEmpty()) {
                while (line.isNotEmpty() && paint.measureText("$line…") > maxWidth) line = line.dropLast(1)
                line += "…"
                remaining = ""
            }
            lines += line
        }
        val baseline = -paint.ascent()
        val lineHeight = (paint.descent() - paint.ascent()) * 1.12f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left, top + baseline + index * lineHeight, paint)
        }
    }

    private fun same(left: File?, right: File?): Boolean {
        if (left == null || right == null) return false
        return try { left.canonicalFile == right.canonicalFile }
        catch (_: Exception) { left.absolutePath == right.absolutePath }
    }

    private companion object {
        val SCRIM: Int = Color.argb(170, 0, 0, 0)
    }
}
