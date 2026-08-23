package com.ane.filemanager.ui.menu

import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.navigation.DockSessionController
import com.ane.filemanager.operation.FileActionController
import com.ane.filemanager.ui.appearance.AppearanceController
import com.ane.filemanager.ui.model.LayoutMode
import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.selection.FileSelectionController
import java.io.File

/** Builds context menus and routes their commands to focused controllers. */
internal class FileMenuCoordinator(
    private val host: MainActivity,
    private val menu: FileMenuController,
    private val fileActions: FileActionController,
    private val appearance: AppearanceController,
    private val selection: FileSelectionController,
    private val dock: DockSessionController,
    private val dp: (Float) -> Float,
    private val invalidate: () -> Unit,
    private val onNavigationChanged: () -> Unit,
    private val onLayoutChanged: () -> Unit,
    private val onThemeChanged: () -> Unit,
    private val openPermissionSettings: () -> Unit
) {
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

    private fun fabActions() = buildList {
        val hasSelection = !selection.isEmpty
        if (fileActions.canUndo) {
            add(MenuAction(s(R.string.action_undo)) { fileActions.undoLastOperation() })
        }
        add(MenuAction(s(if (selection.multiSelect) {
            R.string.action_exit_multi_select
        } else {
            R.string.action_enter_multi_select
        })) {
            if (selection.multiSelect) selection.exitMultiSelect() else selection.enterMultiSelect()
        })
        if (hasSelection) {
            add(MenuAction(s(R.string.action_copy_selected)) { fileActions.copySelection(false) })
            add(MenuAction(s(R.string.action_cut_selected)) { fileActions.copySelection(true) })
        }
        if (fileActions.hasClipboard) {
            add(MenuAction(s(R.string.action_paste_here)) { fileActions.paste() })
        }
        if (hasSelection) {
            add(MenuAction(s(R.string.action_delete_selected)) { fileActions.delete() })
        }
        add(MenuAction(s(R.string.action_new_file)) { fileActions.create(false) })
        add(MenuAction(s(R.string.action_new_folder)) { fileActions.create(true) })
    }

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
        val actions = buildList {
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
            add(
            MenuAction(s(R.string.setting_layout,
                s(if (appearance.layoutMode == LayoutMode.LIST) R.string.layout_list else R.string.layout_grid))) {
                appearance.toggleLayout(); onLayoutChanged()
            })
            add(
            MenuAction(s(R.string.setting_theme,
                s(if (appearance.dark) R.string.theme_dark else R.string.theme_light))) {
                appearance.toggleTheme(); onThemeChanged()
            })
            add(
            MenuAction(s(R.string.setting_text_size, appearance.textSp)) {
                appearance.cycleTextSize(); invalidate()
            })
            add(
            MenuAction(s(R.string.setting_icon_size, appearance.iconDp)) {
                appearance.cycleIconSize(); invalidate()
            })
            add(
            MenuAction(s(R.string.setting_spacing, appearance.spacingDp)) {
                appearance.cycleSpacing(); invalidate()
            })
            add(
            MenuAction(s(if (appearance.showHidden) R.string.setting_hide_hidden else R.string.setting_show_hidden)) {
                appearance.toggleHidden(); onNavigationChanged()
            })
            if (!host.hasStorageAccess()) {
                add(MenuAction(s(R.string.setting_storage_permission)) { openPermissionSettings() })
            }
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
        val actions = if (tab.pinned) listOf(
            MenuAction(s(R.string.action_unpin_tab)) { dock.unpin(index); invalidate() },
            MenuAction(s(R.string.action_rename_tab)) { renameTab(index) }
        ) else listOf(
            MenuAction(s(R.string.action_pin_tab)) { dock.pin(index); invalidate() },
            MenuAction(s(R.string.action_rename_tab)) { renameTab(index) },
            MenuAction(s(R.string.action_close_tab)) { closeTemporaryTab(index) }
        )
        menu.open(MenuKind.TAB, actions, x, y - dp(if (tab.pinned) 112f else 160f), x, y)
    }

    private fun closeTemporaryTab(index: Int) {
        if (dock.close(index)) {
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
