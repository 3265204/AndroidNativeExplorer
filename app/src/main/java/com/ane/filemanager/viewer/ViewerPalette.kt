package com.ane.filemanager.viewer

import android.content.Context
import android.graphics.Color

internal data class ViewerPalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    val primary: Int,
    val divider: Int
) {
    companion object {
        fun from(context: Context): ViewerPalette {
            val dark = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getBoolean("dark", false)
            return if (dark) ViewerPalette(
                true, Color.rgb(18, 22, 29), Color.rgb(28, 34, 43), Color.rgb(235, 240, 247),
                Color.rgb(160, 171, 186), Color.rgb(59, 130, 246), Color.rgb(52, 63, 77)
            ) else ViewerPalette(
                false, Color.rgb(248, 250, 252), Color.WHITE, Color.rgb(25, 33, 45),
                Color.rgb(100, 116, 139), Color.rgb(59, 130, 246), Color.rgb(226, 232, 240)
            )
        }
    }
}
