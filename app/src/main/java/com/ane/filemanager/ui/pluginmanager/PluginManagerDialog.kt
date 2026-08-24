package com.ane.filemanager.ui.pluginmanager

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.pluginmanager.PluginManagerEntry
import com.ane.filemanager.pluginmanager.PluginRegistry
import com.ane.filemanager.pluginmanager.PluginSource
import com.ane.filemanager.ui.dialog.AneDialog
import com.ane.filemanager.ui.dialog.AneDialogAction
import com.ane.filemanager.ui.secondary.SecondaryPageScaffold
import com.ane.filemanager.ui.theme.AppThemePalette
import java.io.File

/** App-owned second-level plugin page. It never delegates ZIP selection to another file manager. */
internal class PluginManagerDialog(
    private val host: MainActivity,
    private val plugins: PluginRegistry,
    private val directory: File,
    private val originX: Float,
    private val originY: Float
) {
    private val dark = host.getSharedPreferences("appearance", 0).getBoolean("dark", false)
    private val theme = AppThemePalette.resolve(host, dark)
    private val surface = theme.surface
    private val text = theme.text
    private val muted = theme.muted
    private val divider = theme.outline
    private val accent = theme.primary
    private lateinit var page: SecondaryPageScaffold
    private lateinit var summary: TextView
    private lateinit var cards: LinearLayout

    fun show() {
        page = SecondaryPageScaffold(
            host = host,
            theme = theme,
            title = host.getString(R.string.plugin_manager_title),
            closeDescription = host.getString(R.string.plugin_manager_close),
            originX = originX,
            originY = originY
        )
        val content = page.content
        summary = page.summary
        content.addView(importCard(), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })
        cards = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(ScrollView(host).apply {
            isFillViewport = true
            addView(cards, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        rebuildCards()
        page.show()
    }

    private fun importCard(): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(13), dp(14), dp(13))
        background = rounded(if (dark) Color.rgb(27, 49, 76) else Color.rgb(231, 241, 255), 18f,
            strokeColor = if (dark) Color.rgb(55, 91, 135) else Color.rgb(177, 207, 245))
        addView(TextView(host).apply {
            text = host.getString(R.string.plugin_zip_badge)
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 12f)
        }, LinearLayout.LayoutParams(dp(46), dp(38)))
        addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(label(host.getString(R.string.plugin_import_current_folder), 16f, text, Typeface.BOLD))
            addView(label(directory.absolutePath, 12f, muted).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(host.getString(R.string.plugin_choose_zip), 14f, accent, Typeface.BOLD))
        isClickable = true
        isFocusable = true
        setOnClickListener { chooseZip() }
    }

    private fun rebuildCards() {
        val entries = plugins.managerEntries()
        cards.removeAllViews()
        val enabled = entries.count { it.enabled && it.error == null }
        summary.text = host.getString(R.string.plugin_manager_summary, enabled, entries.size)
        entries.forEach { entry ->
            cards.addView(pluginCard(entry), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
        }
    }

    private fun pluginCard(entry: PluginManagerEntry): View {
        val descriptor = entry.descriptor
        val stripeColor = CARD_COLORS[(descriptor.id.hashCode() and Int.MAX_VALUE) % CARD_COLORS.size]
        return LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(surface, 20f, divider)
            addView(View(host).apply { setBackgroundColor(stripeColor) }, LinearLayout.LayoutParams(dp(5), -1))
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(14), dp(13))
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(host).apply {
                        text = descriptor.name.firstOrNull()?.uppercase()
                            ?: host.getString(R.string.plugin_fallback_initial)
                        gravity = Gravity.CENTER
                        textSize = 18f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        background = rounded(stripeColor, 14f)
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, dp(6), 0)
                        addView(label(descriptor.name, 17f, text, Typeface.BOLD))
                        addView(label(host.getString(R.string.plugin_card_version, descriptor.version), 12f, muted))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Switch(host).apply {
                        isChecked = entry.enabled
                        isEnabled = entry.error == null || !entry.enabled
                        contentDescription = host.getString(
                            if (entry.enabled) R.string.plugin_disable else R.string.plugin_enable
                        )
                        setOnCheckedChangeListener { _, checked ->
                            if (checked != entry.enabled) {
                                plugins.setEnabled(descriptor.id, checked)
                                cards.post(::rebuildCards)
                            }
                        }
                    })
                })
                val description = descriptor.description.ifBlank {
                    host.getString(R.string.plugin_no_description)
                }
                addView(label(description, 14f, muted).apply {
                    setPadding(0, dp(11), 0, dp(10))
                })
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(statusChip(entry))
                    addView(label(
                        host.getString(if (descriptor.source == PluginSource.BUNDLED) {
                            R.string.plugin_source_bundled
                        } else R.string.plugin_source_imported),
                        12f, muted
                    ).apply { setPadding(dp(10), 0, 0, 0) })
                    addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
                    if (descriptor.source == PluginSource.IMPORTED) {
                        addView(Button(host).apply {
                            text = host.getString(R.string.plugin_remove)
                            isAllCaps = false
                            setTextColor(if (dark) Color.rgb(255, 145, 145) else Color.rgb(184, 48, 48))
                            background = rounded(if (dark) Color.rgb(65, 38, 42) else Color.rgb(255, 238, 238), 12f)
                            setOnClickListener { confirmRemove(entry) }
                        }, LinearLayout.LayoutParams(-2, dp(38)))
                    }
                })
                entry.error?.let { problem ->
                    addView(label(host.getString(R.string.plugin_load_error, problem), 12f,
                        if (dark) Color.rgb(255, 145, 145) else Color.rgb(184, 48, 48)).apply {
                        setPadding(0, dp(8), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun statusChip(entry: PluginManagerEntry): TextView {
        val failed = entry.error != null
        val enabled = entry.enabled && !failed
        val foreground = when {
            failed -> if (dark) Color.rgb(255, 153, 153) else Color.rgb(173, 43, 43)
            enabled -> if (dark) Color.rgb(132, 224, 167) else Color.rgb(31, 125, 70)
            else -> muted
        }
        val background = when {
            failed -> if (dark) Color.rgb(66, 38, 43) else Color.rgb(255, 234, 234)
            enabled -> if (dark) Color.rgb(31, 62, 48) else Color.rgb(229, 247, 235)
            else -> if (dark) Color.rgb(43, 50, 61) else Color.rgb(236, 240, 245)
        }
        return label(host.getString(when {
            failed -> R.string.plugin_status_error
            enabled -> R.string.plugin_status_enabled
            else -> R.string.plugin_status_disabled
        }), 12f, foreground, Typeface.BOLD).apply {
            setPadding(dp(9), dp(4), dp(9), dp(4))
            this.background = rounded(background, 10f)
        }
    }

    private fun chooseZip() {
        val archives = runCatching {
            directory.listFiles()?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
        }.getOrDefault(emptyList())
        if (archives.isEmpty()) {
            AneDialog.message(host, host.getString(R.string.plugin_no_zip_title),
                host.getString(R.string.plugin_no_zip_message, directory.absolutePath), listOf(
                    AneDialogAction(host.getString(R.string.plugin_dialog_confirm), primary = true)
                ))
            return
        }
        AneDialog.choices(host, host.getString(R.string.plugin_choose_zip_title),
            archives.map(File::getName), host.getString(R.string.plugin_dialog_cancel)) {
            confirmInstall(archives[it])
        }
    }

    private fun confirmInstall(file: File) {
        AneDialog.message(host, host.getString(R.string.plugin_install_warning_title),
            host.getString(R.string.plugin_install_zip_warning, file.name), listOf(
                AneDialogAction(host.getString(R.string.plugin_dialog_cancel)),
                AneDialogAction(host.getString(R.string.plugin_install), primary = true) {
                    plugins.install(file) { success -> if (success) rebuildCards() }
                }
            ))
    }

    private fun confirmRemove(entry: PluginManagerEntry) {
        AneDialog.message(host, host.getString(R.string.plugin_remove_title),
            host.getString(R.string.plugin_remove_message, entry.descriptor.name), listOf(
                AneDialogAction(host.getString(R.string.plugin_dialog_cancel)),
                AneDialogAction(host.getString(R.string.plugin_remove), destructive = true) {
                    plugins.remove(entry.descriptor.id)
                    rebuildCards()
                }
            ))
    }

    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(host).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * host.resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * host.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        val CARD_COLORS = intArrayOf(
            Color.rgb(55, 112, 201), Color.rgb(37, 145, 124), Color.rgb(156, 91, 195),
            Color.rgb(205, 111, 45), Color.rgb(194, 70, 100)
        )
    }
}
