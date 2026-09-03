package com.ane.filemanager.ui

import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import com.ane.filemanager.navigation.BrowserTab
import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.LayoutMode
import com.ane.filemanager.ui.motion.GestureTiming
import com.ane.filemanager.ui.onboarding.TutorialProgress.Step
import com.ane.filemanager.ui.selection.ClickResult
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Owns pointer gesture state and translates pointer sequences into file-manager callbacks. */
internal class FileManagerGestureController(
    private val view: FileManagerView,
    private val callbacks: FileManagerCallbackCoordinator
) {
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    var downFile: File? = null
        private set
    private var downTab = -1
    private var downCloseTab: BrowserTab? = null
    private var downMenuAction: MenuAction? = null
    private var moved = false
    var scrolling = false
        private set
    private var dockScrolling = false
    var longTriggered = false
        private set
    var longPressMenuShown = false
        private set
    var dragging = false
        private set
    var tabDragging = false
        private set
    var draggedTab: BrowserTab? = null
        private set
    private var dragCancelHover = false
    var dragX = 0f
        private set
    var dragY = 0f
        private set
    private var slideSelecting = false
    private var slideCandidateFile: File? = null
    private var directMouseDragCandidate = false
    private var onboardingBlockedTouch = false
    private var onboardingActionPending = false
    private var downOnboardingLayout: LayoutMode? = null
    private var downOnboardingNext = false
    private var lastSecondaryClickTime = 0L
    private var lastSecondaryClickX = 0f
    private var lastSecondaryClickY = 0f

    private val longPressMenuRunnable = Runnable(::showLongPressMenu)
    private val longPressRunnable = Runnable {
        with(view) {
            if (!moved && (downFile != null || downTab >= 0)) {
                longTriggered = true
                val tabIndex = downTab.takeIf { it in tabs.indices }
                if (tabIndex != null) {
                    draggedTab = tabs[tabIndex]
                    renderer.restartTabMarquee(tabIndex)
                    if (dock.switchTo(tabIndex)) {
                        callbacks.resetSelectionForNavigation()
                        callbacks.onNavigationChanged()
                    }
                } else {
                    downFile?.let { file ->
                        renderer.restartFileMarquee(file)
                        selection.selectOnLongPress(file)
                    }
                }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!onboarding.active || onboarding.step != Step.MOVE_TO_DOCK) {
                    handler.postDelayed(longPressMenuRunnable, GestureTiming.DRAG_DECISION_WINDOW_MS)
                }
                invalidate()
            }
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean = with(view) {
        if (onboarding.active && onboarding.step == Step.LAYOUT) {
            if (event.pointerCount > 1 || event.isSecondaryMouseInput()) {
                downOnboardingLayout = null
                downOnboardingNext = false
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
                return@with true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downOnboardingLayout = onboarding.layoutChoiceAt(event.x, event.y)
                    downOnboardingNext = onboarding.isLayoutNextAt(event.x, event.y)
                    if (downOnboardingLayout == null && !downOnboardingNext) {
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val released = onboarding.layoutChoiceAt(event.x, event.y)
                    if (released != null && released == downOnboardingLayout) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        chooseOnboardingLayout(released)
                    } else if (downOnboardingNext && onboarding.isLayoutNextAt(event.x, event.y)) {
                        if (onboarding.selectedLayout != null) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            confirmOnboardingLayout()
                        } else {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                        }
                    }
                    downOnboardingLayout = null
                    downOnboardingNext = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    downOnboardingLayout = null
                    downOnboardingNext = false
                }
            }
            return@with true
        }
        if (onboarding.active) {
            if (event.pointerCount > 1 || event.isSecondaryMouseInput()) {
                onboardingBlockedTouch = true
                reset()
                return@with true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val target = onboardingTarget()
                onboardingBlockedTouch = onboardingActionPending || busyText != null ||
                    directoryTransitioning || target == null || !target.contains(event.x, event.y)
                if (onboardingBlockedTouch) {
                    if (target != null && busyText == null && !onboardingActionPending) {
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                    invalidate()
                    return@with true
                }
            } else if (onboardingBlockedTouch) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    onboardingBlockedTouch = false
                }
                return@with true
            }
        }
        if (handleSecondaryMousePress(event)) return@with true
        if (busyText != null) return@with true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                inertialScroll.onDown(event)
                dockInertialScroll.onDown(event)
                onDown(event.x, event.y, event.isMousePointer())
            }
            MotionEvent.ACTION_MOVE -> {
                inertialScroll.onMove(event)
                dockInertialScroll.onMove(event)
                onMove(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                inertialScroll.onUp(event, scrolling, scrollY, maxScroll)
                dockInertialScroll.onUp(event, dockScrolling, dockScrollX, maxDockScroll)
                onUp(event.x, event.y)
            }
            MotionEvent.ACTION_CANCEL -> {
                inertialScroll.onCancel()
                dockInertialScroll.onCancel()
                if (longTriggered && menu.kind == MenuKind.NONE && !selection.multiSelect) selection.clear()
                reset()
            }
        }
        true
    }

    fun handlesGenericMotion(event: MotionEvent): Boolean =
        view.onboarding.active || handleSecondaryMousePress(event)

    fun reset() {
        with(view) {
            handler.removeCallbacks(longPressRunnable)
            handler.removeCallbacks(longPressMenuRunnable)
            downFile = null
            downTab = -1
            downCloseTab = null
            downMenuAction = null
            moved = false
            scrolling = false
            dockScrolling = false
            longTriggered = false
            longPressMenuShown = false
            dragging = false
            tabDragging = false
            draggedTab = null
            dragCancelHover = false
            slideSelecting = false
            slideCandidateFile = null
            directMouseDragCandidate = false
            selection.endSlide()
            invalidate()
        }
    }

    private fun showLongPressMenu() {
        with(view) {
            if (!longTriggered || moved || menu.kind != MenuKind.NONE) return
            val tabIndex = draggedTab?.let(tabs::indexOf)?.takeIf { it in tabs.indices }
            when {
                tabIndex != null -> menus.beginTabEdit(tabIndex, downX, downY)
                downFile != null -> {
                    menus.showFile(downFile!!, downX, downY)
                    onboarding.longPressMenuOpened(downFile!!)
                }
                else -> return
            }
            longPressMenuShown = true
        }
    }

    private fun handleSecondaryMousePress(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val secondaryPress = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> event.actionButton == MotionEvent.BUTTON_SECONDARY
            MotionEvent.ACTION_DOWN -> event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
            else -> false
        }
        if (!secondaryPress) return false
        if (view.busyText != null) return true

        // Some DeX/Android builds deliver both BUTTON_PRESS and DOWN for one right click.
        val duplicate = event.eventTime - lastSecondaryClickTime <
            GestureTiming.SECONDARY_CLICK_DEDUP_TIMEOUT_MS &&
            max(abs(event.x - lastSecondaryClickX), abs(event.y - lastSecondaryClickY)) < view.touchSlop
        if (!duplicate) {
            lastSecondaryClickTime = event.eventTime
            lastSecondaryClickX = event.x
            lastSecondaryClickY = event.y
            view.requestFocus()
            callbacks.openPointerContextMenu(event.x, event.y)
        }
        return true
    }

    private fun onDown(x: Float, y: Float, fromMouse: Boolean) {
        with(view) {
            downX = x
            downY = y
            lastX = x
            lastY = y
            dragX = x
            dragY = y
            moved = false
            scrolling = false
            dockScrolling = false
            longTriggered = false
            longPressMenuShown = false
            dragging = false
            tabDragging = false
            draggedTab = null
            dragCancelHover = false
            slideSelecting = false
            slideCandidateFile = null
            directMouseDragCandidate = false
            downCloseTab = null
            downMenuAction = if (menu.kind != MenuKind.NONE) {
                renderer.menuHits.lastOrNull { it.rect.contains(x, y) }?.action
            } else null
            selection.endSlide()
            // The floating action button is visually above list rows and must win hit testing.
            if (menu.kind == MenuKind.NONE && renderer.isFab(
                    width, height, x, y, systemInsets.right, systemInsets.bottom
                )) {
                downFile = null
                downTab = -1
                return
            }
            val inDock = y in contentBottom..(height - systemInsets.bottom).toFloat()
            if (inDock) {
                downFile = null
                val closeIndex = if (dockEditing) {
                    renderer.tabCloseHits.lastOrNull { it.rect.contains(x, y) }?.index
                } else null
                downCloseTab = closeIndex?.let(tabs::getOrNull)
                downTab = if (downCloseTab == null) {
                    renderer.tabHits.lastOrNull { it.rect.contains(x, y) }?.index ?: -1
                } else -1
            } else {
                downFile = renderer.fileHits.lastOrNull { it.rect.contains(x, y) }?.file
                downTab = -1
            }
            // A selection handle remains a slide-selection candidate until the pointer moves.
            slideCandidateFile = if (inDock) null else renderer.selectionHandleFile(x, y)
            directMouseDragCandidate = !onboarding.active && menu.kind == MenuKind.NONE &&
                !dockEditing && fromMouse && slideCandidateFile == null &&
                downFile?.let(selection::contains) == true
            val onboardingAllowsLongPress = !onboarding.active ||
                downFile != null && (
                    onboarding.step == Step.MOVE_TO_DOCK || onboarding.step == Step.LONG_PRESS_MENU
                    )
            if (menu.kind == MenuKind.NONE && !dockEditing &&
                (downFile != null || downTab >= 0) && onboardingAllowsLongPress) {
                handler.postDelayed(longPressRunnable, GestureTiming.DRAG_READY_TIMEOUT_MS)
            }
        }
    }

    private fun onMove(x: Float, y: Float) {
        with(view) {
            dragX = x
            dragY = y
            val distance = max(abs(x - downX), abs(y - downY))
            if (!longTriggered && directMouseDragCandidate && distance > touchSlop) {
                moved = true
                dragging = true
                handler.removeCallbacks(longPressRunnable)
                updateDragCancelFeedback(x, y)
            } else if (longTriggered && !longPressMenuShown && distance > touchSlop) {
                moved = true
                handler.removeCallbacks(longPressMenuRunnable)
                when {
                    draggedTab != null -> {
                        val source = tabs.indexOf(draggedTab)
                        if (source > 0) {
                            tabDragging = true
                            reorderDraggedTab(x)
                        }
                    }
                    downFile != null -> {
                        dragging = true
                        updateDragCancelFeedback(x, y)
                    }
                }
            } else if (slideSelecting) {
                applySlideSelection(x, y)
                autoScrollSelection(y)
                moved = true
            } else if (!longTriggered && distance > touchSlop) {
                moved = true
                handler.removeCallbacks(longPressRunnable)
                if (slideCandidateFile != null) {
                    slideSelecting = true
                    selection.beginSlide(slideCandidateFile!!)
                    applySlideSelection(x, y)
                    autoScrollSelection(y)
                } else if (downY in contentBottom..(height - systemInsets.bottom).toFloat() &&
                    abs(x - downX) > abs(y - downY)) {
                    dockScrolling = true
                    dockScrollX = (dockScrollX + (lastX - x)).coerceIn(0f, maxDockScroll)
                } else if (downY in topBarBottom..contentBottom &&
                    abs(y - downY) > abs(x - downX)) {
                    scrolling = true
                    scrollY = (scrollY + (lastY - y)).coerceIn(0f, maxScroll)
                }
            }
            lastX = x
            lastY = y
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        with(view) {
            handler.removeCallbacks(longPressRunnable)
            handler.removeCallbacks(longPressMenuRunnable)
            if (longTriggered) {
                if (dragging) finishPracticeOrRealDrag(x, y)
                reset()
                return
            }
            if (menu.kind != MenuKind.NONE) {
                val dismissedKind = menu.kind
                val releasedAction = renderer.menuHits.lastOrNull { it.rect.contains(x, y) }?.action
                val action = if (!moved) downMenuAction ?: releasedAction else null
                if (action != null && !action.enabled) {
                    reset()
                    return
                }
                if (action != null && menu.expand(action)) {
                    reset()
                    return
                }
                if (action != null) {
                    onboardingActionPending = onboarding.active
                    menu.close {
                        action.runAt?.invoke(x, y) ?: action.run()
                        onboardingActionPending = false
                        invalidate()
                    }
                    reset()
                    return
                }
                if (!moved && switchOpenMenuFromLauncher(x, y)) {
                    reset()
                    return
                }
                if (menu.isOpening()) {
                    reset()
                    return
                }
                menu.close {
                    if (dismissedKind == MenuKind.FILE && !selection.multiSelect) selection.clear()
                }
                reset()
                return
            }
            val releasedCloseTab = if (dockEditing) {
                renderer.tabCloseHits.lastOrNull { it.rect.contains(x, y) }
                    ?.index?.let(tabs::getOrNull)
            } else null
            when {
                dockEditing && !moved && downCloseTab != null && releasedCloseTab === downCloseTab ->
                    callbacks.removeManagedTab(downCloseTab!!)
                dockEditing && !moved && downCloseTab != null -> Unit
                dockEditing && !moved && downTab < 0 -> dockEditing = false
                dragging -> finishPracticeOrRealDrag(x, y)
                tabDragging -> Unit
                slideSelecting -> Unit
                !moved && y in topBarTop..topBarBottom &&
                    x < contentLeft + renderer.appMenuHitWidth ->
                    menus.showApp(topBarTop, topBarBottom, contentLeft)
                !moved && y in topBarTop..topBarBottom &&
                    x < contentLeft + renderer.navigateUpHitWidth -> callbacks.navigateUp()
                !moved && y in topBarTop..topBarBottom &&
                    renderer.isSortButton(width, x, systemInsets.right) ->
                    menus.showSort(topBarTop, topBarBottom, contentRight)
                !moved && y in topBarTop..topBarBottom -> callbacks.editAddress()
                !moved && renderer.isFab(
                    width, height, x, y, systemInsets.right, systemInsets.bottom
                ) -> {
                    menus.showFab(contentRight, contentBottom, renderer.fabOffset)
                    onboarding.menuOpened()
                }
                !moved && downTab >= 0 -> callbacks.switchTab(downTab)
                !moved && downFile != null -> {
                    renderer.restartFileMarquee(downFile!!)
                    if (onPickerFileOpened != null && downFile!!.isDirectory) {
                        callbacks.navigateTo(downFile!!)
                    } else {
                        val onboardingStep = onboarding.step
                        when (onboardingStep) {
                            Step.MOVE_TO_DOCK, Step.LONG_PRESS_MENU ->
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                            else -> {
                                val clickedFile = downFile!!
                                when (selection.click(clickedFile)) {
                                    ClickResult.OPEN_DIRECTORY -> {
                                        onboarding.opened(clickedFile)
                                        callbacks.navigateTo(clickedFile)
                                    }
                                    ClickResult.OPEN_FILE -> {
                                        onboarding.opened(clickedFile)
                                        callbacks.openFile(clickedFile)
                                    }
                                    ClickResult.SELECTED, ClickResult.SELECTION_TOGGLED -> Unit
                                }
                                if (onboarding.active && onboardingStep == Step.SELECT &&
                                    selection.contains(clickedFile)
                                ) {
                                    onboarding.selected(clickedFile)
                                    selection.resetClickSequence()
                                }
                            }
                        }
                    }
                }
                !moved && y in topBarBottom..contentBottom -> selection.clear()
            }
            reset()
        }
    }

    private fun switchOpenMenuFromLauncher(x: Float, y: Float): Boolean = with(view) {
        val requested = when {
            y in topBarTop..topBarBottom && x < contentLeft + renderer.appMenuHitWidth -> MenuKind.APP
            y in topBarTop..topBarBottom && renderer.isSortButton(width, x, systemInsets.right) -> MenuKind.SORT
            renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom) -> MenuKind.FAB
            else -> return@with false
        }
        if (menu.kind == requested) {
            if (!menu.isOpening()) menu.close()
        } else {
            when (requested) {
                MenuKind.APP -> menus.showApp(topBarTop, topBarBottom, contentLeft)
                MenuKind.SORT -> menus.showSort(topBarTop, topBarBottom, contentRight)
                MenuKind.FAB -> menus.showFab(contentRight, contentBottom, renderer.fabOffset)
                else -> Unit
            }
        }
        true
    }

    private fun reorderDraggedTab(x: Float) {
        with(view) {
            autoScrollDockDuringTabDrag(x)
            val tab = draggedTab ?: return
            val from = tabs.indexOf(tab)
            val to = renderer.tabSlotHits.lastOrNull { it.rect.contains(x, dragY) }?.index ?: return
            if (from <= 0 || to == from) return
            val starts = renderer.tabVisualStarts()
            val movedTo = dock.moveTab(from, to)
            if (movedTo != from) {
                dockMotion.reorderFrom(starts)
                lastActiveIndex = dock.activeIndex
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    private fun autoScrollDockDuringTabDrag(x: Float) {
        with(view) {
            val edge = dp(52f)
            val delta = when {
                x < contentLeft + edge -> -dp(12f)
                x > contentRight - edge -> dp(12f)
                else -> 0f
            }
            if (delta != 0f) dockScrollX = (dockScrollX + delta).coerceIn(0f, maxDockScroll)
        }
    }

    private fun updateDragCancelFeedback(x: Float, y: Float) {
        with(view) {
            val hovering = renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom)
            if (hovering && !dragCancelHover) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            dragCancelHover = hovering
        }
    }

    private fun applySlideSelection(x: Float, y: Float) {
        view.renderer.fileAt(x, y)?.let(::applySlideFile)
    }

    private fun autoScrollSelection(y: Float) {
        with(view) {
            val edge = dp(56f)
            val delta = when {
                y < topBarBottom + edge -> -dp(18f)
                y > contentBottom - edge -> dp(18f)
                else -> 0f
            }
            if (delta != 0f) scrollY = (scrollY + delta).coerceIn(0f, maxScroll)
        }
    }

    private fun applySlideFile(file: File) {
        view.renderer.restartFileMarquee(file)
        view.selection.applySlide(file)
    }

    private fun finishPracticeOrRealDrag(x: Float, y: Float) {
        with(view) {
            if (!onboarding.active) {
                callbacks.finishDrag(x, y, downFile)
                return
            }
            val targetTab = renderer.tabHits.lastOrNull { it.rect.contains(x, y) }?.index
            val targetFolder = renderer.fileHits.lastOrNull {
                it.file.isDirectory && it.rect.contains(x, y)
            }?.file
            val target = targetTab?.let { tabs.getOrNull(it)?.directory } ?: targetFolder
            if (onboarding.acceptsMoveTo(target)) {
                callbacks.finishDrag(x, y, downFile)
            } else {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }
    }

    private fun MotionEvent.isSecondaryMouseInput(): Boolean =
        isFromSource(InputDevice.SOURCE_MOUSE) && (
            actionButton == MotionEvent.BUTTON_SECONDARY ||
                buttonState and MotionEvent.BUTTON_SECONDARY != 0
            )

    private fun MotionEvent.isMousePointer(): Boolean =
        isFromSource(InputDevice.SOURCE_MOUSE) ||
            pointerCount > 0 && getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
            buttonState and (
                MotionEvent.BUTTON_PRIMARY or
                    MotionEvent.BUTTON_SECONDARY or
                    MotionEvent.BUTTON_TERTIARY
                ) != 0
}
