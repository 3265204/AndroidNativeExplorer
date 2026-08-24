package com.ane.filemanager.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import com.ane.filemanager.ui.model.MotionSnapshot

/** Owns only menu transitions; ordinary clicks and selection changes are immediate. */
internal class UiMotionController(private val invalidate: () -> Unit) {
    private var menuProgress = 1f
    private var menuAnimator: ValueAnimator? = null
    private var closing = false

    fun openMenu() {
        closing = false
        menuAnimator?.cancel()
        menuProgress = 0f
        animateMenuTo(1f, 240L)
    }

    fun closeMenu(after: () -> Unit) {
        if (closing) return
        closing = true
        animateMenuTo(0f, 190L) {
            closing = false
            after()
        }
    }

    fun snapshot() = MotionSnapshot(menuProgress)

    private fun animateMenuTo(target: Float, duration: Long, after: () -> Unit = {}) {
        menuAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(menuProgress, target).apply {
            this.duration = duration
            interpolator = if (target > menuProgress) {
                PathInterpolator(.2f, 0f, 0f, 1f)
            } else {
                PathInterpolator(.4f, 0f, 1f, 1f)
            }
            addUpdateListener {
                menuProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) after()
                }
            })
        }
        menuAnimator = animator
        animator.start()
    }
}
