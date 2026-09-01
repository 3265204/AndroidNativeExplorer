package com.ane.filemanager.ui.secondary

import android.os.Build
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.ui.model.UiInsets
import com.ane.filemanager.plugin.api.ui.AneComponents
import com.ane.filemanager.plugin.api.ui.AneMotion
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.plugin.api.ui.AneUiTokens

/**
 * Shared host chrome for full-screen second-level pages.
 *
 * Feature pages own their content and state. This scaffold exclusively owns the
 * common page contract: safe areas, header, responsive width, mounting and motion.
 */
internal class SecondaryPageScaffold(
    private val host: MainActivity,
    private val theme: AneTheme,
    title: String,
    closeDescription: String,
    private val originX: Float,
    private val originY: Float,
    private val onUsableWidthChanged: ((Int) -> Unit)? = null,
    private val onClosed: () -> Unit = {}
) {
    val content = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(AneUiTokens.PAGE_HORIZONTAL_PADDING_DP),
            dp(AneUiTokens.PAGE_TOP_PADDING_DP),
            dp(AneUiTokens.PAGE_HORIZONTAL_PADDING_DP),
            dp(AneUiTokens.PAGE_BOTTOM_PADDING_DP)
        )
        setBackgroundColor(theme.background)
    }
    private val header = AneComponents.pageHeader(
        context = host,
        theme = theme,
        navigationIconRes = R.drawable.ic_secondary_back,
        navigationDescription = closeDescription,
        title = title,
        onNavigate = ::close
    )
    val summary: TextView
        get() = header.summary
    val root = FrameLayout(host).apply {
        visibility = android.view.View.INVISIBLE
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
        header.attachTo(content)
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
            return (widthDp - AneUiTokens.PAGE_HORIZONTAL_PADDING_DP * 2).coerceAtLeast(1)
        }

    fun show() {
        host.showFullscreenOverlay(root, ::close)
        root.post { AneMotion.reveal(root, originX, originY) }
    }

    fun close() {
        if (closing || root.parent == null) return
        closing = true
        AneMotion.hide(root) {
            host.removeFullscreenOverlay(root)
            onClosed()
        }
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
}
