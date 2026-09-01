package com.ane.filemanager.plugin.archive

import android.text.format.Formatter
import android.view.View
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.ui.AneBadgeKind
import com.ane.filemanager.plugin.api.ui.AneBreadcrumb
import com.ane.filemanager.plugin.api.ui.AnePluginBrowserPage
import com.ane.filemanager.plugin.api.ui.ui
import java.io.File

/** Archive hierarchy and navigation state rendered through the host browser-page service. */
internal class ArchiveBrowserDialog(
    private val host: PluginHost,
    private val source: File,
    entries: List<ArchiveEntryInfo>,
    private val extractAll: () -> Unit
) {
    private val activity = host.activity
    private val ui = host.ui
    private val hierarchy = ArchiveHierarchy(entries)
    private var currentDirectory = ""
    private lateinit var page: AnePluginBrowserPage

    fun show() {
        page = ui.browserPage(
            title = source.name,
            closeDescription = activity.getString(R.string.archive_browser_close),
            primaryActionLabel = activity.getString(R.string.archive_extract_all),
            onPrimaryAction = extractAll
        )
        rebuild()
        page.show()
    }

    private fun rebuild() {
        val items = hierarchy.children(currentDirectory)
        page.summary.text = activity.resources.getQuantityString(
            R.plurals.archive_browser_summary,
            items.size,
            displayPath(),
            items.size
        )
        page.setBreadcrumbs(breadcrumbs())
        val rows = buildList<View> {
            if (currentDirectory.isNotEmpty()) add(upRow())
            items.forEach { add(itemRow(it)) }
        }
        val empty = ui.emptyState(
            activity,
            activity.getString(R.string.archive_browser_empty)
        ).takeIf { items.isEmpty() }
        page.setRows(rows, empty)
    }

    private fun breadcrumbs(): List<AneBreadcrumb> {
        val result = mutableListOf<AneBreadcrumb>()
        val segments = currentDirectory.split('/').filter(String::isNotBlank)
        result += AneBreadcrumb(
            label = activity.getString(R.string.archive_browser_root),
            current = segments.isEmpty()
        ) {
            currentDirectory = ""
            rebuild()
        }
        var path = ""
        segments.forEachIndexed { index, segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            val target = path
            result += AneBreadcrumb(segment, index == segments.lastIndex) {
                currentDirectory = target
                rebuild()
            }
        }
        return result
    }

    private fun upRow() = browserRow(
        badge = "..",
        badgeKind = AneBadgeKind.FOLDER,
        name = activity.getString(R.string.archive_browser_parent),
        detail = activity.getString(R.string.archive_browser_parent_detail),
        chevron = true
    ) {
        currentDirectory = hierarchy.parent(currentDirectory)
        rebuild()
    }

    private fun itemRow(item: ArchiveBrowserItem): View {
        val detail = if (item.directory) {
            activity.resources.getQuantityString(
                R.plurals.archive_browser_folder_items,
                item.childCount,
                item.childCount
            )
        } else {
            item.size?.let { Formatter.formatShortFileSize(activity, it) }
                ?: activity.getString(R.string.archive_browser_unknown_size)
        }
        val badge = if (item.directory) {
            activity.getString(R.string.archive_browser_folder_badge)
        } else {
            item.name.substringAfterLast('.', "").take(4).uppercase().ifBlank {
                activity.getString(R.string.archive_browser_file_badge)
            }
        }
        return browserRow(
            badge = badge,
            badgeKind = if (item.directory) AneBadgeKind.FOLDER else AneBadgeKind.FILE,
            badgeSeed = item.name,
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
        badgeKind: AneBadgeKind,
        badgeSeed: String = "",
        name: String,
        detail: String,
        chevron: Boolean,
        action: (() -> Unit)?
    ) = ui.listRow(
        context = activity,
        leading = ui.badge(activity, badge, ui.badgeColor(badgeKind, badgeSeed)),
        title = name,
        detail = detail,
        chevron = chevron,
        onClick = action
    )

    private fun displayPath(): String = if (currentDirectory.isEmpty()) {
        activity.getString(R.string.archive_browser_root)
    } else {
        currentDirectory
    }
}
