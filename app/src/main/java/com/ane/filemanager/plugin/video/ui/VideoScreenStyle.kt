package com.ane.filemanager.plugin.video.ui

import android.app.Activity
import android.view.View
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.HostUi

internal fun Activity.applyVideoSystemBars(theme: AneTheme) = HostUi.applyMediaSystemBars(this, theme)

internal fun View.applyVideoSystemInsets() = HostUi.applySystemInsets(this)
