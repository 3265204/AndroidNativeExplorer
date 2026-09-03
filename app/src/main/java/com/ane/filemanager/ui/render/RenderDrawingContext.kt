package com.ane.filemanager.ui.render

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.model.RenderState

/** Shared, frame-scoped drawing primitives used by the focused renderers. */
internal class RenderDrawingContext(
    val context: Context,
    onInvalidate: () -> Unit
) {
    private val density = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val iconPath = Path()
    val overflow = TextOverflow(this, onInvalidate)

    lateinit var state: RenderState
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    private var resolvedPalette: AneTheme? = null

    val contentLeft get() = state.insets.left.toFloat()
    val contentRight get() = width - state.insets.right.toFloat()
    val topBarTop get() = state.insets.top.toFloat()
    val topHeight get() = dp(56f)
    val topBarBottom get() = topBarTop + topHeight
    val bottomHeight get() = dp(58f)
    val fabOffset get() = dp(39f)
    val contentBottom get() = height - state.insets.bottom - bottomHeight

    fun beginFrame(renderState: RenderState, canvasWidth: Int, canvasHeight: Int) {
        state = renderState
        width = canvasWidth
        height = canvasHeight
    }

    fun dp(value: Float) = value * density

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

    fun textWidth(value: String, sizeSp: Float, bold: Boolean): Float {
        configureText(sizeSp, Color.WHITE, bold)
        return paint.measureText(value)
    }

    fun configureText(sizeSp: Float, color: Int, bold: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sizeSp * context.resources.displayMetrics.scaledDensity
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
        else android.graphics.Typeface.DEFAULT
    }

    fun blend(a: Int, b: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(a) * inverse + Color.red(b) * ratio).toInt(),
            (Color.green(a) * inverse + Color.green(b) * ratio).toInt(),
            (Color.blue(a) * inverse + Color.blue(b) * ratio).toInt()
        )
    }

    fun desaturate(source: Int, amount: Float): Int {
        val gray = (Color.red(source) * .299f + Color.green(source) * .587f +
            Color.blue(source) * .114f).toInt()
        return blend(source, Color.rgb(gray, gray, gray), amount.coerceIn(0f, 1f))
    }

    fun fadeColor(color: Int, progress: Float): Int = Color.argb(
        (Color.alpha(color) * progress.coerceIn(0f, 1f)).toInt(),
        Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun palette(): AneTheme {
        val current = resolvedPalette
        if (current != null && current.dark == state.appearance.dark) return current
        return AneTheme.resolve(context, state.appearance.dark).also { resolvedPalette = it }
    }
}
