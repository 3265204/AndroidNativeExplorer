package com.ane.filemanager.plugin.image.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

internal class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {
    private val workingMatrix = Matrix()
    private var imageScale = 1f
    private var userScale = 1f
    private var translationX = 0f
    private var translationY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    val isZoomed get() = userScale > 1.02f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (userScale * detector.scaleFactor).coerceIn(1f, 6f)
                val factor = next / userScale
                userScale = next
                imageScale *= factor
                // Keep the image point that was under the old two-finger center under the new center.
                translationX = detector.focusX + factor * (translationX - lastFocusX)
                translationY = detector.focusY + factor * (translationY - lastFocusY)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                clampTranslation()
                applyMatrix()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (userScale > 1.05f) {
                    fitImage()
                } else {
                    val factor = 2.5f
                    userScale = factor
                    imageScale *= factor
                    translationX = event.x + factor * (translationX - event.x)
                    translationY = event.y + factor * (translationY - event.y)
                    clampTranslation()
                    applyMatrix()
                }
                return true
            }

            override fun onFling(
                first: MotionEvent?,
                second: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                first ?: return false
                if (isZoomed || scaleDetector.isInProgress) return false
                val dx = second.x - first.x
                val dy = second.y - first.y
                val threshold = 64f * resources.displayMetrics.density
                if (abs(dx) < threshold || abs(dx) < abs(dy) * 1.25f || abs(velocityX) < 350f) return false
                if (dx < 0f) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fitImage()
    }

    override fun setImageBitmap(bitmap: android.graphics.Bitmap?) {
        super.setImageBitmap(bitmap)
        post(::fitImage)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (scaleDetector.isInProgress) {
                lastX = event.x
                lastY = event.y
            } else if (event.pointerCount == 1 && userScale > 1f) {
                translationX += event.x - lastX
                translationY += event.y - lastY
                lastX = event.x
                lastY = event.y
                clampTranslation()
                applyMatrix()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
        }
        return true
    }

    private fun fitImage() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        imageScale = min(
            width.toFloat() / drawable.intrinsicWidth,
            height.toFloat() / drawable.intrinsicHeight
        )
        translationX = (width - drawable.intrinsicWidth * imageScale) / 2f
        translationY = (height - drawable.intrinsicHeight * imageScale) / 2f
        userScale = 1f
        applyMatrix()
    }

    private fun clampTranslation() {
        val drawable = drawable ?: return
        val displayWidth = drawable.intrinsicWidth * imageScale
        val displayHeight = drawable.intrinsicHeight * imageScale
        translationX = if (displayWidth <= width) (width - displayWidth) / 2f
        else translationX.coerceIn(width - displayWidth, 0f)
        translationY = if (displayHeight <= height) (height - displayHeight) / 2f
        else translationY.coerceIn(height - displayHeight, 0f)
    }

    private fun applyMatrix() {
        workingMatrix.setValues(floatArrayOf(
            imageScale, 0f, translationX,
            0f, imageScale, translationY,
            0f, 0f, 1f
        ))
        imageMatrix = workingMatrix
    }
}
