package com.ane.filemanager.plugin.terminal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build

/** Mirrors ANE's semantic palette without depending on host-internal UI classes. */
internal data class TerminalPalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surface2: Int,
    val text: Int,
    val muted: Int,
    val outline: Int,
    val primary: Int
) {
    companion object {
        fun resolve(context: Context): TerminalPalette {
            val dark = context.getSharedPreferences("appearance", 0).getBoolean("dark", false)
            val fallback = fallback(dark)
            if (Build.VERSION.SDK_INT < 31) return fallback
            return TerminalPalette(
                dark = dark,
                background = system(
                    context,
                    if (dark) "system_neutral1_900" else "system_neutral1_50",
                    fallback.background
                ),
                surface = system(
                    context,
                    if (dark) "system_neutral1_800" else "system_neutral1_10",
                    fallback.surface
                ),
                surface2 = system(
                    context,
                    if (dark) "system_neutral2_800" else "system_neutral2_50",
                    fallback.surface2
                ),
                text = system(
                    context,
                    if (dark) "system_neutral1_50" else "system_neutral1_900",
                    fallback.text
                ),
                muted = system(
                    context,
                    if (dark) "system_neutral2_200" else "system_neutral2_700",
                    fallback.muted
                ),
                outline = system(
                    context,
                    if (dark) "system_neutral2_700" else "system_neutral2_200",
                    fallback.outline
                ),
                primary = system(
                    context,
                    if (dark) "system_accent1_300" else "system_accent1_600",
                    fallback.primary
                )
            )
        }

        fun rounded(fill: Int, outline: Int, radiusPx: Float, strokePx: Int = 1) =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(fill)
                setStroke(strokePx, outline)
            }

        private fun fallback(dark: Boolean) = if (dark) TerminalPalette(
            true,
            Color.rgb(18, 22, 29),
            Color.rgb(28, 34, 43),
            Color.rgb(38, 46, 57),
            Color.rgb(235, 240, 247),
            Color.rgb(160, 171, 186),
            Color.rgb(57, 67, 80),
            Color.rgb(59, 130, 246)
        ) else TerminalPalette(
            false,
            Color.rgb(248, 250, 252),
            Color.WHITE,
            Color.rgb(241, 245, 249),
            Color.rgb(25, 33, 45),
            Color.rgb(100, 116, 139),
            Color.rgb(226, 232, 240),
            Color.rgb(59, 130, 246)
        )

        @SuppressLint("DiscouragedApi")
        private fun system(context: Context, name: String, fallback: Int): Int {
            val id = context.resources.getIdentifier(name, "color", "android")
            return if (id == 0) fallback else runCatching {
                context.resources.getColor(id, context.theme)
            }.getOrDefault(fallback)
        }
    }
}
