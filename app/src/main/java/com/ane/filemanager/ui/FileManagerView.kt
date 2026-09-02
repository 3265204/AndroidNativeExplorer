package com.ane.filemanager.ui

import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.input.DesktopAction
import com.ane.filemanager.input.DesktopShortcut
import com.ane.filemanager.input.DesktopShortcutResolver
import com.ane.filemanager.navigation.BrowserTab
import com.ane.filemanager.navigation.DockSessionController
import com.ane.filemanager.navigation.DockSessionStore
import com.ane.filemanager.operation.FileActionController
import com.ane.filemanager.operation.FileTransactionService
import com.ane.filemanager.pluginmanager.PluginRegistry
import com.ane.filemanager.ui.appearance.AppearanceController
import com.ane.filemanager.ui.directory.DirectoryLoader
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.ui.menu.FileMenuController
import com.ane.filemanager.ui.menu.FileMenuCoordinator
import com.ane.filemanager.ui.model.MenuAction
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.RenderState
import com.ane.filemanager.ui.model.UiInsets
import com.ane.filemanager.ui.motion.GestureTiming
import com.ane.filemanager.ui.motion.DockMotionController
import com.ane.filemanager.ui.motion.InertialScrollController
import com.ane.filemanager.ui.motion.ScrollAxis
import com.ane.filemanager.ui.render.FileManagerRenderer
import com.ane.filemanager.ui.selection.FileSelectionController
import com.ane.filemanager.ui.sort.FileSortController
import com.ane.filemanager.ui.sort.FileSortMode
import java.io.File
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Android View boundary: assembles render state and routes pointer/keyboard events to controllers. */
internal class FileManagerView(private val host: MainActivity) : View(host) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val appearance = AppearanceController(context)
    private val sorting = FileSortController(context)
    private val menu = FileMenuController { postInvalidateOnAnimation() }
    private val renderer = FileManagerRenderer(
        context = context,
        pluginFileIcon = { file ->
            if (::plugins.isInitialized) plugins.fileIcon(file) else null
        },
        onInvalidate = { postInvalidateOnAnimation() }
    )
    private val inertialScroll = InertialScrollController(context) { postInvalidateOnAnimation() }
    private val dockInertialScroll = InertialScrollController(context, ScrollAxis.HORIZONTAL) {
        postInvalidateOnAnimation()
    }
    private val dockMotion = DockMotionController { postInvalidateOnAnimation() }
    private lateinit var fileActions: FileActionController
    private lateinit var plugins: PluginRegistry
    private lateinit var menus: FileMenuCoordinator
    private val selection = FileSelectionController(
        openFile = ::openFile,
        openDirectory = { navigateTo(it) },
        invalidate = { invalidate() },
        doubleClickTimeoutMs = GestureTiming.doubleTapTimeoutMs
    )
    private val directoryLoader = DirectoryLoader { directory, loaded ->
        post {
            if (!::dock.isInitialized || !sameDirectory(currentDirectory, directory)) return@post
            renderer.onDirectoryContentsChanged()
            items = loaded
            displayedDirectoryPath = directory.absolutePath
            directoryTransitioning = false
            if (pendingContentRevealPath == directory.absolutePath) {
                pendingContentRevealPath = null
                dockMotion.revealContent()
            }
            selection.retain(items)
            scrollY = scrollY.coerceAtLeast(0f)
            invalidate()
        }
    }

    private val storageRoot = host.initialDirectory()
    private val transactions = FileTransactionService(storageRoot)
    private val dockStore = DockSessionStore(context)
    private lateinit var dock: DockSessionController
    private val tabs get() = dock.tabs
    private val activeTab get() = dock.activeIndex
    private val currentDirectory get() = dock.currentDirectory
    private var items = listOf<File>()
    private var displayedDirectoryPath: String? = null
    private var directoryTransitioning = false
    private var scrollY = 0f
    private var maxScroll = 0f
    private var dockScrollX = 0f
    private var maxDockScroll = 0f
    private var revealActiveTab = true
    private var dockEditing = false
    private lateinit var lastActiveTab: BrowserTab
    private var lastActiveIndex = 0
    private var pendingContentRevealPath: String? = null
    private var busyText: String? = null
    private var systemInsets = UiInsets()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downFile: File? = null
    private var downTab = -1
    private var downCloseTab: BrowserTab? = null
    private var downMenuAction: MenuAction? = null
    private var moved = false
    private var scrolling = false
    private var dockScrolling = false
    private var longTriggered = false
    private var dragging = false
    private var tabDragging = false
    private var draggedTab: BrowserTab? = null
    private var dragCancelHover = false
    private var dragX = 0f
    private var dragY = 0f
    private var slideSelecting = false
    private var slideCandidateFile: File? = null
    private var lastSecondaryClickTime = 0L
    private var lastSecondaryClickX = 0f
    private var lastSecondaryClickY = 0f
    private val longPressRunnable = Runnable {
        if (!moved && (downFile != null || downTab >= 0)) {
            longTriggered = true
            val tabIndex = downTab.takeIf { it in tabs.indices }
            if (tabIndex != null) {
                val index = tabIndex
                draggedTab = tabs[index]
                renderer.restartTabMarquee(index)
                if (dock.switchTo(index)) {
                    selection.exitMultiSelect()
                    onNavigationChanged()
                }
            } else {
                downFile?.let { file ->
                    renderer.restartFileMarquee(file)
                    selection.selectOnLongPress(file)
                }
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private val contentLeft get() = systemInsets.left.toFloat()
    private val contentRight get() = width - systemInsets.right.toFloat()
    private val topBarTop get() = systemInsets.top.toFloat()
    private val topBarBottom get() = topBarTop + renderer.topHeight
    private val contentBottom get() = renderer.contentBottom(height, systemInsets.bottom)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = host.getString(R.string.file_manager_description)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val root = storageRoot
        val storageLabel = s(R.string.storage)
        val defaultTabs = mutableListOf(BrowserTab(storageLabel, root, true))
        listOf(
            "Download" to R.string.downloads,
            "Documents" to R.string.documents,
            "Pictures" to R.string.pictures
        ).forEach { (folder, label) ->
            File(root, folder).takeIf(File::isDirectory)?.let {
                defaultTabs += BrowserTab(host.getString(label), it, true)
            }
        }
        val labelForDirectory: (File) -> String = { directory ->
            if (directory.name == "0") s(R.string.storage)
            else directory.name.ifBlank { s(R.string.storage) }
        }
        val restored = dockStore.restore(root, storageLabel, labelForDirectory)
        dock = DockSessionController(
            initialDirectory = root,
            initialTabs = restored?.tabs ?: defaultTabs,
            labelFor = labelForDirectory,
            activeDirectory = restored?.activeDirectory ?: root,
            onChanged = ::persistDock
        )
        lastActiveTab = dock.currentTab
        lastActiveIndex = dock.activeIndex
        persistDock()
        fileActions = FileActionController(
            host = host,
            transactions = transactions,
            currentDirectory = { currentDirectory },
            selectedFiles = selection::files,
            replaceSelection = selection::replace,
            exitMultiSelect = selection::exitMultiSelect,
            setBusy = { message -> busyText = message; invalidate() },
            refresh = { refresh() }
        )
        plugins = PluginRegistry(
            activity = host,
            transactions = transactions,
            setBusy = { message -> busyText = message; invalidate() },
            reportOutput = ::handlePluginOutput
        )
        menus = FileMenuCoordinator(
            host = host,
            menu = menu,
            fileActions = fileActions,
            plugins = plugins,
            appearance = appearance,
            selection = selection,
            dock = dock,
            sorting = sorting,
            dp = ::dp,
            invalidate = { invalidate() },
            searchCurrentFolder = ::searchCurrentFolder,
            onNavigationChanged = ::onNavigationChanged,
            beginDockManagement = ::beginDockManagement,
            changeDockOrder = ::changeDockOrder,
            onLayoutChanged = { scrollY = 0f; revealActiveTab = true; invalidate() },
            openPermissionSettings = { openPermissionSettings() }
        )
        applySystemColors()
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        inertialScroll.onCancel()
        dockInertialScroll.onCancel()
        dockMotion.cancel()
        directoryLoader.close()
        renderer.close()
        if (::fileActions.isInitialized) fileActions.close()
        if (::plugins.isInitialized) plugins.close()
        transactions.close()
    }

    fun persistSession() {
        if (::dock.isInitialized) dockStore.save(tabs, activeTab, durable = true)
    }

    private fun persistDock() {
        if (::dock.isInitialized) dockStore.save(tabs, activeTab)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        systemInsets = if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            UiInsets(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            @Suppress("DEPRECATION")
            UiInsets(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
        }
        invalidate()
        return insets
    }

    fun refresh() {
        if (tabs.isEmpty()) return
        val directory = currentDirectory
        val changingDirectory = displayedDirectoryPath != directory.absolutePath
        directoryTransitioning = changingDirectory
        if (changingDirectory) {
            selection.clear()
            invalidate()
        }
        val mode = sorting.mode
        directoryLoader.load(directory, appearance.showHidden) { listed ->
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            sorting.sorted(listed, mode, collator)
        }
    }

    fun handlePluginOutput(output: File, registerHistory: Boolean) {
        if (!output.exists()) return
        if (registerHistory) fileActions.registerCreatedOutput(output)
        selection.replace(output)
        refresh()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val menuState = menu.renderState()
        renderer.draw(canvas, RenderState(
            tabs = tabs,
            activeTab = activeTab,
            items = items,
            selected = selection.paths,
            multiSelect = selection.multiSelect,
            canAccessStorage = host.hasStorageAccess(),
            canReadDirectory = currentDirectory.canRead(),
            scrollY = scrollY,
            dockScrollX = dockScrollX,
            dockEditing = dockEditing,
            appearance = appearance.snapshot(),
            dragging = dragging,
            tabDragging = tabDragging,
            draggedTabIndex = draggedTab?.let(tabs::indexOf) ?: -1,
            dragX = dragX,
            dragY = dragY,
            dragCount = selection.dragFiles(downFile).size,
            menuKind = menuState.kind,
            menuLayers = menuState.layers,
            menuX = menuState.x,
            menuY = menuState.y,
            menuOriginX = menuState.originX,
            menuOriginY = menuState.originY,
            busyText = busyText,
            motion = menuState.motion,
            dockMotion = dockMotion.snapshot(),
            deferPreviews = scrolling || inertialScroll.isActive,
            directoryTransitioning = directoryTransitioning,
            insets = systemInsets
        ))
        maxScroll = renderer.maxScroll
        scrollY = scrollY.coerceIn(0f, maxScroll)
        maxDockScroll = renderer.maxDockScroll
        dockScrollX = dockScrollX.coerceIn(0f, maxDockScroll)
        if (revealActiveTab) {
            revealActiveTab = false
            val revealed = renderer.scrollToRevealTab(activeTab, dockScrollX)
            if (revealed != dockScrollX) {
                dockScrollX = revealed
                postInvalidateOnAnimation()
            }
        }
    }

    override fun computeScroll() {
        var changed = false
        inertialScroll.next(maxScroll)?.let {
            scrollY = it
            changed = true
        }
        dockInertialScroll.next(maxDockScroll)?.let {
            dockScrollX = it
            changed = true
        }
        if (changed) postInvalidateOnAnimation()
    }

    private fun applySystemColors() {
        host.window.statusBarColor = renderer.surfaceColor(appearance.dark)
        host.window.navigationBarColor = renderer.surfaceColor(appearance.dark)
        if (Build.VERSION.SDK_INT >= 23) {
            host.window.decorView.systemUiVisibility = if (appearance.dark) 0 else SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleSecondaryMousePress(event)) return true
        if (busyText != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                inertialScroll.onDown(event)
                dockInertialScroll.onDown(event)
                onDown(event.x, event.y)
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
                resetGesture()
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleSecondaryMousePress(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleSecondaryMousePress(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val secondaryPress = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> event.actionButton == MotionEvent.BUTTON_SECONDARY
            MotionEvent.ACTION_DOWN -> event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
            else -> false
        }
        if (!secondaryPress) return false
        if (busyText != null) return true

        // Some DeX/Android builds deliver both BUTTON_PRESS and DOWN for one right click.
        val duplicate = event.eventTime - lastSecondaryClickTime <
            GestureTiming.SECONDARY_CLICK_DEDUP_TIMEOUT_MS &&
            max(abs(event.x - lastSecondaryClickX), abs(event.y - lastSecondaryClickY)) < touchSlop
        if (!duplicate) {
            lastSecondaryClickTime = event.eventTime
            lastSecondaryClickX = event.x
            lastSecondaryClickY = event.y
            requestFocus()
            openPointerContextMenu(event.x, event.y)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shortcut = DesktopShortcutResolver.resolve(keyCode, event)
            ?: return super.onKeyDown(keyCode, event)
        if (busyText != null) return true
        if (menu.kind != MenuKind.NONE) menu.close { runDesktopShortcut(shortcut) }
        else runDesktopShortcut(shortcut)
        return true
    }

    private fun onDown(x: Float, y: Float) {
        downX = x; downY = y; lastX = x; lastY = y; dragX = x; dragY = y
        moved = false; scrolling = false; dockScrolling = false
        longTriggered = false; dragging = false; tabDragging = false
        draggedTab = null; dragCancelHover = false; slideSelecting = false
        slideCandidateFile = null
        downCloseTab = null
        downMenuAction = if (menu.kind != MenuKind.NONE) {
            renderer.menuHits.lastOrNull { it.rect.contains(x, y) }?.action
        } else {
            null
        }
        selection.endSlide()
        // The floating action button is visually above list rows and must also win hit testing.
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
        // A selection handle is only a slide-selection candidate until the pointer moves.
        // Keeping the long-press timer alive restores folder dragging from the same area.
        slideCandidateFile = if (inDock) null else renderer.selectionHandleFile(x, y)
        if (menu.kind == MenuKind.NONE && !dockEditing && (downFile != null || downTab >= 0)) {
            handler.postDelayed(longPressRunnable, GestureTiming.longPressTimeoutMs)
        }
    }

    private fun onMove(x: Float, y: Float) {
        dragX = x; dragY = y
        val distance = max(abs(x - downX), abs(y - downY))
        if (longTriggered && distance > touchSlop) {
            moved = true
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
            } else if (downY in topBarBottom..contentBottom && abs(y - downY) > abs(x - downX)) {
                scrolling = true
                scrollY = (scrollY + (lastY - y)).coerceIn(0f, maxScroll)
            }
        }
        lastX = x
        lastY = y
        invalidate()
    }

    private fun onUp(x: Float, y: Float) {
        handler.removeCallbacks(longPressRunnable)
        if (menu.kind != MenuKind.NONE) {
            val dismissedKind = menu.kind
            val releasedAction = renderer.menuHits.lastOrNull { it.rect.contains(x, y) }?.action
            val action = if (!moved) downMenuAction ?: releasedAction else null
            if (action != null && !action.enabled) {
                resetGesture()
                return
            }
            if (action != null && menu.expand(action)) {
                resetGesture()
                return
            }
            if (action != null) {
                menu.close { action.runAt?.invoke(x, y) ?: action.run() }
                resetGesture()
                return
            }
            // The menu is visually above its launchers. Only let an exposed launcher handle
            // the touch when no menu row owns that point.
            if (!moved && switchOpenMenuFromLauncher(x, y)) {
                resetGesture()
                return
            }
            if (menu.isOpening()) {
                resetGesture()
                return
            }
            menu.close {
                if (dismissedKind == MenuKind.FILE && !selection.multiSelect) selection.clear()
            }
            resetGesture()
            return
        }
        val releasedCloseTab = if (dockEditing) {
            renderer.tabCloseHits.lastOrNull { it.rect.contains(x, y) }
                ?.index?.let(tabs::getOrNull)
        } else null
        when {
            dockEditing && !moved && downCloseTab != null && releasedCloseTab === downCloseTab ->
                removeManagedTab(downCloseTab!!)
            dockEditing && !moved && downCloseTab != null -> Unit
            dockEditing && !moved && downTab < 0 -> dockEditing = false
            dragging -> finishDrag(x, y)
            tabDragging -> Unit
            slideSelecting -> Unit
            longTriggered && downFile != null -> menus.showFile(downFile!!, x, y)
            longTriggered && downTab >= 0 && !moved -> menus.beginTabEdit(downTab, x, y)
            !moved && y in topBarTop..topBarBottom && x < contentLeft + renderer.appMenuHitWidth ->
                menus.showApp(topBarTop, topBarBottom, contentLeft)
            !moved && y in topBarTop..topBarBottom && x < contentLeft + renderer.navigateUpHitWidth -> navigateUp()
            !moved && y in topBarTop..topBarBottom &&
                renderer.isSortButton(width, x, systemInsets.right) ->
                menus.showSort(topBarTop, topBarBottom, contentRight)
            !moved && y in topBarTop..topBarBottom -> editAddress()
            !moved && renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom) ->
                menus.showFab(contentRight, contentBottom, renderer.fabOffset)
            !moved && downTab >= 0 -> switchTab(downTab)
            !moved && downFile != null -> {
                renderer.restartFileMarquee(downFile!!)
                selection.click(downFile!!)
            }
            !moved && y in topBarBottom..contentBottom -> selection.clear()
        }
        resetGesture()
    }

    private fun switchOpenMenuFromLauncher(x: Float, y: Float): Boolean {
        val requested = when {
            y in topBarTop..topBarBottom && x < contentLeft + renderer.appMenuHitWidth -> MenuKind.APP
            y in topBarTop..topBarBottom && renderer.isSortButton(width, x, systemInsets.right) -> MenuKind.SORT
            renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom) -> MenuKind.FAB
            else -> return false
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
        return true
    }

    private fun resetGesture() {
        handler.removeCallbacks(longPressRunnable)
        downFile = null; downTab = -1; downCloseTab = null; downMenuAction = null
        moved = false; scrolling = false; dockScrolling = false
        longTriggered = false; dragging = false; tabDragging = false
        draggedTab = null; dragCancelHover = false
        slideSelecting = false; slideCandidateFile = null; selection.endSlide()
        invalidate()
    }

    private fun reorderDraggedTab(x: Float) {
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

    private fun autoScrollDockDuringTabDrag(x: Float) {
        val edge = dp(52f)
        val delta = when {
            x < contentLeft + edge -> -dp(12f)
            x > contentRight - edge -> dp(12f)
            else -> 0f
        }
        if (delta != 0f) dockScrollX = (dockScrollX + delta).coerceIn(0f, maxDockScroll)
    }

    private fun updateDragCancelFeedback(x: Float, y: Float) {
        val hovering = renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom)
        if (hovering && !dragCancelHover) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        dragCancelHover = hovering
    }

    private fun applySlideSelection(x: Float, y: Float) {
        renderer.fileAt(x, y)?.let(::applySlideFile)
    }

    private fun autoScrollSelection(y: Float) {
        val edge = dp(56f)
        val delta = when {
            y < topBarBottom + edge -> -dp(18f)
            y > contentBottom - edge -> dp(18f)
            else -> 0f
        }
        if (delta != 0f) scrollY = (scrollY + delta).coerceIn(0f, maxScroll)
    }

    private fun applySlideFile(file: File) {
        renderer.restartFileMarquee(file)
        selection.applySlide(file)
    }

    private fun beginDockManagement() {
        dockEditing = true
        revealActiveTab = true
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        invalidate()
    }

    private fun removeManagedTab(tab: BrowserTab) {
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

    private fun confirmManagedTabUnpin(tab: BrowserTab) {
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

    private fun onNavigationChanged() {
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

    private fun changeDockOrder(action: () -> Unit) {
        val starts = renderer.tabVisualStarts()
        action()
        dockMotion.reorderFrom(starts)
        if (lastActiveTab === dock.currentTab) lastActiveIndex = dock.activeIndex
        revealActiveTab = true
        invalidate()
    }

    private fun navigateTo(directory: File) {
        if (!directory.isDirectory || !directory.canRead()) {
            host.toast(host.getString(R.string.cannot_read_directory)); return
        }
        dock.navigateTo(directory)
        sorting.markOpened(directory)
        selection.exitMultiSelect(); onNavigationChanged()
    }

    private fun navigateUp() = currentDirectory.parentFile?.let(::navigateTo) ?: Unit

    private fun switchTab(index: Int) {
        renderer.restartTabMarquee(index)
        if (dock.switchTo(index)) {
            sorting.markOpened(dock.currentDirectory)
            selection.exitMultiSelect(); onNavigationChanged()
        }
    }

    fun handleBack(): Boolean {
        if (menu.kind != MenuKind.NONE) { menu.close(); return true }
        if (dockEditing) { dockEditing = false; invalidate(); return true }
        if (dragging || tabDragging) { resetGesture(); return true }
        if (selection.multiSelect) { selection.exitMultiSelect(); return true }
        if (!selection.isEmpty) { selection.clear(); return true }
        val parent = parentForSystemBack() ?: return false
        if (!dock.navigateBackTo(parent)) return false
        sorting.markOpened(dock.currentDirectory)
        onNavigationChanged()
        return true
    }

    private fun parentForSystemBack(): File? {
        if (sameDirectory(currentDirectory, storageRoot)) return null
        return currentDirectory.parentFile?.takeIf { it.isDirectory && it.canRead() }
    }

    private fun sameDirectory(left: File, right: File): Boolean = try {
        left.canonicalFile == right.canonicalFile
    } catch (_: Exception) {
        left.absolutePath == right.absolutePath
    }

    private fun openPointerContextMenu(x: Float, y: Float) {
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

    private fun runDesktopShortcut(shortcut: DesktopShortcut) {
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

    private fun openSelectedFile() {
        val file = selection.files().singleOrNull() ?: return
        if (file.isDirectory) navigateTo(file) else openFile(file)
    }

    private fun navigateHistoryBack() {
        if (dock.goBack()) {
            sorting.markOpened(dock.currentDirectory)
            selection.exitMultiSelect()
            onNavigationChanged()
        }
    }

    private fun switchRelativeTab(direction: Int) {
        if (tabs.size < 2) return
        val index = (activeTab + direction + tabs.size) % tabs.size
        switchTab(index)
    }

    private fun finishDrag(x: Float, y: Float) {
        if (renderer.isFab(width, height, x, y, systemInsets.right, systemInsets.bottom)) {
            host.toast(s(R.string.drag_cancelled))
            return
        }
        val targetTab = renderer.tabHits.lastOrNull { it.rect.contains(x, y) }?.index
        val targetFolder = renderer.fileHits.lastOrNull { it.file.isDirectory && it.rect.contains(x, y) }?.file
        val target = when {
            targetTab != null -> tabs[targetTab].directory
            targetFolder != null -> targetFolder
            else -> null
        }
        val sources = selection.dragFiles(downFile)
        if (target == null) { host.toast(s(R.string.drag_no_target)); return }
        fileActions.move(sources, target)
    }

    private fun editAddress() {
        host.promptPath(currentDirectory.absolutePath) { value ->
            val raw = File(value)
            val target = (if (raw.isAbsolute) raw else File(currentDirectory, value)).let {
                try { it.canonicalFile } catch (_: Exception) { it.absoluteFile }
            }
            when {
                target.isDirectory -> navigateTo(target)
                target.isFile -> openFile(target)
                else -> host.toast(s(R.string.error_path_not_found))
            }
        }
    }

    private fun searchCurrentFolder() {
        host.showFileSearch(items, ::selectSearchResult)
    }

    private fun selectSearchResult(file: File) {
        if (file !in items) return
        if (selection.multiSelect) selection.exitMultiSelect()
        selection.replace(file)
        renderer.restartFileMarquee(file)
        scrollY = renderer.scrollToRevealFile(file, scrollY)
        invalidate()
    }

    private fun openPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            host.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${host.packageName}")))
        }
    }

    private fun openFile(file: File) {
        val opened = plugins.open(file) || host.openFile(file)
        if (opened) {
            sorting.markOpened(file)
            if (sorting.mode == FileSortMode.LAST_OPENED) refresh()
        }
    }

    private fun s(resId: Int, vararg args: Any): String = host.getString(resId, *args)
}
