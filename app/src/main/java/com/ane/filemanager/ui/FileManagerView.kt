package com.ane.filemanager.ui

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.input.DesktopShortcutResolver
import com.ane.filemanager.navigation.BrowserTab
import com.ane.filemanager.navigation.DockSessionController
import com.ane.filemanager.navigation.DockSessionStore
import com.ane.filemanager.operation.FileActionController
import com.ane.filemanager.operation.FileTransactionService
import com.ane.filemanager.pluginmanager.PluginRegistry
import com.ane.filemanager.ui.appearance.AppearanceController
import com.ane.filemanager.ui.directory.DirectoryLoader
import com.ane.filemanager.ui.menu.FileMenuController
import com.ane.filemanager.ui.menu.FileMenuCoordinator
import com.ane.filemanager.ui.model.MenuKind
import com.ane.filemanager.ui.model.RenderState
import com.ane.filemanager.ui.model.UiInsets
import com.ane.filemanager.ui.motion.GestureTiming
import com.ane.filemanager.ui.motion.DockMotionController
import com.ane.filemanager.ui.motion.InertialScrollController
import com.ane.filemanager.ui.motion.ScrollAxis
import com.ane.filemanager.ui.onboarding.InlineOnboardingCoach
import com.ane.filemanager.ui.onboarding.OnboardingWorkspace
import com.ane.filemanager.ui.onboarding.TutorialProgress.Step
import com.ane.filemanager.ui.render.FileManagerRenderer
import com.ane.filemanager.ui.selection.FileSelectionController
import com.ane.filemanager.ui.sort.FileSortController
import java.io.File
import java.text.Collator
import java.util.Locale

