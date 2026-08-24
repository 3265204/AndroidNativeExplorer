package com.ane.filemanager.ui.secondary

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.ui.model.UiInsets
import com.ane.filemanager.ui.motion.FullscreenOverlayMotion
import com.ane.filemanager.ui.theme.AppThemePalette

/**
 * Shared host chrome for full-screen second-level pages.
 *
 * Feature pages own their content and state. This scaffold exclusively owns the
 * common page contract: safe areas, header, responsive width, mounting and motion.
 */
internal class SecondaryPageScaffold(
    private val host: MainActivity,
    private val theme: AppThemePalette,
    title: String,
    closeDescription: String,
    private val originX: Float,
    private val originY: Float,
    private val onUsableWidthChanged: ((Int) -> Unit)? = null
) {
    val content = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(HORIZONTAL_PADDING_DP), dp(TOP_PADDING_DP),
            dp(HORIZONTAL_PADDING_DP), dp(BOTTOM_PADDING_DP))
        setBackgroundColor(theme.background)
    }
    val summary = TextView(host).apply {
        textSize = 13f
        setTextColor(theme.muted)
    }
    val root = FrameLayout(host).apply {
        visibility = View.INVISIBLE
        setBackgroundColor(theme.background)
        isClickable = true
        isFocusable = true
        addView(content, FrameLayout.LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets ->
            val safe = systemInsets(insets)
            (content.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = safe.left
                topMargin = safe.top
                rightMargin = safe.right
                bottomMargin = safe.bottom
                content.layoutParams = this
            }
            insets
        }
    }

    private var lastUsableWidth = 0
    private var closing = false

    init {
        content.addView(header(title, closeDescription), LinearLayout.LayoutParams(-1, dp(HEADER_HEIGHT_DP)))
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            val oldWidth = oldRight - oldLeft
            val usable = usableWidthDp
            if (width > 0 && width != oldWidth && usable != lastUsableWidth) {
                lastUsableWidth = usable
                root.post {
                    if (!closing && root.parent != null) onUsableWidthChanged?.invoke(usable)
                }
            }
        }
    }

    val usableWidthDp: Int
        get() {
            val widthPx = root.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
            val widthDp = (widthPx / host.resources.displayMetrics.density).toInt()
            return (widthDp - HORIZONTAL_PADDING_DP * 2).coerceAtLeast(1)
        }

    fun show() {
        host.showFullscreenOverlay(root, ::close)
        root.post { FullscreenOverlayMotion.reveal(root, originX, originY) }
    }

    fun close() {
        if (closing || root.parent == null) return
        closing = true
        FullscreenOverlayMotion.hide(root) { host.removeFullscreenOverlay(root) }
    }

    private fun header(title: String, closeDescription: String): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageButton(host).apply {
            setImageResource(R.drawable.ic_secondary_back)
            setColorFilter(theme.text)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(BACK_ICON_PADDING_DP), dp(BACK_ICON_PADDING_DP),
                dp(BACK_ICON_PADDING_DP), dp(BACK_ICON_PADDING_DP))
            background = GradientDrawable().apply {
                setColor(theme.surface)
                cornerRadius = dp(18).toFloat()
            }
            contentDescription = closeDescription
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(BACK_SIZE_DP), dp(BACK_SIZE_DP)))
        addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(host).apply {
                text = title
                textSize = 22f
                setTextColor(theme.text)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(summary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun systemInsets(insets: WindowInsets): UiInsets = if (Build.VERSION.SDK_INT >= 30) {
        val value = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        UiInsets(value.left, value.top, value.right, value.bottom)
    } else {
        @Suppress("DEPRECATION")
        UiInsets(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom
        )
    }

    private fun dp(value: Int) = (value * host.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val HORIZONTAL_PADDING_DP = 18
        const val TOP_PADDING_DP = 14
        const val BOTTOM_PADDING_DP = 18
        const val HEADER_HEIGHT_DP = 60
        const val BACK_SIZE_DP = 48
        const val BACK_ICON_PADDING_DP = 13
    }
}
