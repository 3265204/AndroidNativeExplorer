package com.ane.filemanager.plugin.image.ui

import android.app.Activity
import android.view.View
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.HostUi

internal fun Activity.applyImageSystemBars(theme: AneTheme) = HostUi.applySystemBars(this, theme)

internal fun View.applyImageSystemInsets() = HostUi.applySystemInsets(this)
