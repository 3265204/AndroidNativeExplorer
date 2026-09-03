package com.ane.filemanager.ui.settings.update

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.ane.filemanager.BuildConfig
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.ui.secondary.SecondaryPageScaffold

/** Second-level settings page dedicated to app version checks and installation policy. */
internal class UpdateSettingsDialog(
    private val host: MainActivity,
    dark: Boolean,
    private val originX: Float,
    private val originY: Float
) {
    private val theme = AneTheme.resolve(host, dark)
    private lateinit var page: SecondaryPageScaffold
    private lateinit var grid: GridLayout

    fun show() {
        page = SecondaryPageScaffold(
            host = host,
            theme = theme,
            title = host.getString(R.string.update_settings_title),
            closeDescription = host.getString(R.string.update_settings_close_page),
            originX = originX,
            originY = originY,
            onUsableWidthChanged = { rebuild() }
        )
        page.summary.text = host.getString(R.string.update_settings_summary)
        grid = GridLayout(host).apply {
            orientation = GridLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(8))
        }
        page.content.addView(ScrollView(host).apply {
            isFillViewport = true
            addView(grid, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        rebuild()
        page.show()
    }

    private fun rebuild() {
        if (!::grid.isInitialized) return
        grid.removeAllViews()
        val cards = listOf(
            informationCard(
                host.getString(R.string.update_settings_version_title),
                host.getString(
                    R.string.update_settings_version_value,
                    BuildConfig.VERSION_NAME,
                    host.getString(if (BuildConfig.DEBUG) {
                        R.string.update_settings_build_beta
                    } else {
                        R.string.update_settings_build_release
                    }),
                    BuildConfig.VERSION_CODE
                )
            ),
            actionCard(
                host.getString(R.string.settings_check_updates_title),
                host.getString(R.string.update_settings_check_hint),
                host.updateController::checkManually
            ),
            switchCard(
                host.getString(R.string.settings_automatic_updates_title),
                host.getString(R.string.settings_automatic_updates_hint),
                host.updateController.automaticUpdatesEnabled
            ) { enabled -> host.updateController.automaticUpdatesEnabled = enabled }
        )
        val columns = (page.usableWidthDp / MIN_CARD_WIDTH_DP).coerceIn(1, MAX_COLUMNS)
        grid.columnCount = columns
        cards.forEachIndexed { index, card ->
            val row = index / columns
            val column = index % columns
            grid.addView(card, GridLayout.LayoutParams(
                GridLayout.spec(row), GridLayout.spec(column, 1, 1f)
            ).apply {
                width = 0
                height = -2
                setMargins(
                    if (column == 0) 0 else dp(5),
                    0,
                    if (column == columns - 1) 0 else dp(5),
                    dp(10)
                )
            })
        }
    }

    private fun informationCard(title: String, value: String): View =
        cardText(title, value).apply { contentDescription = "$title, $value" }

    private fun actionCard(title: String, hint: String, action: () -> Unit): View =
        LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(13), dp(13))
            background = rounded(theme.surface, 18f, theme.outline)
            addView(cardTextContent(title, hint), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(host.getString(R.string.settings_choice_indicator), 27f, theme.primary).apply {
                gravity = Gravity.CENTER
            })
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $hint"
            setOnClickListener { action() }
        }

    private fun switchCard(
        title: String,
        hint: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(13), dp(13), dp(13))
        background = rounded(theme.surface, 18f, theme.outline)
        addView(cardTextContent(title, hint), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(host).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
        })
    }

    private fun cardText(title: String, value: String): LinearLayout =
        cardTextContent(title, value).apply {
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(theme.surface, 18f, theme.outline)
        }

    private fun cardTextContent(title: String, value: String) = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 14f, theme.muted))
        addView(label(value, 17f, theme.text, Typeface.BOLD).apply {
            setPadding(0, dp(4), dp(8), 0)
        })
    }

    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(host).apply {
            text = value
            textSize = size
            setTextColor(color)
            setTypeface(typeface, style)
        }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int) = (value * host.resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * host.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val MIN_CARD_WIDTH_DP = 320
        const val MAX_COLUMNS = 2
    }
}
