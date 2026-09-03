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
import com.ane.filemanager.ui.model.LayoutMode
import com.ane.filemanager.ui.onboarding.CoachMarkPlacement.Box
import com.ane.filemanager.ui.onboarding.CoachMarkPlacement.VerticalPreference
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
    private var listLayoutTarget: RectF? = null
    private var gridLayoutTarget: RectF? = null
    private var layoutNextTarget: RectF? = null

    var selectedLayout: LayoutMode? = null
        private set

    var targetPath: String? = workspace?.sample?.absolutePath
        private set

    val active: Boolean get() = enabled && progress.step != Step.COMPLETE
    val step: Step get() = progress.step

    fun layoutChoiceAt(x: Float, y: Float): LayoutMode? = when {
        listLayoutTarget?.contains(x, y) == true -> LayoutMode.LIST
        gridLayoutTarget?.contains(x, y) == true -> LayoutMode.GRID
        else -> null
    }

    fun selectLayout(mode: LayoutMode) {
        if (step == Step.LAYOUT) selectedLayout = mode
    }

    fun isLayoutNextAt(x: Float, y: Float): Boolean =
        layoutNextTarget?.contains(x, y) == true

    fun confirmLayout(): Boolean {
        if (step != Step.LAYOUT || selectedLayout == null) return false
        return progress.accept(Action.CHOOSE_LAYOUT)
    }

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
        directoryTargets: List<RectF>,
        dragging: Boolean,
        longPressActive: Boolean
    ) {
        if (!active) return
        if (step == Step.LAYOUT) {
            drawLayoutChooser(canvas, directoryTargets)
            return
        }
        listLayoutTarget = null
        gridLayoutTarget = null
        layoutNextTarget = null
        if (primary == null) {
            paint.color = SCRIM
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            drawBubble(
                canvas, null, directoryTargets,
                context.getString(R.string.tutorial_waiting_for_items)
            )
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
        drawBubble(canvas, secondary ?: holes.first(), directoryTargets, instruction(dragging))
    }

    private fun title(): String = context.getString(when (step) {
        Step.LAYOUT -> R.string.tutorial_layout_title
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
            Step.LAYOUT -> context.getString(R.string.tutorial_layout_description)
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
        Step.LAYOUT -> 1
        Step.SELECT -> 2
        Step.MOVE_TO_DOCK -> 3
        Step.OPEN_MOVE_DESTINATION, Step.LONG_PRESS_MENU, Step.COPY_CHOOSE -> 4
        Step.OPEN_COPY_DESTINATION, Step.PASTE_OPEN_MENU, Step.PASTE_CHOOSE -> 5
        Step.OPEN -> 6
        Step.TABS, Step.COMPLETE -> 7
    }

    private fun drawLayoutChooser(canvas: Canvas, directoryTargets: List<RectF>) {
        paint.style = Paint.Style.FILL
        paint.color = SCRIM
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)

        val margin = dp(16f)
        val width = (canvas.width - margin * 2f).coerceAtMost(dp(460f))
        val height = dp(286f)
        val panel = popupBounds(
            canvas, width, height, margin, emptyList(), directoryTargets,
            VerticalPreference.BOTTOM
        )
        paint.color = palette.surface
        canvas.drawRoundRect(panel, dp(20f), dp(20f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = palette.outline
        canvas.drawRoundRect(panel, dp(20f), dp(20f), paint)

        text(canvas, title(), panel.left + dp(18f), panel.top + dp(29f), 17f,
            palette.text, true, Paint.Align.LEFT)
        text(canvas, context.getString(R.string.tutorial_inline_progress, 1, TOTAL_STEPS),
            panel.right - dp(18f), panel.top + dp(29f), 12f,
            palette.primary, true, Paint.Align.RIGHT)
        wrappedText(
            canvas,
            context.getString(R.string.tutorial_layout_description),
            panel.left + dp(18f),
            panel.top + dp(52f),
            panel.width() - dp(36f),
            14f,
            palette.muted,
            2
        )

        val gap = dp(12f)
        val choicesTop = panel.top + dp(112f)
        val choicesBottom = panel.bottom - dp(72f)
        val choiceWidth = (panel.width() - dp(36f) - gap) / 2f
        listLayoutTarget = RectF(
            panel.left + dp(18f), choicesTop,
            panel.left + dp(18f) + choiceWidth, choicesBottom
        )
        gridLayoutTarget = RectF(
            listLayoutTarget!!.right + gap, choicesTop,
            panel.right - dp(18f), choicesBottom
        )
        drawLayoutChoice(canvas, listLayoutTarget!!, LayoutMode.LIST)
        drawLayoutChoice(canvas, gridLayoutTarget!!, LayoutMode.GRID)

        layoutNextTarget = RectF(
            panel.left + dp(18f), panel.bottom - dp(58f),
            panel.right - dp(18f), panel.bottom - dp(18f)
        )
        val nextEnabled = selectedLayout != null
        paint.style = Paint.Style.FILL
        paint.color = if (nextEnabled) palette.primary else palette.surface2
        canvas.drawRoundRect(layoutNextTarget!!, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = palette.outline
        canvas.drawRoundRect(layoutNextTarget!!, dp(12f), dp(12f), paint)
        text(
            canvas,
            context.getString(R.string.tutorial_next),
            layoutNextTarget!!.centerX(),
            layoutNextTarget!!.centerY(),
            14f,
            if (nextEnabled) Color.WHITE else palette.muted,
            true,
            Paint.Align.CENTER
        )
    }

    private fun drawLayoutChoice(canvas: Canvas, bounds: RectF, mode: LayoutMode) {
        val selected = selectedLayout == mode
        paint.style = Paint.Style.FILL
        paint.color = if (selected) palette.primary else palette.surface2
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = if (selected) palette.primary else palette.outline
        canvas.drawRoundRect(bounds, dp(14f), dp(14f), paint)

        val iconCenterX = bounds.centerX()
        val iconTop = bounds.top + dp(18f)
        paint.strokeWidth = dp(2.2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (selected) Color.WHITE else palette.primary
        if (mode == LayoutMode.LIST) {
            repeat(3) { index ->
                val y = iconTop + dp(index * 10f)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(iconCenterX - dp(22f), y, dp(2.2f), paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(iconCenterX - dp(14f), y, iconCenterX + dp(23f), y, paint)
            }
        } else {
            paint.style = Paint.Style.STROKE
            val size = dp(9f)
            val gap = dp(7f)
            val startX = iconCenterX - size - gap / 2f
            repeat(2) { row -> repeat(2) { column ->
                val cellLeft = startX + column * (size + gap)
                val cellTop = iconTop - dp(4f) + row * (size + gap)
                canvas.drawRoundRect(
                    RectF(cellLeft, cellTop, cellLeft + size, cellTop + size),
                    dp(2f), dp(2f), paint
                )
            } }
        }
        paint.strokeCap = Paint.Cap.BUTT
        text(
            canvas,
            context.getString(if (mode == LayoutMode.LIST) R.string.layout_list else R.string.layout_grid),
            bounds.centerX(), bounds.bottom - dp(22f), 15f,
            if (selected) Color.WHITE else palette.text, true, Paint.Align.CENTER
        )
    }

    private fun drawBubble(
        canvas: Canvas,
        anchor: RectF?,
        directoryTargets: List<RectF>,
        instruction: String
    ) {
        val margin = dp(16f)
        val width = (canvas.width - margin * 2f).coerceAtMost(dp(420f))
        val height = dp(126f)
        val preference = when {
            anchor == null -> VerticalPreference.CENTER
            anchor.centerY() > canvas.height / 2f -> VerticalPreference.TOP
            else -> VerticalPreference.BOTTOM
        }
        val bubble = popupBounds(
            canvas, width, height, margin, listOfNotNull(anchor), directoryTargets, preference
        )
        paint.style = Paint.Style.FILL
        paint.color = palette.surface
        canvas.drawRoundRect(bubble, dp(18f), dp(18f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = palette.outline
        canvas.drawRoundRect(bubble, dp(18f), dp(18f), paint)
        text(canvas, title(), bubble.left + dp(18f), bubble.top + dp(28f), 16f,
            palette.text, true, Paint.Align.LEFT)
        text(canvas, context.getString(R.string.tutorial_inline_progress, stepNumber(), TOTAL_STEPS),
            bubble.right - dp(18f), bubble.top + dp(28f), 12f,
            palette.primary, true, Paint.Align.RIGHT)
        wrappedText(canvas, instruction, bubble.left + dp(18f), bubble.top + dp(51f),
            bubble.width() - dp(36f), 14f, palette.muted, 3)
    }

    private fun popupBounds(
        canvas: Canvas,
        width: Float,
        height: Float,
        margin: Float,
        priorityAvoid: List<RectF>,
        contentAvoid: List<RectF>,
        preference: VerticalPreference
    ): RectF {
        val bounds = CoachMarkPlacement.place(
            viewportWidth = canvas.width.toFloat(),
            viewportHeight = canvas.height.toFloat(),
            popupWidth = width,
            popupHeight = height,
            margin = margin,
            gap = dp(8f),
            priorityAvoid = priorityAvoid.map { it.toBox() },
            contentAvoid = contentAvoid.map { it.toBox() },
            preference = preference
        )
        return RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun RectF.toBox() = Box(left, top, right, bottom)

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
        const val TOTAL_STEPS = 7
        val SCRIM: Int = Color.argb(170, 0, 0, 0)
    }
}
