@file:Suppress("DEPRECATION")

package com.ane.filemanager.plugin.text.editor

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

internal data class TextEditorPalette(
    val dark: Boolean, val background: Int, val surface: Int, val text: Int,
    val muted: Int, val primary: Int, val divider: Int
) {
    companion object {
        fun from(context: Context): TextEditorPalette {
            val dark = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("dark", false)
            return if (dark) TextEditorPalette(true, Color.rgb(18, 22, 29), Color.rgb(28, 34, 43),
                Color.rgb(235, 240, 247), Color.rgb(160, 171, 186), Color.rgb(59, 130, 246), Color.rgb(52, 63, 77))
            else TextEditorPalette(false, Color.rgb(248, 250, 252), Color.WHITE, Color.rgb(25, 33, 45),
                Color.rgb(100, 116, 139), Color.rgb(59, 130, 246), Color.rgb(226, 232, 240))
        }
    }
}

internal fun Activity.applyTextEditorSystemBars(palette: TextEditorPalette) {
    window.statusBarColor = palette.surface
    window.navigationBarColor = palette.background
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.decorView.windowInsetsController?.setSystemBarsAppearance(if (palette.dark) 0 else mask, mask)
    } else {
        var flags = window.decorView.systemUiVisibility
        flags = if (!palette.dark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= 26) {
            flags = if (!palette.dark) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = flags
    }
}

internal fun View.applyTextEditorSystemInsets() {
    val initial = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { view, insets ->
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val value = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(value.left, value.top, value.right, value.bottom)
        } else {
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        view.setPadding(initial[0] + safe[0], initial[1] + safe[1], initial[2] + safe[2], initial[3] + safe[3])
        insets
    }
    requestApplyInsets()
}
