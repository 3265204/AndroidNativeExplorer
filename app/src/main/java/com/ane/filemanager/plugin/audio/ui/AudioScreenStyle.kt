package com.ane.filemanager.plugin.audio.ui

import android.app.Activity
import android.view.View
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.HostUi

internal fun Activity.applyAudioSystemBars(theme: AneTheme) = HostUi.applySystemBars(this, theme)

internal fun View.applyAudioSystemInsets() = HostUi.applySystemInsets(this)
