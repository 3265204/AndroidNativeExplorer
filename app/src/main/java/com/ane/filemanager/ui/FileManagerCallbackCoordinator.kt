package com.ane.filemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import com.ane.filemanager.R
import com.ane.filemanager.input.DesktopAction
import com.ane.filemanager.input.DesktopShortcut
import com.ane.filemanager.navigation.BrowserTab
import com.ane.filemanager.operation.TransferTargetPolicy
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.sort.FileSortMode
import java.io.File

/** Coordinates navigation, dock management, desktop actions, and host-facing callbacks. */
internal class FileManagerCallbackCoordinator(private val view: FileManagerView) {
    fun beginDockManagement() {
        with(view) {
            dockEditing = true
            revealActiveTab = true
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            invalidate()
        }
    }

    fun removeManagedTab(tab: BrowserTab) {
        with(view) {
            val index = tabs.indexOfFirst { it === tab }
            if (index <= 0) return
            if (tab.pinned) {
                confirmManagedTabUnpin(tab)
                return
            }
            val previousActive = dock.currentTab
            val starts = renderer.tabVisualStarts()
            if (!dock.close(index)) return
            dockMotion.reorderFrom(starts)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (dock.currentTab !== previousActive) {
                selection.exitMultiSelect()
                onNavigationChanged()
            } else {
                lastActiveTab = dock.currentTab
                lastActiveIndex = dock.activeIndex
                revealActiveTab = true
                if (tabs.size <= 1) dockEditing = false
                invalidate()
            }
        }
    }

    fun onNavigationChanged() {
        with(view) {
            val current = dock.currentTab
            if (lastActiveTab !== current) {
                val previousIndex = tabs.indexOfFirst { it === lastActiveTab }
                    .takeIf { it >= 0 }
                    ?: lastActiveIndex.coerceIn(tabs.indices)
                dockMotion.switchTabs(previousIndex, dock.activeIndex)
                pendingContentRevealPath = dock.currentDirectory.absolutePath
            } else if (pendingContentRevealPath != null) {
                pendingContentRevealPath = dock.currentDirectory.absolutePath
            }
            lastActiveTab = current
            lastActiveIndex = dock.activeIndex
            scrollY = 0f
            revealActiveTab = true
            refresh()
        }
    }

    fun changeDockOrder(action: () -> Unit) {
        with(view) {
            val starts = renderer.tabVisualStarts()
            action()
            dockMotion.reorderFrom(starts)
            if (lastActiveTab === dock.currentTab) lastActiveIndex = dock.activeIndex
            revealActiveTab = true
            invalidate()
        }
    }

    fun navigateTo(directory: File) {
        with(view) {
            if (!directory.isDirectory || !directory.canRead()) {
                host.toast(host.getString(R.string.cannot_read_directory))
                return
            }
            dock.navigateTo(directory)
            sorting.markOpened(directory)
            resetSelectionForNavigation()
            onNavigationChanged()
        }
    }

    fun navigateUp() {
        view.currentDirectory.parentFile?.let(::navigateTo)
    }

    fun switchTab(index: Int) {
        with(view) {
            renderer.restartTabMarquee(index)
            if (dock.switchTo(index)) {
                sorting.markOpened(dock.currentDirectory)
                resetSelectionForNavigation()
                onNavigationChanged()
                onboarding.tabSwitched(dock.currentDirectory)
            }
        }
    }

    fun handleBack(): Boolean = with(view) {
        if (onboarding.active) {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            return@with true
        }
        if (menu.kind != MenuKind.NONE) {
            menu.close()
            return@with true
        }
        if (dockEditing) {
            dockEditing = false
            invalidate()
            return@with true
        }
        if (gestures.dragging || gestures.tabDragging) {
            gestures.reset()
            return@with true
        }
        if (selection.multiSelect && !pickerAllowsMultiple) {
            selection.exitMultiSelect()
            return@with true
        }
        if (!selection.isEmpty) {
            selection.clear()
            return@with true
        }
        val parent = parentForSystemBack() ?: return@with false
        if (!dock.navigateBackTo(parent)) return@with false
        sorting.markOpened(dock.currentDirectory)
        onNavigationChanged()
        true
    }

    fun sameDirectory(left: File, right: File): Boolean = try {
        left.canonicalFile == right.canonicalFile
    } catch (_: Exception) {
        left.absolutePath == right.absolutePath
    }

    fun openPointerContextMenu(x: Float, y: Float) {
        with(view) {
            val open = {
                val file = renderer.fileHits.lastOrNull { it.rect.contains(x, y) }?.file
                val tab = renderer.tabHits.lastOrNull { it.rect.contains(x, y) }?.index
                when {
                    file != null -> menus.showFile(file, x, y)
                    tab != null -> menus.beginTabEdit(tab, x, y)
                    y in topBarBottom..contentBottom -> {
                        if (!selection.multiSelect) selection.clear()
                        menus.showFabAt(x, y, x, y)
                    }
                }
            }
            if (menu.kind != MenuKind.NONE) menu.close(open) else open()
        }
    }

