package com.ane.filemanager.plugin.api.ui

import android.content.Context

/** Host-compatible typography policy for plugin-owned editor and console surfaces. */
object AneTypography {
    fun editorTextSp(context: Context): Float =
        (applicationTextSp(context) - 1).coerceIn(12, 20).toFloat()

    fun terminalTextSp(context: Context): Int =
        (applicationTextSp(context) - 2).coerceIn(11, 16)

    private fun applicationTextSp(context: Context): Int = context
        .getSharedPreferences(APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(TEXT_SIZE_PREFERENCE, DEFAULT_TEXT_SP)

    private const val APPEARANCE_PREFERENCES = "appearance"
    private const val TEXT_SIZE_PREFERENCE = "textSp"
    private const val DEFAULT_TEXT_SP = 16
}
