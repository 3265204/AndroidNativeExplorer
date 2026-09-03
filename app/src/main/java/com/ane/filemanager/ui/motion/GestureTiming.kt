package com.ane.filemanager.ui.motion

import android.view.ViewConfiguration

/** Central timing policy for application pointer gestures. */
internal object GestureTiming {
    val doubleTapTimeoutMs: Long
        get() = ViewConfiguration.getDoubleTapTimeout().toLong()

    /** DeX may emit BUTTON_PRESS and DOWN for the same physical right click. */
    const val SECONDARY_CLICK_DEDUP_TIMEOUT_MS = 120L

    /** Movement on an ordinary file or tab before this point belongs to scrolling. */
    const val DRAG_READY_TIMEOUT_MS = 400L

    /** A stationary press opens its context menu at this absolute time. */
    const val CONTEXT_MENU_TIMEOUT_MS = 800L

    const val DRAG_DECISION_WINDOW_MS = CONTEXT_MENU_TIMEOUT_MS - DRAG_READY_TIMEOUT_MS
}
