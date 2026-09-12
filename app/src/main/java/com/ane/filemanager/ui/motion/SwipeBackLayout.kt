package com.ane.filemanager.ui.motion

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.ane.filemanager.plugin.api.ui.AneMotion
import kotlin.math.abs

/** Edge-swipe container shared by full-screen secondary pages. */
@SuppressLint("ViewConstructor")
internal class SwipeBackLayout(
    context: Context,
    private val onDismissed: () -> Unit
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val edgeWidth = dp(EDGE_WIDTH_DP)
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var settling = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (tracking && !dragging) {
                    val horizontal = directedDistance(event.x)
                    val vertical = abs(event.y - downY)
                    if (horizontal > touchSlop && horizontal > vertical * DIRECTION_BIAS) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (horizontal < -touchSlop || vertical > touchSlop * CANCEL_VERTICAL_MULTIPLIER) {
                        stopTracking()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> if (!dragging) stopTracking()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settling) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) begin(event)
        if (!tracking && !dragging) return super.onTouchEvent(event)
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val distance = directedDistance(event.x).coerceAtLeast(0f)
                translationX = distance * directionSign()
                alpha = 1f - (distance / width.coerceAtLeast(1)) * DRAG_FADE_RATIO
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(directedDistance(event.x)) <= touchSlop &&
                    abs(event.y - downY) <= touchSlop
                ) {
                    performClick()
                }
                finish(event)
            }
            MotionEvent.ACTION_CANCEL -> settleBack()
        }
        return true
    }

    fun cancelSwipe() {
        animate().cancel()
        stopTracking()
        settling = false
        translationX = 0f
        alpha = 1f
    }

    private fun begin(event: MotionEvent) {
        if (settling || event.actionMasked != MotionEvent.ACTION_DOWN) return
        val fromStartEdge = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            event.x >= width - edgeWidth
        } else {
            event.x <= edgeWidth
        }
        if (!fromStartEdge) return
        animate().cancel()
        downX = event.x
        downY = event.y
        tracking = true
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun finish(event: MotionEvent) {
        velocityTracker?.computeCurrentVelocity(MILLISECONDS_PER_SECOND)
        val horizontalVelocity = (velocityTracker?.xVelocity ?: 0f) * directionSign()
        val distance = directedDistance(event.x)
        val shouldDismiss = distance >= width * DISMISS_DISTANCE_RATIO ||
            (distance > touchSlop && horizontalVelocity >= minimumFlingVelocity)
        if (shouldDismiss) settleOut() else settleBack()
    }

    private fun settleOut() {
        settling = true
        stopTracking()
        animate().cancel()
        animate()
            .translationX(width * directionSign())
            .alpha(1f - DRAG_FADE_RATIO)
            .setDuration(AneMotion.DURATION_MEDIUM_MS)
            .setInterpolator(AneMotion.ENTER_INTERPOLATOR)
            .withEndAction {
                settling = false
                onDismissed()
            }
            .start()
    }

    private fun settleBack() {
        settling = true
        stopTracking()
        animate().cancel()
        animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(AneMotion.DURATION_SHORT_MS)
            .setInterpolator(AneMotion.ENTER_INTERPOLATOR)
            .withEndAction { settling = false }
            .start()
    }

    private fun directedDistance(currentX: Float) = (currentX - downX) * directionSign()

    private fun directionSign() = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f

    private fun stopTracking() {
        tracking = false
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val EDGE_WIDTH_DP = 28
        const val DIRECTION_BIAS = 1.15f
        const val CANCEL_VERTICAL_MULTIPLIER = 1.5f
        const val DISMISS_DISTANCE_RATIO = .32f
        const val DRAG_FADE_RATIO = .18f
        const val MILLISECONDS_PER_SECOND = 1_000
    }
}
