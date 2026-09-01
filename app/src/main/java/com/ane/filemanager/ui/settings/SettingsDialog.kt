package com.ane.filemanager.ui.settings

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.localization.LanguageMode
import com.ane.filemanager.ui.appearance.AppearanceController
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.ui.model.LayoutMode
import com.ane.filemanager.ui.secondary.SecondaryPageScaffold
import com.ane.filemanager.plugin.api.ui.AneTheme

/** Full-screen settings owner; values remain persisted by their focused controllers. */
internal class SettingsDialog(
    private val host: MainActivity,
    private val appearance: AppearanceController,
    private val originX: Float,
    private val originY: Float,
    private val onLayoutChanged: () -> Unit,
    private val onAppearanceChanged: () -> Unit,
    private val onFilesChanged: () -> Unit,
    private val openPermissionSettings: () -> Unit
) {
    private val theme = AneTheme.resolve(host, appearance.dark)
    private lateinit var page: SecondaryPageScaffold
    private lateinit var grid: GridLayout

    fun show() {
        page = SecondaryPageScaffold(
            host = host,
            theme = theme,
            title = host.getString(R.string.settings_title),
            closeDescription = host.getString(R.string.settings_close_page),
            originX = originX,
            originY = originY,
            onUsableWidthChanged = { rebuild() }
        )
        page.summary.text = host.getString(R.string.settings_summary)
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
        val cards = buildList {
            add(choiceCard(
                host.getString(R.string.setting_language),
                host.getString(AppLanguage.current(host).labelResource),
                ::chooseLanguage
            ))
            add(choiceCard(
                host.getString(R.string.settings_theme_title),
                host.getString(if (appearance.dark) R.string.theme_dark else R.string.theme_light),
                ::chooseTheme
            ))
            add(sliderCard(
                title = host.getString(R.string.settings_text_size_title),
                value = appearance.textSp,
                minimum = AppearanceController.TEXT_SIZE_MIN_SP,
                maximum = AppearanceController.TEXT_SIZE_MAX_SP,
                valueLabel = { host.getString(R.string.settings_value_sp, it) }
            ) {
                appearance.setTextSize(it)
                onAppearanceChanged()
            })
            add(sliderCard(
                title = host.getString(R.string.settings_icon_size_title),
                value = appearance.iconDp,
                minimum = AppearanceController.ICON_SIZE_MIN_DP,
                maximum = AppearanceController.ICON_SIZE_MAX_DP,
                valueLabel = { host.getString(R.string.settings_value_dp, it) }
            ) {
                appearance.setIconSize(it)
                onAppearanceChanged()
            })
            add(sliderCard(
                title = host.getString(R.string.settings_spacing_title),
                value = appearance.spacingDp,
                minimum = AppearanceController.SPACING_MIN_DP,
                maximum = AppearanceController.SPACING_MAX_DP,
                valueLabel = { host.getString(R.string.settings_value_dp, it) }
            ) {
                appearance.setSpacing(it)
                onAppearanceChanged()
            })
            add(choiceCard(
                host.getString(R.string.settings_layout_title),
                host.getString(if (appearance.layoutMode == LayoutMode.LIST) {
                    R.string.layout_list
                } else {
                    R.string.layout_grid
                }),
                ::chooseLayout
            ))
            add(switchCard(
                host.getString(R.string.settings_hidden_title),
                host.getString(R.string.settings_hidden_hint),
                appearance.showHidden
            ) { checked ->
                appearance.setShowHidden(checked)
                onFilesChanged()
            })
            if (!host.hasStorageAccess()) add(choiceCard(
                host.getString(R.string.setting_storage_permission),
                host.getString(R.string.settings_storage_permission_hint),
                openPermissionSettings
            ))
        }
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

    private fun choiceCard(title: String, value: String, action: () -> Unit): View =
        LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(13), dp(13))
            background = rounded(theme.surface, 18f, theme.outline)
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 14f, theme.muted))
                addView(label(value, 17f, theme.text, Typeface.BOLD).apply {
                    setPadding(0, dp(4), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(host.getString(R.string.settings_choice_indicator), 27f, theme.primary).apply {
                gravity = Gravity.CENTER
            })
            isClickable = true
            isFocusable = true
            contentDescription = host.getString(R.string.settings_choice_description, title, value)
            setOnClickListener { action() }
        }

    private fun sliderCard(
        title: String,
        value: Int,
        minimum: Int,
        maximum: Int,
        valueLabel: (Int) -> String,
        onChanged: (Int) -> Unit
    ): View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(12), dp(15), dp(10))
        background = rounded(theme.surface, 18f, theme.outline)
        lateinit var currentValue: TextView
        addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 15f, theme.text, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f))
            currentValue = label(valueLabel(value), 14f, theme.primary, Typeface.BOLD)
            addView(currentValue)
        })
        addView(SeekBar(host).apply {
            max = maximum - minimum
            progress = value.coerceIn(minimum, maximum) - minimum
            progressTintList = ColorStateList.valueOf(theme.primary)
            thumbTintList = ColorStateList.valueOf(theme.primary)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val selected = minimum + progress
                    currentValue.text = valueLabel(selected)
                    if (fromUser) onChanged(selected)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
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
        addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, theme.text, Typeface.BOLD))
            addView(label(hint, 12.5f, theme.muted).apply { setPadding(0, dp(4), dp(8), 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(host).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> if (value != appearance.showHidden) onChanged(value) }
        })
    }

    private fun chooseLanguage() {
        val modes = LanguageMode.entries
        AneDialog.choices(
            activity = host,
            title = host.getString(R.string.settings_language_title),
            labels = modes.map { host.getString(it.labelResource) },
            cancelLabel = host.getString(R.string.dialog_cancel),
            colors = theme
        ) { index ->
            val selected = modes[index]
            if (selected != AppLanguage.current(host)) {
                AppLanguage.select(host, selected)
                host.recreate()
            }
        }
    }

    private fun chooseTheme() {
        val values = listOf(false, true)
        AneDialog.choices(
            activity = host,
            title = host.getString(R.string.settings_theme_title),
            labels = listOf(host.getString(R.string.theme_light), host.getString(R.string.theme_dark)),
            cancelLabel = host.getString(R.string.dialog_cancel),
            colors = theme
        ) { index ->
            val selected = values[index]
            if (selected != appearance.dark) {
                appearance.setDark(selected)
                host.recreate()
            }
        }
    }

    private fun chooseLayout() {
        val values = listOf(LayoutMode.LIST, LayoutMode.GRID)
        AneDialog.choices(
            activity = host,
            title = host.getString(R.string.settings_layout_title),
            labels = listOf(host.getString(R.string.layout_list), host.getString(R.string.layout_grid)),
            cancelLabel = host.getString(R.string.dialog_cancel),
            colors = theme
        ) { index ->
            val selected = values[index]
            if (selected != appearance.layoutMode) {
                appearance.setLayoutMode(selected)
                onLayoutChanged()
                rebuild()
            }
        }
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
