package com.ane.filemanager.ui.theme

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Semantic color roles. Callers never depend on whether colors came from Monet or a fallback. */
internal data class AppThemePalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surface2: Int,
    val text: Int,
    val muted: Int,
    val outline: Int,
    val primary: Int,
    val selected: Int,
    val danger: Int
) {
    companion object {
        fun resolve(context: Context, dark: Boolean): AppThemePalette {
            val fallback = fallback(dark)
            if (Build.VERSION.SDK_INT < 31) return fallback
            val surface = system(context, if (dark) "system_neutral1_800" else "system_neutral1_10", fallback.surface)
            val primary = system(context, if (dark) "system_accent1_300" else "system_accent1_600", fallback.primary)
            return AppThemePalette(
                dark = dark,
                background = system(context, if (dark) "system_neutral1_900" else "system_neutral1_50", fallback.background),
                surface = surface,
                surface2 = system(context, if (dark) "system_neutral2_800" else "system_neutral2_50", fallback.surface2),
                text = system(context, if (dark) "system_neutral1_50" else "system_neutral1_900", fallback.text),
                muted = system(context, if (dark) "system_neutral2_200" else "system_neutral2_700", fallback.muted),
                outline = system(context, if (dark) "system_neutral2_700" else "system_neutral2_200", fallback.outline),
                primary = primary,
                selected = blend(surface, primary, if (dark) .34f else .18f),
                danger = system(
                    context,
                    if (dark) "system_accent3_300" else "system_accent3_600",
                    fallback.danger
                )
            )
        }

        private fun fallback(dark: Boolean) = if (dark) AppThemePalette(
            true,
            Color.rgb(18, 22, 29), Color.rgb(28, 34, 43), Color.rgb(38, 46, 57),
            Color.rgb(235, 240, 247), Color.rgb(160, 171, 186), Color.rgb(57, 67, 80),
            Color.rgb(59, 130, 246), Color.rgb(31, 69, 112), Color.rgb(255, 145, 145)
        ) else AppThemePalette(
            false,
            Color.rgb(248, 250, 252), Color.WHITE, Color.rgb(241, 245, 249),
            Color.rgb(25, 33, 45), Color.rgb(100, 116, 139), Color.rgb(226, 232, 240),
            Color.rgb(59, 130, 246), Color.rgb(219, 234, 254), Color.rgb(190, 45, 45)
        )

        private fun system(context: Context, name: String, fallback: Int): Int {
            val id = context.resources.getIdentifier(name, "color", "android")
            return if (id == 0) fallback else runCatching {
                context.resources.getColor(id, context.theme)
            }.getOrDefault(fallback)
        }

        private fun blend(first: Int, second: Int, ratio: Float): Int {
            val inverse = 1f - ratio
            return Color.rgb(
                (Color.red(first) * inverse + Color.red(second) * ratio).toInt(),
                (Color.green(first) * inverse + Color.green(second) * ratio).toInt(),
                (Color.blue(first) * inverse + Color.blue(second) * ratio).toInt()
            )
        }
    }
}
