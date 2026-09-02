package com.ane.filemanager.input

import android.view.KeyEvent

internal enum class DesktopAction {
    COPY,
    CUT,
    PASTE,
    UNDO,
    REDO,
    SELECT_ALL,
    EDIT_ADDRESS,
    CREATE_FOLDER,
    RENAME,
    DELETE,
    OPEN,
    REFRESH,
    HISTORY_BACK,
    DIRECTORY_UP,
    NEXT_TAB,
    PREVIOUS_TAB,
    SWITCH_TAB
}

internal data class DesktopShortcut(
    val action: DesktopAction,
    val tabIndex: Int = -1
)

/** Keeps physical-keyboard policy separate from file-manager behavior. */
internal object DesktopShortcutResolver {
    fun resolve(keyCode: Int, event: KeyEvent): DesktopShortcut? {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return null

        // Escape deliberately remains unmapped so Android/DeX can handle it normally.
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return null

        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        if (ctrl && !alt) {
            val action = when (keyCode) {
                KeyEvent.KEYCODE_C -> DesktopAction.COPY
                KeyEvent.KEYCODE_X -> DesktopAction.CUT
                KeyEvent.KEYCODE_V -> DesktopAction.PASTE
                KeyEvent.KEYCODE_Z -> if (shift) DesktopAction.REDO else DesktopAction.UNDO
                KeyEvent.KEYCODE_Y -> DesktopAction.REDO
                KeyEvent.KEYCODE_A -> DesktopAction.SELECT_ALL
                KeyEvent.KEYCODE_L -> DesktopAction.EDIT_ADDRESS
                KeyEvent.KEYCODE_N -> if (shift) DesktopAction.CREATE_FOLDER else null
                KeyEvent.KEYCODE_TAB -> if (shift) DesktopAction.PREVIOUS_TAB else DesktopAction.NEXT_TAB
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> DesktopAction.SWITCH_TAB
                else -> null
            } ?: return null
            val tabIndex = if (action == DesktopAction.SWITCH_TAB) keyCode - KeyEvent.KEYCODE_1 else -1
            return DesktopShortcut(action, tabIndex)
        }

        if (alt && !ctrl) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> DesktopShortcut(DesktopAction.HISTORY_BACK)
                KeyEvent.KEYCODE_DPAD_UP -> DesktopShortcut(DesktopAction.DIRECTORY_UP)
                else -> null
            }
        }

        if (!ctrl && !alt) {
            return when (keyCode) {
                KeyEvent.KEYCODE_F2 -> DesktopShortcut(DesktopAction.RENAME)
                KeyEvent.KEYCODE_FORWARD_DEL -> DesktopShortcut(DesktopAction.DELETE)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> DesktopShortcut(DesktopAction.OPEN)
                KeyEvent.KEYCODE_F5 -> DesktopShortcut(DesktopAction.REFRESH)
                else -> null
            }
        }

        return null
    }
}
