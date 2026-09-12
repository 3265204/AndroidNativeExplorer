package com.ane.filemanager.plugin.api.ui

import android.animation.TimeInterpolator
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.max

/** Shared motion contract for ANE-owned and plugin-owned surfaces. */
object AneMotion {
    /** Standard duration and easing tokens. Keep feature code free of ad-hoc motion values. */
    const val DURATION_QUICK_MS = 120L
    const val DURATION_SHORT_MS = 160L
    const val DURATION_MEDIUM_MS = 220L
    const val DURATION_LONG_MS = 280L

    @JvmField
    val ENTER_INTERPOLATOR: TimeInterpolator = PathInterpolator(.2f, 0f, 0f, 1f)

    @JvmField
    val EXIT_INTERPOLATOR: TimeInterpolator = PathInterpolator(.4f, 0f, 1f, 1f)

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
        ).apply {
            duration = DURATION_LONG_MS
            interpolator = ENTER_INTERPOLATOR
        }.start()
    }

    fun hide(root: View, finished: () -> Unit) {
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .setDuration(DURATION_QUICK_MS)
            .setInterpolator(EXIT_INTERPOLATOR)
            .withEndAction(finished)
            .start()
    }

    fun showDialog(root: View) {
        root.animate().cancel()
        root.alpha = 0f
        root.scaleX = DIALOG_START_SCALE
        root.scaleY = DIALOG_START_SCALE
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DURATION_MEDIUM_MS)
            .setInterpolator(ENTER_INTERPOLATOR)
            .start()
    }

    fun hideDialog(root: View, finished: () -> Unit) {
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .scaleX(DIALOG_START_SCALE)
            .scaleY(DIALOG_START_SCALE)
            .setDuration(DURATION_SHORT_MS)
            .setInterpolator(EXIT_INTERPOLATOR)
            .withEndAction(finished)
            .start()
    }

    /** Adds the same subtle pressed-state response to app and plugin controls. */
    fun bindPressFeedback(view: View) {
        view.setOnTouchListener { target, event ->
            if (!target.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(PRESSED_SCALE)
                        .scaleY(PRESSED_SCALE)
                        .setDuration(DURATION_QUICK_MS / 2)
                        .setInterpolator(ENTER_INTERPOLATOR)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(DURATION_SHORT_MS)
                        .setInterpolator(ENTER_INTERPOLATOR)
                        .start()
                }
            }
            false
        }
    }

    private const val DIALOG_START_SCALE = .96f
    private const val PRESSED_SCALE = .98f
}
