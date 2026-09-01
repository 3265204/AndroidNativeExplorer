package com.ane.filemanager.plugin.text.editor

import android.app.Activity
import android.view.View
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.HostUi

internal fun Activity.applyTextEditorSystemBars(theme: AneTheme) = HostUi.applySystemBars(this, theme)

internal fun View.applyTextEditorSystemInsets() = HostUi.applySystemInsets(this)
