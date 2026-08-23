package com.ane.filemanager.viewer

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

internal fun Activity.applyViewerSystemBars(
    palette: ViewerPalette,
    navigationColor: Int = palette.background,
    lightNavigationBars: Boolean = !palette.dark
) {
    window.statusBarColor = palette.surface
    window.navigationBarColor = navigationColor
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val appearance = (if (palette.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) or
            (if (lightNavigationBars) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)
        // Accessing decorView also installs it when this is called early in onCreate.
        window.decorView.windowInsetsController?.setSystemBarsAppearance(appearance, mask)
    } else {
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        flags = if (!palette.dark && Build.VERSION.SDK_INT >= 23) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            flags = if (lightNavigationBars) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
}

/** Keeps viewer content out of status bars, navigation bars, DeX taskbars and cutouts. */
fun View.applyViewerSystemInsets() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            left = safe.left
            top = safe.top
            right = safe.right
            bottom = safe.bottom
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        view.setPadding(
            initialLeft + left,
            initialTop + top,
            initialRight + right,
            initialBottom + bottom
        )
        insets
    }
    requestApplyInsets()
}
