package com.ane.filemanager.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import com.ane.filemanager.ui.model.DockMotionSnapshot
import com.ane.filemanager.ui.model.TabMotionStart

/** Owns Dock reorder, active-indicator, and newly loaded content transitions. */
internal class DockMotionController(private val invalidate: () -> Unit) {
    private var reorderStarts = emptyList<TabMotionStart>()
    private var reorderProgress = 1f
    private var reorderAnimator: ValueAnimator? = null
    private var fromTab = -1
    private var toTab = -1
    private var direction = 0
    private var indicatorProgress = 1f
    private var contentProgress = 1f
    private var indicatorAnimator: ValueAnimator? = null
    private var contentAnimator: ValueAnimator? = null

    fun switchTabs(from: Int, to: Int) {
        if (from == to) return
        indicatorAnimator?.cancel()
        contentAnimator?.cancel()
        fromTab = from
        toTab = to
        direction = if (to > from) 1 else -1
        indicatorProgress = 0f
        contentProgress = 0f
        indicatorAnimator = animate(0f, 1f, INDICATOR_DURATION_MS) {
            indicatorProgress = it
        }
    }

    fun revealContent() {
        if (contentProgress >= 1f) return
        contentAnimator?.cancel()
        contentAnimator = animate(contentProgress, 1f, CONTENT_DURATION_MS) {
            contentProgress = it
        }
    }

    fun reorderFrom(starts: List<TabMotionStart>) {
        if (starts.isEmpty()) return
        reorderAnimator?.cancel()
        reorderStarts = starts
        reorderProgress = 0f
        reorderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REORDER_DURATION_MS
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener {
                reorderProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        reorderProgress = 1f
                        reorderStarts = emptyList()
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    fun cancel() {
        reorderAnimator?.cancel()
        indicatorAnimator?.cancel()
        contentAnimator?.cancel()
        reorderAnimator = null
        indicatorAnimator = null
        contentAnimator = null
        reorderStarts = emptyList()
        reorderProgress = 1f
    }

    fun snapshot() = DockMotionSnapshot(
        reorderStarts = reorderStarts,
        reorderProgress = reorderProgress,
        fromTab = fromTab,
        toTab = toTab,
        indicatorProgress = indicatorProgress,
        contentProgress = contentProgress,
        direction = direction
    )

    private fun animate(
        startValue: Float,
        endValue: Float,
        durationMs: Long,
        update: (Float) -> Unit
    ): ValueAnimator = ValueAnimator.ofFloat(startValue, endValue).apply {
        duration = durationMs
        interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
        addUpdateListener {
            update(it.animatedValue as Float)
            invalidate()
        }
        start()
    }

    private companion object {
        const val REORDER_DURATION_MS = 210L
        const val INDICATOR_DURATION_MS = 260L
        const val CONTENT_DURATION_MS = 210L
    }
}
