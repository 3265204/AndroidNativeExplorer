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
    private var menuLayerProgress = 1f
    private var animatedMenuLayer = -1
    private var menuLayerAnimator: ValueAnimator? = null
    private var closing = false

    fun openMenu() {
        closing = false
        menuAnimator?.cancel()
        resetLayerAnimation()
        menuProgress = 0f
        animateMenuTo(1f, 240L)
    }

    fun enterMenuLayer(layer: Int) {
        animateMenuLayer(layer, 1f, 210L)
    }

    fun exitMenuLayer(layer: Int, after: () -> Unit) {
        animateMenuLayer(layer, 0f, 160L, after)
    }

    fun isMenuOpening() = menuProgress < 1f && !closing

    private fun animateMenuLayer(
        layer: Int,
        target: Float,
        duration: Long,
        after: () -> Unit = {}
    ) {
        val start = if (animatedMenuLayer == layer) menuLayerProgress else 1f - target
        menuLayerAnimator?.cancel()
        animatedMenuLayer = layer
        menuLayerProgress = start
        val animator = ValueAnimator.ofFloat(start, target).apply {
            this.duration = duration
            interpolator = if (target > start) {
                PathInterpolator(.2f, 0f, 0f, 1f)
            } else {
                PathInterpolator(.4f, 0f, 1f, 1f)
            }
            addUpdateListener {
                menuLayerProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        after()
                        animatedMenuLayer = -1
                        menuLayerProgress = 1f
                        invalidate()
                    }
                }
            })
        }
        menuLayerAnimator = animator
        animator.start()
    }

    fun closeMenu(after: () -> Unit) {
        if (closing) return
        closing = true
        resetLayerAnimation()
        animateMenuTo(0f, 190L) {
            closing = false
            after()
        }
    }

    fun snapshot() = MotionSnapshot(menuProgress, menuLayerProgress, animatedMenuLayer)

    private fun resetLayerAnimation() {
        menuLayerAnimator?.cancel()
        menuLayerAnimator = null
        menuLayerProgress = 1f
        animatedMenuLayer = -1
    }

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
