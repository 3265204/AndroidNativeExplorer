package com.ane.filemanager.ui.menu

import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.navigation.DockSessionController
import com.ane.filemanager.operation.FileActionController
import com.ane.filemanager.pluginmanager.PluginRegistry
import com.ane.filemanager.ui.pluginmanager.PluginManagerDialog
import com.ane.filemanager.ui.appearance.AppearanceController
import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.selection.FileSelectionController
import com.ane.filemanager.ui.sort.FileSortController
import com.ane.filemanager.ui.sort.FileSortMode
import com.ane.filemanager.ui.settings.SettingsDialog
import com.ane.filemanager.ui.tabmanager.TabManagerDialog
import java.io.File

/** Builds context menus and routes their commands to focused controllers. */
internal class FileMenuCoordinator(
    private val host: MainActivity,
    private val menu: FileMenuController,
    private val fileActions: FileActionController,
    private val plugins: PluginRegistry,
    private val appearance: AppearanceController,
    private val selection: FileSelectionController,
    private val dock: DockSessionController,
    private val sorting: FileSortController,
    private val dp: (Float) -> Float,
    private val invalidate: () -> Unit,
    private val searchCurrentFolder: () -> Unit,
    private val onNavigationChanged: () -> Unit,
    private val beginDockManagement: () -> Unit,
    private val changeDockOrder: (action: () -> Unit) -> Unit,
    private val onLayoutChanged: () -> Unit,
    private val openPermissionSettings: () -> Unit
) {
    fun showSort(topBarTop: Float, topBarBottom: Float, contentRight: Float) {
        val actions = buildList {
            add(MenuAction(s(R.string.search_current_folder), run = searchCurrentFolder))
            FileSortMode.entries.forEach { mode ->
                val label = s(when (mode) {
                    FileSortMode.NAME -> R.string.sort_name
                    FileSortMode.MODIFIED -> R.string.sort_modified
                    FileSortMode.SIZE -> R.string.sort_size
                    FileSortMode.LAST_OPENED -> R.string.sort_last_opened
                })
                add(MenuAction(if (mode == sorting.mode) s(R.string.sort_selected, label) else label) {
                    sorting.select(mode)
                    onNavigationChanged()
                })
            }
        }
        menu.open(
            MenuKind.SORT,
            actions,
            contentRight - dp(235f),
            topBarBottom + dp(7f),
            contentRight - dp(29f),
            (topBarTop + topBarBottom) / 2f
        )
    }

    fun showFab(contentRight: Float, contentBottom: Float, fabOffset: Float) {
        val actions = fabActions()
        openFab(
            actions = actions,
            menuLeft = contentRight - dp(235f),
            menuTop = contentBottom - dp(actions.size * 48f + 22f),
            originX = contentRight - fabOffset,
            originY = contentBottom - fabOffset
        )
    }

    fun showFabAt(menuLeft: Float, menuTop: Float, originX: Float, originY: Float) {
        openFab(fabActions(), menuLeft, menuTop, originX, originY)
    }

    private fun fabActions(): List<MenuAction> {
        val selectedFiles = selection.files()
        return if (selection.multiSelect || selectedFiles.isNotEmpty()) {
            selectionFabActions(selectedFiles)
        } else {
            directoryFabActions()
        }
    }

    private fun selectionFabActions(selectedFiles: List<File>) = buildList {
        add(MenuAction(s(if (selection.multiSelect) {
            R.string.action_exit_multi_select
        } else {
            R.string.action_enter_multi_select
        })) {
            if (selection.multiSelect) selection.exitMultiSelect() else selection.enterMultiSelect()
        })
        if (selectedFiles.isNotEmpty()) {
            add(MenuAction(s(R.string.action_copy_selected)) { fileActions.copySelection(false) })
            add(MenuAction(s(R.string.action_cut_selected)) { fileActions.copySelection(true) })
            if (selectedFiles.all(File::isFile)) {
                add(MenuAction(s(R.string.action_share)) { host.shareFiles(selectedFiles) })
            }
            val tools = plugins.selectionActions(selectedFiles).map { action ->
                MenuAction(action.label, run = action.run)
            }
            if (tools.isNotEmpty()) {
                add(MenuAction(
                    label = s(R.string.action_tools),
                    children = tools
                ))
            }
            add(MenuAction(s(R.string.action_delete_selected)) { fileActions.delete() })
        }
    }

    private fun directoryFabActions() = buildList {
        add(MenuAction(s(R.string.action_enter_multi_select)) { selection.enterMultiSelect() })
        if (fileActions.hasClipboard) {
            add(MenuAction(s(R.string.action_paste_here)) { fileActions.paste() })
        }
        if (fileActions.canUndo) {
            add(MenuAction(s(R.string.action_undo), run = fileActions::undoLastOperation))
        }
        if (fileActions.canRedo) {
            add(MenuAction(s(R.string.action_redo), run = fileActions::redoLastOperation))
        }
        add(MenuAction(
            label = s(R.string.action_create),
            children = createActions()
        ))
        val tools = plugins.directoryActions(dock.currentDirectory).map { action ->
            MenuAction(action.label, run = action.run)
        }
        if (tools.isNotEmpty()) {
            add(MenuAction(
                label = s(R.string.action_tools),
                children = tools
            ))
        }
    }

    private fun createActions() = listOf(
        MenuAction(s(R.string.action_new_file)) { fileActions.create(false) },
        MenuAction(s(R.string.action_new_folder)) { fileActions.create(true) }
    )

    private fun openFab(
        actions: List<MenuAction>,
        menuLeft: Float,
        menuTop: Float,
        originX: Float,
        originY: Float
    ) {
        menu.open(MenuKind.FAB, actions, menuLeft, menuTop, originX, originY)
    }

    fun showFile(file: File, x: Float, y: Float) {
        selection.prepareContext(file)
        val selectedFiles = selection.files()
        val actions = buildList {
            add(MenuAction(s(if (selection.multiSelect) {
                R.string.action_exit_multi_select
            } else {
                R.string.action_enter_multi_select
            })) {
                if (selection.multiSelect) selection.exitMultiSelect() else selection.enterMultiSelect()
            })
            plugins.contextActions(file).forEach { action ->
                add(MenuAction(action.label, run = action.run))
            }
            if (selection.size == 1 && file.isFile) {
                add(MenuAction(s(R.string.action_choose_file_app)) {
                    host.openFile(file, forceChooser = true)
                })
            }
            if (selectedFiles.isNotEmpty() && selectedFiles.all(File::isFile)) {
                add(MenuAction(s(R.string.action_share)) { host.shareFiles(selectedFiles) })
            }
            add(MenuAction(s(R.string.action_copy)) { fileActions.copySelection(false) })
            add(MenuAction(s(R.string.action_cut)) { fileActions.copySelection(true) })
            if (selection.size == 1) {
                add(MenuAction(s(R.string.action_rename)) { fileActions.rename() })
            }
            add(MenuAction(s(R.string.action_delete)) { fileActions.delete() })
        }
        menu.open(MenuKind.FILE, actions, x, y, x, y)
    }

    fun showApp(topBarTop: Float, topBarBottom: Float, contentLeft: Float) {
        val actions = buildList {
            add(MenuAction(
                label = s(R.string.setting_settings),
                runAt = { x, y -> showSettings(x, y) }
            ))
            add(MenuAction(
                label = s(R.string.setting_tab_manager),
                runAt = { x, y -> showTabManager(x, y) }
            ))
            add(MenuAction(
                label = s(R.string.setting_plugins),
                runAt = { x, y ->
                    PluginManagerDialog(host, plugins, dock.currentDirectory, x, y).show()
                }
            ))
        }
        menu.open(
            MenuKind.APP,
            actions,
            contentLeft + dp(10f),
            topBarBottom + dp(7f),
            contentLeft + dp(27f),
            (topBarTop + topBarBottom) / 2f
        )
    }

    private fun showSettings(originX: Float, originY: Float) {
        SettingsDialog(
            host = host,
            appearance = appearance,
            originX = originX,
            originY = originY,
            onLayoutChanged = onLayoutChanged,
            onAppearanceChanged = invalidate,
            onFilesChanged = onNavigationChanged,
            openPermissionSettings = openPermissionSettings
        ).show()
    }

    fun beginTabEdit(index: Int, x: Float, y: Float) {
        if (index !in dock.tabs.indices) return
        if (dock.switchTo(index)) {
            selection.exitMultiSelect()
            onNavigationChanged()
        }
        showTab(dock.activeIndex, x, y)
    }

    private fun showTab(index: Int, x: Float, y: Float) {
        val tab = dock.tabs[index]
        val actions = buildList {
            add(MenuAction(
                label = s(R.string.setting_tab_manager),
                run = beginDockManagement
            ))
            if (index > 0) add(MenuAction(s(if (tab.pinned) {
                R.string.action_unpin_tab
            } else {
                R.string.action_pin_tab
            })) {
                changeDockOrder {
                    if (tab.pinned) dock.unpin(index) else dock.pin(index)
                }
                invalidate()
            })
            add(MenuAction(s(R.string.action_rename_tab)) { renameTab(index) })
            if (!tab.pinned) add(MenuAction(s(R.string.action_close_tab)) { closeTemporaryTab(index) })
        }
        menu.open(MenuKind.TAB, actions, x, y - dp(actions.size * 48f + 16f), x, y)
    }

    private fun showTabManager(originX: Float, originY: Float) {
        TabManagerDialog(
            host = host,
            dock = dock,
            originX = originX,
            originY = originY,
            onActiveTabChanged = {
                selection.exitMultiSelect()
                onNavigationChanged()
            },
            onTabsChanged = invalidate
        ).show()
    }

    private fun closeTemporaryTab(index: Int) {
        var closed = false
        changeDockOrder { closed = dock.close(index) }
        if (closed) {
            selection.exitMultiSelect()
            onNavigationChanged()
        }
    }

    private fun renameTab(index: Int) {
        host.promptName(s(R.string.dialog_rename_tab), dock.tabs[index].label) {
            dock.rename(index, it)
            invalidate()
        }
    }

    private fun s(resId: Int, vararg args: Any): String = host.getString(resId, *args)
}
