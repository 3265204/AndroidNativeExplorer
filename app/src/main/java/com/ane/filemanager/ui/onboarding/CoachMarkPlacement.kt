package com.ane.filemanager.ui.onboarding

import kotlin.math.abs

/** Platform-free coach-mark placement so popups can avoid the content they explain. */
internal object CoachMarkPlacement {
    enum class VerticalPreference { TOP, CENTER, BOTTOM }

    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f

        fun intersectionArea(other: Box): Float {
            val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
            val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
            return width * height
        }
    }

    fun place(
        viewportWidth: Float,
        viewportHeight: Float,
        popupWidth: Float,
        popupHeight: Float,
        margin: Float,
        gap: Float,
        priorityAvoid: List<Box>,
        contentAvoid: List<Box>,
        preference: VerticalPreference
    ): Box {
        val minLeft = margin
        val maxLeft = (viewportWidth - margin - popupWidth).coerceAtLeast(minLeft)
        val minTop = margin
        val maxTop = (viewportHeight - margin - popupHeight).coerceAtLeast(minTop)
        val centeredLeft = ((viewportWidth - popupWidth) / 2f).coerceIn(minLeft, maxLeft)
        val centeredTop = ((viewportHeight - popupHeight) / 2f).coerceIn(minTop, maxTop)
        val preferredTop = when (preference) {
            VerticalPreference.TOP -> minTop
            VerticalPreference.CENTER -> centeredTop
            VerticalPreference.BOTTOM -> maxTop
        }
        val obstacles = priorityAvoid + contentAvoid
        val lefts = buildList {
            add(centeredLeft)
            add(minLeft)
            add(maxLeft)
            obstacles.forEach { obstacle ->
                add((obstacle.left - gap - popupWidth).coerceIn(minLeft, maxLeft))
                add((obstacle.right + gap).coerceIn(minLeft, maxLeft))
            }
        }.distinct()
        val tops = buildList {
            add(preferredTop)
            add(if (preference == VerticalPreference.TOP) maxTop else minTop)
            add(centeredTop)
            obstacles.forEach { obstacle ->
                add((obstacle.top - gap - popupHeight).coerceIn(minTop, maxTop))
                add((obstacle.bottom + gap).coerceIn(minTop, maxTop))
            }
        }.distinct()

        return lefts.flatMap { left ->
            tops.map { top -> Box(left, top, left + popupWidth, top + popupHeight) }
        }.minWithOrNull(
            compareBy<Box> { candidate ->
                priorityAvoid.sumOf { candidate.intersectionArea(it).toDouble() }
            }.thenBy { candidate ->
                contentAvoid.sumOf { candidate.intersectionArea(it).toDouble() }
            }.thenBy { candidate ->
                abs(candidate.top - preferredTop)
            }.thenBy { candidate ->
                abs(candidate.centerX - viewportWidth / 2f)
            }
        ) ?: Box(centeredLeft, preferredTop, centeredLeft + popupWidth, preferredTop + popupHeight)
    }
}
