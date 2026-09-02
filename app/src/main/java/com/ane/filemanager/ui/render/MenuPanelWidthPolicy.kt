package com.ane.filemanager.ui.render

/** Fits every visible menu level into the horizontal safe area without overlapping panels. */
internal object MenuPanelWidthPolicy {
    fun fit(
        desiredWidths: List<Float>,
        availableWidth: Float,
        gap: Float,
        minimumWidth: Float
    ): List<Float> {
        if (desiredWidths.isEmpty()) return emptyList()
        val usableWidth = (availableWidth - gap * (desiredWidths.size - 1)).coerceAtLeast(0f)
        if (desiredWidths.sum() <= usableWidth) return desiredWidths

        val fittedMinimum = minOf(minimumWidth, usableWidth / desiredWidths.size)
        val flexible = desiredWidths.map { (it - fittedMinimum).coerceAtLeast(0f) }
        val flexibleTotal = flexible.sum()
        val remaining = (usableWidth - fittedMinimum * desiredWidths.size).coerceAtLeast(0f)
        return if (flexibleTotal <= 0f) {
            List(desiredWidths.size) { usableWidth / desiredWidths.size }
        } else {
            desiredWidths.indices.map { index ->
                fittedMinimum + remaining * flexible[index] / flexibleTotal
            }
        }
    }
}