/** Android View boundary: assembles render state and routes pointer/keyboard events to controllers. */
internal class FileManagerView(
    internal val host: MainActivity,
    private val launchDirectory: File? = null,
    internal val pickerAllowsMultiple: Boolean = false,
    private val fileFilter: (File) -> Boolean = { true },
    internal val onPickerFileOpened: ((File) -> Unit)? = null,
    onSelectionChanged: (List<File>) -> Unit = {},
    private val onboardingWorkspace: OnboardingWorkspace? = null,
    onOnboardingCompleted: () -> Unit = {}
) : View(host) {
    internal val density = resources.displayMetrics.density
    internal fun dp(value: Float) = value * density
    internal val handler = Handler(Looper.getMainLooper())
    internal val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    internal val appearance = AppearanceController(context)
    internal val sorting = FileSortController(context)
    internal val menu = FileMenuController { postInvalidateOnAnimation() }
    internal val renderer = FileManagerRenderer(
        context = context,
        pluginFileIcon = { file ->
            if (::plugins.isInitialized) plugins.fileIcon(file) else null
        },
        onInvalidate = { postInvalidateOnAnimation() }
    )
    internal val inertialScroll = InertialScrollController(context) { postInvalidateOnAnimation() }
    internal val dockInertialScroll = InertialScrollController(context, ScrollAxis.HORIZONTAL) {
        postInvalidateOnAnimation()
    }
    internal val dockMotion = DockMotionController { postInvalidateOnAnimation() }
    internal lateinit var fileActions: FileActionController
    internal lateinit var plugins: PluginRegistry
    internal lateinit var menus: FileMenuCoordinator
    internal lateinit var callbacks: FileManagerCallbackCoordinator
    internal lateinit var gestures: FileManagerGestureController
    internal val onboarding = InlineOnboardingCoach(host, onboardingWorkspace, onOnboardingCompleted)
    internal val selection = FileSelectionController(
        invalidate = { invalidate() },
        doubleClickTimeoutMs = GestureTiming.doubleTapTimeoutMs,
        onSelectionChanged = onSelectionChanged
    )
    private val directoryLoader = DirectoryLoader { directory, loaded ->
        post {
            if (!::dock.isInitialized || !callbacks.sameDirectory(currentDirectory, directory)) return@post
            renderer.onDirectoryContentsChanged()
            items = loaded.filter { it.isDirectory || fileFilter(it) }
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

    internal val storageRoot = onboardingWorkspace?.root ?: host.initialDirectory()
    internal val transactions = FileTransactionService(storageRoot)
    internal val dockStore = DockSessionStore(context)
    internal lateinit var dock: DockSessionController
    internal val tabs get() = dock.tabs
    internal val activeTab get() = dock.activeIndex
    internal val currentDirectory get() = dock.currentDirectory
    internal var items = listOf<File>()
    internal var displayedDirectoryPath: String? = null
    internal var directoryTransitioning = false
    internal var scrollY = 0f
    internal var maxScroll = 0f
    internal var dockScrollX = 0f
    internal var maxDockScroll = 0f
    internal var revealActiveTab = true
    internal var dockEditing = false
    internal lateinit var lastActiveTab: BrowserTab
    internal var lastActiveIndex = 0
    internal var pendingContentRevealPath: String? = null
    internal var busyText: String? = null
    internal var systemInsets = UiInsets()

    internal val contentLeft get() = systemInsets.left.toFloat()
    internal val contentRight get() = width - systemInsets.right.toFloat()
    internal val topBarTop get() = systemInsets.top.toFloat()
    internal val topBarBottom get() = topBarTop + renderer.topHeight
    internal val contentBottom get() = renderer.contentBottom(height, systemInsets.bottom)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = host.getString(R.string.file_manager_description)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val root = storageRoot
        val storageLabel = onboardingWorkspace?.rootLabel ?: s(R.string.storage)
        val defaultTabs = if (onboardingWorkspace != null) {
            mutableListOf(
                BrowserTab(onboardingWorkspace.rootLabel, root, true),
                BrowserTab(onboardingWorkspace.moveTargetLabel, onboardingWorkspace.moveTarget, true),
                BrowserTab(onboardingWorkspace.copyTargetLabel, onboardingWorkspace.copyTarget, true)
            )
        } else {
            mutableListOf(BrowserTab(storageLabel, root, true)).apply {
                listOf(
                    "Download" to R.string.downloads,
                    "Documents" to R.string.documents,
                    "Pictures" to R.string.pictures
                ).forEach { (folder, label) ->
                    File(root, folder).takeIf(File::isDirectory)?.let {
                        add(BrowserTab(host.getString(label), it, true))
                    }
                }
            }
        }
        val labelForDirectory: (File) -> String = { directory ->
            if (directory.name == "0") s(R.string.storage)
            else directory.name.ifBlank { s(R.string.storage) }
        }
        val restored = if (onboardingWorkspace == null) {
            dockStore.restore(root, storageLabel, labelForDirectory)
        } else null
        dock = DockSessionController(
            initialDirectory = root,
            initialTabs = restored?.tabs ?: defaultTabs,
            labelFor = labelForDirectory,
            activeDirectory = restored?.activeDirectory ?: root,
            onChanged = ::persistDock
        )
        launchDirectory
            ?.takeIf { it.isDirectory && it.canRead() }
            ?.let(dock::navigateTo)
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
            refresh = { refresh() },
            onCopied = onboarding::copied,
            onMoveCompleted = onboarding::moveCompleted,
            onPasteCompleted = onboarding::pasteCompleted
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
            searchCurrentFolder = { callbacks.searchCurrentFolder() },
            onNavigationChanged = { callbacks.onNavigationChanged() },
            beginDockManagement = { callbacks.beginDockManagement() },
            changeDockOrder = { action -> callbacks.changeDockOrder(action) },
            onLayoutChanged = { scrollY = 0f; revealActiveTab = true; invalidate() },
            openPermissionSettings = { callbacks.openPermissionSettings() }
        )
        callbacks = FileManagerCallbackCoordinator(this)
        gestures = FileManagerGestureController(this, callbacks)
        applySystemColors()
        if (pickerAllowsMultiple) selection.enterMultiSelect()
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
        if (onboardingWorkspace == null && ::dock.isInitialized) {
            dockStore.save(tabs, activeTab, durable = true)
        }
    }

    fun selectedFiles(): List<File> = selection.files().filter(File::isFile)

    fun pickerDirectory(): File = currentDirectory

    private fun persistDock() {
        if (onboardingWorkspace == null && ::dock.isInitialized) dockStore.save(tabs, activeTab)
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
        val dragSources = selection.dragFiles(gestures.downFile)
        renderer.draw(canvas, RenderState(
            tabs = tabs,
            activeTab = activeTab,
            items = items,
            selected = selection.paths,
            multiSelect = selection.multiSelect,
            canAccessStorage = onboardingWorkspace != null || host.hasStorageAccess(),
            canReadDirectory = currentDirectory.canRead(),
            scrollY = scrollY,
            dockScrollX = dockScrollX,
            dockEditing = dockEditing,
            appearance = appearance.snapshot(),
            dragReady = gestures.longTriggered && !gestures.longPressMenuShown &&
                !gestures.dragging && gestures.downFile != null,
            dragging = gestures.dragging,
            tabDragging = gestures.tabDragging,
            draggedTabIndex = gestures.draggedTab?.let(tabs::indexOf) ?: -1,
            dragX = gestures.dragX,
            dragY = gestures.dragY,
            dragCount = dragSources.size,
            dragSources = dragSources,
            menuKind = menuState.kind,
            menuLayers = menuState.layers,
            menuX = menuState.x,
            menuY = menuState.y,
            menuOriginX = menuState.originX,
            menuOriginY = menuState.originY,
            busyText = busyText,
            motion = menuState.motion,
            dockMotion = dockMotion.snapshot(),
            deferPreviews = gestures.scrolling || inertialScroll.isActive,
            directoryTransitioning = directoryTransitioning,
            addressOverride = onboardingWorkspace?.let {
                s(R.string.tutorial_workspace_address, tabs[activeTab].label)
            },
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
        onboarding.draw(
            canvas,
            onboardingTarget(),
            onboardingSecondaryTarget(),
            gestures.dragging,
            gestures.longTriggered
        )
    }

    internal fun onboardingTarget(): RectF? {
        if (!onboarding.active) return null
        return when (onboarding.step) {
            Step.SELECT, Step.MOVE_TO_DOCK, Step.LONG_PRESS_MENU, Step.OPEN ->
                renderer.fileHits.firstOrNull {
                it.file.absolutePath == onboarding.targetPath
            }?.rect
            Step.OPEN_MOVE_DESTINATION, Step.OPEN_COPY_DESTINATION, Step.TABS ->
                onboardingTargetTabIndex()?.let { index ->
                    renderer.tabHits.firstOrNull { it.index == index }?.rect
                }
            Step.PASTE_OPEN_MENU -> {
                val centerX = contentRight - renderer.fabOffset
                val centerY = contentBottom - renderer.fabOffset
                RectF(centerX - dp(34f), centerY - dp(34f), centerX + dp(34f), centerY + dp(34f))
            }
            Step.COPY_CHOOSE -> renderer.menuHits.firstOrNull {
                it.action.label == s(R.string.action_copy_selected) ||
                    it.action.label == s(R.string.action_copy)
            }?.rect
            Step.PASTE_CHOOSE -> renderer.menuHits.firstOrNull {
                it.action.label == s(R.string.action_paste_here)
            }?.rect
            Step.COMPLETE -> null
        }?.let(::RectF)
    }

    private fun onboardingSecondaryTarget(): RectF? {
        if (!onboarding.active || onboarding.step != Step.MOVE_TO_DOCK) return null
        val index = onboardingTargetTabIndex() ?: return null
        return renderer.tabHits.firstOrNull { it.index == index }?.rect
    }

    private fun onboardingTargetTabIndex(): Int? = when (onboarding.step) {
        Step.MOVE_TO_DOCK, Step.OPEN_MOVE_DESTINATION ->
            onboarding.workspace?.moveTarget?.let(::tabIndexForDirectory)
        Step.OPEN_COPY_DESTINATION -> onboarding.workspace?.copyTarget?.let(::tabIndexForDirectory)
        Step.TABS -> renderer.tabHits.firstOrNull { it.index != activeTab }?.index
        else -> null
    }

    private fun tabIndexForDirectory(directory: File): Int? = tabs.indexOfFirst {
        callbacks.sameDirectory(it.directory, directory)
    }.takeIf { it >= 0 }

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

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (gestures.handlesGenericMotion(event)) true else super.onGenericMotionEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onboarding.active) {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            return true
        }
        val shortcut = DesktopShortcutResolver.resolve(keyCode, event)
            ?: return super.onKeyDown(keyCode, event)
        if (busyText != null) return true
        if (menu.kind != MenuKind.NONE) menu.close { callbacks.runDesktopShortcut(shortcut) }
        else callbacks.runDesktopShortcut(shortcut)
        return true
    }

    fun handleBack(): Boolean = callbacks.handleBack()

    private fun s(resId: Int, vararg args: Any): String = host.getString(resId, *args)
}
