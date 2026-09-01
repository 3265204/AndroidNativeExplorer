package com.ane.filemanager.plugin.api.ui

import android.view.View
import android.view.ViewAnimationUtils
import kotlin.math.hypot
import kotlin.math.max

/** Shared motion contract for ANE-owned and plugin-owned surfaces. */
object AneMotion {
    fun reveal(root: View, originX: Float, originY: Float) {
        if (root.parent == null || root.width <= 0 || root.height <= 0) return
        val centerX = originX.toInt().coerceIn(0, root.width)
        val centerY = originY.toInt().coerceIn(0, root.height)
        val farX = max(centerX, root.width - centerX).toFloat()
        val farY = max(centerY, root.height - centerY).toFloat()
        root.alpha = 1f
        root.visibility = View.VISIBLE
        ViewAnimationUtils.createCircularReveal(
            root,
            centerX,
            centerY,
            0f,
            hypot(farX, farY)
        ).apply { duration = OPEN_DURATION_MS }.start()
    }

    fun hide(root: View, finished: () -> Unit) {
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .setDuration(CLOSE_DURATION_MS)
            .withEndAction(finished)
            .start()
    }

    const val OPEN_DURATION_MS = 280L
    const val CLOSE_DURATION_MS = 120L
}


