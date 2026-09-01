@file:Suppress("DEPRECATION")

package com.ane.filemanager.plugin.api.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.Window

/** Applies ANE's system-bar colors and icon contrast to a plugin-owned Activity. */
fun Activity.applyAneSystemBars(
    theme: AneTheme,
    navigationColor: Int = theme.background,
    lightNavigationIcons: Boolean = theme.dark
) {
    window.applyAneSystemBars(theme, navigationColor, lightNavigationIcons)
}

fun Window.applyAneSystemBars(
    theme: AneTheme,
    navigationColor: Int = theme.background,
    lightNavigationIcons: Boolean = theme.dark
) {
    statusBarColor = theme.surface
    this.navigationBarColor = navigationColor
    if (Build.VERSION.SDK_INT >= 30) {
        val statusMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        val navigationMask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val mask = statusMask or navigationMask
        var appearance = if (theme.dark) 0 else statusMask
        if (!lightNavigationIcons) appearance = appearance or navigationMask
        decorView.windowInsetsController?.setSystemBarsAppearance(appearance, mask)
    } else {
        var flags = decorView.systemUiVisibility
        flags = if (!theme.dark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= 26) {
            flags = if (!lightNavigationIcons) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        decorView.systemUiVisibility = flags
    }
}

/** Adds system bars and display cutouts to the view's original padding. */
fun View.applyAneSystemInsets() {
    val initial = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { view, insets ->
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val value = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            intArrayOf(value.left, value.top, value.right, value.bottom)
        } else {
            intArrayOf(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
        }
        view.setPadding(
            initial[0] + safe[0],
            initial[1] + safe[1],
            initial[2] + safe[2],
            initial[3] + safe[3]
        )
        insets
    }
    requestApplyInsets()
}

/** Convenience for media screens that intentionally keep the navigation region black. */
fun Activity.applyAneMediaSystemBars(theme: AneTheme) =
    applyAneSystemBars(theme, navigationColor = Color.BLACK, lightNavigationIcons = true)