    fun runDesktopShortcut(shortcut: DesktopShortcut) {
        with(view) {
            when (shortcut.action) {
                DesktopAction.COPY -> if (!selection.isEmpty) fileActions.copySelection(false)
                DesktopAction.CUT -> if (!selection.isEmpty) fileActions.copySelection(true)
                DesktopAction.PASTE -> if (fileActions.hasClipboard) fileActions.paste()
                DesktopAction.UNDO -> if (fileActions.canUndo) fileActions.undoLastOperation()
                DesktopAction.REDO -> if (fileActions.canRedo) fileActions.redoLastOperation()
                DesktopAction.SELECT_ALL -> selection.selectAll(items)
                DesktopAction.EDIT_ADDRESS -> editAddress()
                DesktopAction.CREATE_FOLDER -> fileActions.create(true)
                DesktopAction.RENAME -> if (selection.files().size == 1) fileActions.rename()
                DesktopAction.DELETE -> if (!selection.isEmpty) fileActions.delete()
                DesktopAction.OPEN -> openSelectedFile()
                DesktopAction.REFRESH -> refresh()
                DesktopAction.HISTORY_BACK -> navigateHistoryBack()
                DesktopAction.DIRECTORY_UP -> navigateUp()
                DesktopAction.NEXT_TAB -> switchRelativeTab(1)
                DesktopAction.PREVIOUS_TAB -> switchRelativeTab(-1)
                DesktopAction.SWITCH_TAB -> switchTab(shortcut.tabIndex)
            }
        }
    }

    fun finishDrag(x: Float, y: Float, sourceFile: File?) {
        with(view) {
            if (renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom)) {
                host.toast(s(R.string.drag_cancelled))
                return
            }
            val targetTab = renderer.tabHits.lastOrNull { it.rect.contains(x, y) }?.index
            val targetFolder = renderer.fileHits.lastOrNull {
                it.file.isDirectory && it.rect.contains(x, y)
            }?.file
            val target = when {
                targetTab != null -> tabs[targetTab].directory
                targetFolder != null -> targetFolder
                else -> null
            }
            val sources = selection.dragFiles(sourceFile)
            if (target == null || !TransferTargetPolicy.accepts(sources, target)) return
            fileActions.move(sources, target)
        }
    }

    fun editAddress() {
        with(view) {
            host.promptPath(currentDirectory.absolutePath) { value ->
                val raw = File(value)
                val target = (if (raw.isAbsolute) raw else File(currentDirectory, value)).let {
                    try {
                        it.canonicalFile
                    } catch (_: Exception) {
                        it.absoluteFile
                    }
                }
                when {
                    target.isDirectory -> navigateTo(target)
                    target.isFile -> openFile(target)
                    else -> host.toast(s(R.string.error_path_not_found))
                }
            }
        }
    }

    fun searchCurrentFolder() {
        view.host.showFileSearch(view.items, ::selectSearchResult)
    }

    fun openPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            view.host.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${view.host.packageName}")
                )
            )
        }
    }

    fun resetSelectionForNavigation() {
        view.selection.exitMultiSelect()
        if (view.pickerAllowsMultiple) view.selection.enterMultiSelect()
    }

    private fun confirmManagedTabUnpin(tab: BrowserTab) {
        with(view) {
            AneDialog.message(
                activity = host,
                title = s(R.string.dock_unpin_confirm_title),
                message = s(R.string.dock_unpin_confirm_message, tab.label),
                actions = listOf(
                    AneDialogAction(s(R.string.dialog_cancel)),
                    AneDialogAction(s(R.string.dock_unpin_confirm_action), primary = true) {
                        val index = tabs.indexOfFirst { it === tab }
                        if (index > 0 && tab.pinned) {
                            val starts = renderer.tabVisualStarts()
                            dock.unpin(index)
                            dockMotion.reorderFrom(starts)
                            lastActiveTab = dock.currentTab
                            lastActiveIndex = dock.activeIndex
                            revealActiveTab = true
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                        }
                    }
                )
            )
        }
    }

    private fun parentForSystemBack(): File? {
        if (sameDirectory(view.currentDirectory, view.storageRoot)) return null
        return view.currentDirectory.parentFile?.takeIf { it.isDirectory && it.canRead() }
    }

    private fun openSelectedFile() {
        val file = view.selection.files().singleOrNull() ?: return
        if (file.isDirectory) navigateTo(file) else openFile(file)
    }

    private fun navigateHistoryBack() {
        with(view) {
            if (dock.goBack()) {
                sorting.markOpened(dock.currentDirectory)
                resetSelectionForNavigation()
                onNavigationChanged()
            }
        }
    }

    private fun switchRelativeTab(direction: Int) {
        if (view.tabs.size < 2) return
        val index = (view.activeTab + direction + view.tabs.size) % view.tabs.size
        switchTab(index)
    }

    private fun selectSearchResult(file: File) {
        with(view) {
            if (file !in items) return
            if (selection.multiSelect && !pickerAllowsMultiple) selection.exitMultiSelect()
            selection.replace(file)
            renderer.restartFileMarquee(file)
            scrollY = renderer.scrollToRevealFile(file, scrollY)
            invalidate()
        }
    }

    fun openFile(file: File) {
        with(view) {
            onPickerFileOpened?.let {
                it(file)
                return
            }
            val opened = plugins.open(file) || host.openFile(file)
            if (opened) {
                sorting.markOpened(file)
                if (sorting.mode == FileSortMode.LAST_OPENED) refresh()
            }
        }
    }

    private fun s(resId: Int, vararg args: Any): String = view.host.getString(resId, *args)
}
