package com.ane.filemanager.ui.menu

import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.MotionSnapshot
import com.ane.filemanager.ui.motion.UiMotionController

internal data class MenuRenderState(
    val kind: MenuKind,
    val actions: List<MenuAction>,
    val x: Float,
    val y: Float,
    val originX: Float,
    val originY: Float,
    val motion: MotionSnapshot
)

/** Owns menu placement and open/close animation state. */
internal class FileMenuController(private val invalidate: () -> Unit) {
    private val motion = UiMotionController(invalidate)

    var kind: MenuKind = MenuKind.NONE
        private set
    private var actions = listOf<MenuAction>()
    private var x = 0f
    private var y = 0f
    private var originX = 0f
    private var originY = 0f

    fun open(
        kind: MenuKind,
        actions: List<MenuAction>,
        x: Float,
        y: Float,
        originX: Float,
        originY: Float
    ) {
        this.kind = kind
        this.actions = actions
        this.x = x
        this.y = y
        this.originX = originX
        this.originY = originY
        motion.openMenu()
        invalidate()
    }

    fun close(after: () -> Unit = {}) {
        if (kind == MenuKind.NONE) {
            after()
            return
        }
        motion.closeMenu {
            kind = MenuKind.NONE
            actions = emptyList()
            invalidate()
            after()
        }
    }

    fun renderState() = MenuRenderState(kind, actions, x, y, originX, originY, motion.snapshot())
}
