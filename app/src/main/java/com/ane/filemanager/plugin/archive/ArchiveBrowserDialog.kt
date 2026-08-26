package com.ane.filemanager.plugin.archive

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.ui.secondary.SecondaryPageScaffold
import com.ane.filemanager.ui.theme.AppThemePalette
import java.io.File

/** A level-by-level archive browser matching the file manager's second-level pages. */
internal class ArchiveBrowserDialog(
    private val host: MainActivity,
    private val source: File,
    entries: List<ArchiveEntryInfo>,
    private val extractAll: () -> Unit
) {
    private val dark = host.getSharedPreferences("appearance", 0).getBoolean("dark", false)
    private val theme = AppThemePalette.resolve(host, dark)
    private val hierarchy = ArchiveHierarchy(entries)
    private var currentDirectory = ""
    private lateinit var page: SecondaryPageScaffold
    private lateinit var breadcrumbs: LinearLayout
    private lateinit var itemList: LinearLayout

    fun show() {
        page = SecondaryPageScaffold(
            host = host,
            theme = theme,
            title = source.name,
            closeDescription = host.getString(R.string.archive_browser_close),
            originX = host.resources.displayMetrics.widthPixels / 2f,
            originY = host.resources.displayMetrics.heightPixels / 2f
        )
        page.content.addView(buildBreadcrumbs(), LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(7)
            bottomMargin = dp(8)
        })
        itemList = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        page.content.addView(ScrollView(host).apply {
            isFillViewport = true
            addView(itemList, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.content.addView(Button(host).apply {
            text = host.getString(R.string.archive_extract_all)
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(theme.primary, 14f)
            setOnClickListener {
                page.close()
                extractAll()
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        rebuild()
        page.show()
    }

    private fun buildBreadcrumbs() = HorizontalScrollView(host).apply {
        isHorizontalScrollBarEnabled = false
        background = rounded(theme.surface, 14f, theme.outline)
        breadcrumbs = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        addView(breadcrumbs, FrameLayout.LayoutParams(-2, -1))
    }

    private fun rebuild() {
        val items = hierarchy.children(currentDirectory)
        page.summary.text = host.resources.getQuantityString(
            R.plurals.archive_browser_summary,
            items.size,
            displayPath(),
            items.size
        )
        rebuildBreadcrumbs()
        itemList.removeAllViews()
        if (currentDirectory.isNotEmpty()) {
            itemList.addView(upRow(), rowLayout())
        }
        if (items.isEmpty()) {
            itemList.addView(label(host.getString(R.string.archive_browser_empty), 15f, theme.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(56), dp(12), dp(56))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            items.forEach { item -> itemList.addView(itemRow(item), rowLayout()) }
        }
    }

    private fun rebuildBreadcrumbs() {
        breadcrumbs.removeAllViews()
        val segments = currentDirectory.split('/').filter(String::isNotBlank)
        addBreadcrumb(host.getString(R.string.archive_browser_root), "", segments.isEmpty())
        var path = ""
        segments.forEachIndexed { index, segment ->
            breadcrumbs.addView(label("›", 17f, theme.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
            })
            path = if (path.isEmpty()) segment else "$path/$segment"
            addBreadcrumb(segment, path, index == segments.lastIndex)
        }
    }

    private fun addBreadcrumb(name: String, path: String, current: Boolean) {
        breadcrumbs.addView(label(
            name,
            14f,
            if (current) theme.text else theme.primary,
            if (current) Typeface.BOLD else Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            isClickable = !current
            if (!current) setOnClickListener {
                currentDirectory = path
                rebuild()
            }
        }, LinearLayout.LayoutParams(-2, -1))
    }

    private fun upRow() = browserRow(
        badge = "..",
        badgeColor = folderColor(),
        name = host.getString(R.string.archive_browser_parent),
        detail = host.getString(R.string.archive_browser_parent_detail),
        chevron = true
    ) {
        currentDirectory = hierarchy.parent(currentDirectory)
        rebuild()
    }

    private fun itemRow(item: ArchiveBrowserItem): View {
        val detail = if (item.directory) {
            host.resources.getQuantityString(
                R.plurals.archive_browser_folder_items,
                item.childCount,
                item.childCount
            )
        } else {
            item.size?.let { Formatter.formatShortFileSize(host, it) }
                ?: host.getString(R.string.archive_browser_unknown_size)
        }
        val badge = if (item.directory) {
            host.getString(R.string.archive_browser_folder_badge)
        } else {
            item.name.substringAfterLast('.', "").take(4).uppercase().ifBlank {
                host.getString(R.string.archive_browser_file_badge)
            }
        }
        return browserRow(
            badge = badge,
            badgeColor = if (item.directory) folderColor() else fileColor(item.name),
            name = item.name,
            detail = detail,
            chevron = item.directory,
            action = if (item.directory) ({
                currentDirectory = item.path
                rebuild()
            }) else null
        )
    }

    private fun browserRow(
        badge: String,
        badgeColor: Int,
        name: String,
        detail: String,
        chevron: Boolean,
        action: (() -> Unit)?
    ) = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(theme.surface, 17f, theme.outline)
        addView(label(badge, if (badge.length > 3) 10f else 14f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            this.background = rounded(badgeColor, 12f)
        }, LinearLayout.LayoutParams(dp(46), dp(42)))
        addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
            addView(label(name, 16f, theme.text, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(label(detail, 12f, theme.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) addView(label("›", 25f, theme.muted).apply { gravity = Gravity.CENTER })
        if (action != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun displayPath(): String = if (currentDirectory.isEmpty()) {
        host.getString(R.string.archive_browser_root)
    } else currentDirectory

    private fun folderColor(): Int = if (dark) Color.rgb(196, 139, 45) else Color.rgb(232, 166, 50)

    private fun fileColor(name: String): Int {
        val colors = intArrayOf(
            Color.rgb(55, 112, 201), Color.rgb(37, 145, 124), Color.rgb(156, 91, 195),
            Color.rgb(205, 111, 45), Color.rgb(194, 70, 100)
        )
        return colors[(name.lowercase().hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun rowLayout() = LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(8) }

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
}
