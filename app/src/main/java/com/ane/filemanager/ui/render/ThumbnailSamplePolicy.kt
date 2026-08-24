package com.ane.filemanager.ui.render

/** Chooses a power-of-two sample without decoding a full-resolution intermediate thumbnail. */
internal object ThumbnailSamplePolicy {
    private const val MIN_PIXEL_BUDGET = 1_000_000L
    private const val TARGET_PIXEL_MULTIPLIER = 4L

    fun sampleFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        val pixelBudget = maxOf(MIN_PIXEL_BUDGET, width.toLong() * height * TARGET_PIXEL_MULTIPLIER)
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2) {
            val currentWidth = scaled(sourceWidth, sample)
            val currentHeight = scaled(sourceHeight, sample)
            val next = sample * 2
            val nextWidth = scaled(sourceWidth, next)
            val nextHeight = scaled(sourceHeight, next)
            val exceedsBudget = currentWidth.toLong() * currentHeight > pixelBudget
            val nextStillCoversTarget = nextWidth >= width && nextHeight >= height
            if (!exceedsBudget && !nextStillCoversTarget) break
            sample = next
        }
        return sample
    }

    private fun scaled(value: Int, sample: Int): Int =
        ((value.toLong() + sample - 1L) / sample).toInt()
}
