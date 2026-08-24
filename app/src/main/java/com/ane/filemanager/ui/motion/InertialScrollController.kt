package com.ane.filemanager.ui.motion

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

internal enum class ScrollAxis { HORIZONTAL, VERTICAL }

/** Wraps Android's native velocity tracking and fling deceleration. */
internal class InertialScrollController(
    context: Context,
    private val axis: ScrollAxis = ScrollAxis.VERTICAL,
    private val invalidate: () -> Unit
) {
    private val scroller = OverScroller(context)
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null

    val isActive: Boolean get() = !scroller.isFinished

    fun onDown(event: MotionEvent) {
        if (!scroller.isFinished) scroller.abortAnimation()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    fun onMove(event: MotionEvent) {
        velocityTracker?.addMovement(event)
    }

    fun onUp(event: MotionEvent, allowFling: Boolean, current: Float, maximum: Float) {
        val tracker = velocityTracker ?: return
        tracker.addMovement(event)
        if (allowFling && maximum > 0f) {
            tracker.computeCurrentVelocity(1000, maximumVelocity.toFloat())
            val velocity = if (axis == ScrollAxis.HORIZONTAL) tracker.xVelocity else tracker.yVelocity
            if (abs(velocity) >= minimumVelocity) {
                if (axis == ScrollAxis.HORIZONTAL) {
                    scroller.fling(current.toInt(), 0, -velocity.toInt(), 0,
                        0, maximum.toInt(), 0, 0)
                } else {
                    scroller.fling(0, current.toInt(), 0, -velocity.toInt(),
                        0, 0, 0, maximum.toInt())
                }
                invalidate()
            }
        }
        recycleTracker()
    }

    fun onCancel() {
        recycleTracker()
    }

    fun next(maximum: Float): Float? {
        if (!scroller.computeScrollOffset()) return null
        val value = if (axis == ScrollAxis.HORIZONTAL) scroller.currX else scroller.currY
        return value.toFloat().coerceIn(0f, maximum)
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
