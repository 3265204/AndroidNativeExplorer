package com.ane.filemanager.ui.motion

import android.view.ViewConfiguration

/** Central timing policy for file-manager pointer gestures. */
internal object GestureTiming {
    val longPressTimeoutMs: Long
        get() = ViewConfiguration.getLongPressTimeout().toLong()

    val doubleTapTimeoutMs: Long
        get() = ViewConfiguration.getDoubleTapTimeout().toLong()

    /** DeX may emit BUTTON_PRESS and DOWN for the same physical right click. */
    const val SECONDARY_CLICK_DEDUP_TIMEOUT_MS = 120L
}
