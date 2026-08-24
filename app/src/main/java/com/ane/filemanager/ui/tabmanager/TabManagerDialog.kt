package com.ane.filemanager.ui.tabmanager

import android.content.ClipData
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.navigation.BrowserTab
import com.ane.filemanager.navigation.DockSessionController
import com.ane.filemanager.navigation.DockSessionStore
import com.ane.filemanager.ui.dialog.AneDialog
import com.ane.filemanager.ui.motion.GestureTiming
import com.ane.filemanager.ui.secondary.SecondaryPageScaffold
import com.ane.filemanager.ui.theme.AppThemePalette

/** Full-screen owner for persisted Dock tabs and their startup restore policy. */
internal class TabManagerDialog(
    private val host: MainActivity,
    private val dock: DockSessionController,
    private val originX: Float,
    private val originY: Float,
    private val onActiveTabChanged: () -> Unit,
    private val onTabsChanged: () -> Unit
) {
    private val dark = host.getSharedPreferences("appearance", 0).getBoolean("dark", false)
    private val theme = AppThemePalette.resolve(host, dark)
    private val store = DockSessionStore(host)
    private lateinit var page: SecondaryPageScaffold
    private lateinit var summary: TextView
    private lateinit var controls: LinearLayout
    private lateinit var cards: GridLayout
    private lateinit var tabScroll: ScrollView
    private val cardBindings = mutableListOf<CardBinding>()
    private var selectedTab: BrowserTab? = dock.currentTab
    private var dragTargetTab: BrowserTab? = null
    private var lastClickedTab: BrowserTab? = null
    private var lastClickTime = 0L

    fun show() {
        page = SecondaryPageScaffold(
            host = host,
            theme = theme,
            title = host.getString(R.string.tab_manager_title),
            closeDescription = host.getString(R.string.tab_manager_close_page),
            originX = originX,
            originY = originY,
            onUsableWidthChanged = { rebuild() }
        )
        val content = page.content
        summary = page.summary
        controls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })
        cards = GridLayout(host).apply {
            orientation = GridLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
            setOnDragListener { _, event -> handleCardDrag(event) }
        }
        tabScroll = ScrollView(host).apply {
            isFillViewport = true
            addView(cards, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(tabScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rebuild()
        page.show()
    }

    private fun rebuild() {
        val temporaryCount = dock.tabs.count { !it.pinned }
        val compact = availableWidthDp() < COMPACT_WIDTH_DP
        summary.text = host.getString(if (compact) {
            R.string.tab_manager_summary
        } else {
            R.string.tab_manager_summary_reorder
        }, dock.tabs.size, temporaryCount)
        rebuildControls(temporaryCount)
        rebuildCards()
    }

    private fun rebuildCards() {
        lastClickedTab = null
        lastClickTime = 0L
        cards.removeAllViews()
        cardBindings.clear()
        val columns = cardColumns()
        cards.columnCount = columns
        dock.tabs.forEachIndexed { index, tab ->
            val row = index / columns
            val column = index % columns
            cards.addView(tabCard(index, tab), GridLayout.LayoutParams(
                GridLayout.spec(row), GridLayout.spec(column, 1, 1f)
            ).apply {
                width = 0
                height = -2
                setMargins(
                    if (column == 0) 0 else dp(5),
                    0,
                    if (column == columns - 1) 0 else dp(5),
                    dp(11)
                )
            })
        }
    }

    private fun rebuildControls(temporaryCount: Int) {
        controls.removeAllViews()
        val availableWidth = availableWidthDp()
        val compact = availableWidth < COMPACT_WIDTH_DP
        val wide = availableWidth >= WIDE_CONTROLS_WIDTH_DP
        controls.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val restoreTemporary = store.restoresTemporaryTabs()
        val policy = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 12 else 16), dp(if (compact) 9 else 13), dp(12), dp(if (compact) 9 else 13))
            background = rounded(theme.surface, 18f, theme.outline)
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(host.getString(R.string.tab_manager_restore_title),
                    if (compact) 15f else 16f, theme.text, Typeface.BOLD))
                if (!compact) addView(label(host.getString(if (restoreTemporary) {
                    R.string.tab_manager_restore_all_hint
                } else {
                    R.string.tab_manager_restore_pinned_hint
                }), 12.5f, theme.muted).apply { setPadding(0, dp(4), dp(8), 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(actionButton(host.getString(if (restoreTemporary) {
                R.string.tab_manager_restore_all
            } else {
                R.string.tab_manager_restore_pinned
            }), primary = restoreTemporary) {
                store.setRestoreTemporaryTabs(!restoreTemporary)
                rebuildControls(temporaryCount)
            })
        }
        controls.addView(policy, if (wide && temporaryCount > 0) {
            LinearLayout.LayoutParams(0, -2, 1f)
        } else {
            LinearLayout.LayoutParams(-1, -2)
        })

        if (temporaryCount > 0) {
            val closeAll = actionButton(
                host.getString(R.string.tab_manager_close_all_temporary),
                destructive = true
            ) { closeAllTemporary() }
            controls.addView(closeAll, if (wide) {
                LinearLayout.LayoutParams(0, -2, .52f).apply { marginStart = dp(9) }
            } else {
                LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) }
            }.apply {
                gravity = Gravity.END
            })
        }
    }

    private fun tabCard(index: Int, tab: BrowserTab): View {
        val active = index == dock.activeIndex
        val emphasized = selectedTab === tab || dragTargetTab === tab
        val stripeColor = if (emphasized) theme.primary else theme.outline
        lateinit var stripe: View
        return LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(if (emphasized) theme.selected else theme.surface, 18f, stripeColor)
            stripe = View(host).apply { setBackgroundColor(stripeColor) }
            addView(stripe, LinearLayout.LayoutParams(dp(5), -1))
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(13), dp(12))
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(tab.label, 17f, theme.text, Typeface.BOLD),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    if (active) addView(statusChip(host.getString(R.string.tab_manager_active), true))
                    addView(statusChip(host.getString(if (tab.pinned) {
                        R.string.tab_manager_pinned
                    } else {
                        R.string.tab_manager_temporary
                    }), false), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
                })
                addView(label(tab.directory.absolutePath, 12.5f, theme.muted).apply {
                    maxLines = 2
                    setPadding(0, dp(7), 0, dp(10))
                })
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addDistributedAction(host.getString(R.string.tab_manager_rename)) {
                        rename(index, tab)
                    }
                    if (index > 0) {
                        addDistributedAction(host.getString(R.string.tab_manager_change_directory)) {
                            changeDirectory(index, tab)
                        }
                        addDistributedAction(host.getString(if (tab.pinned) {
                            R.string.tab_manager_unpin
                        } else {
                            R.string.tab_manager_pin
                        })) { togglePinned(index) }
                        if (!tab.pinned) addDistributedAction(
                            host.getString(R.string.tab_manager_close), destructive = true
                        ) { close(index) }
                    }
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            isClickable = true
            isFocusable = true
            setOnClickListener { clickCard(index, tab) }
            setOnLongClickListener { beginCardDrag(tab, this) }
            cardBindings += CardBinding(tab, this, stripe)
        }
    }

    private fun clickCard(index: Int, tab: BrowserTab) {
        val now = SystemClock.uptimeMillis()
        val doubleClick = lastClickedTab === tab && now - lastClickTime <= GestureTiming.doubleTapTimeoutMs
        lastClickedTab = if (doubleClick) null else tab
        lastClickTime = now
        if (doubleClick) {
            open(index)
        } else {
            selectedTab = tab
            updateCardSelection()
        }
    }

    private fun updateCardSelection() {
        cardBindings.forEach { binding ->
            val emphasized = selectedTab === binding.tab || dragTargetTab === binding.tab
            val stripe = if (emphasized) theme.primary else theme.outline
            binding.root.background = rounded(if (emphasized) theme.selected else theme.surface, 18f, stripe)
            binding.stripe.setBackgroundColor(stripe)
        }
    }

    private fun beginCardDrag(tab: BrowserTab, root: View): Boolean {
        val index = dock.tabs.indexOfFirst { it === tab }
        if (index <= 0) return false
        selectedTab = tab
        dragTargetTab = tab
        lastClickedTab = null
        updateCardSelection()
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val clip = ClipData.newPlainText("ane-tab", tab.directory.absolutePath)
        val started = if (Build.VERSION.SDK_INT >= 24) {
            root.startDragAndDrop(clip, View.DragShadowBuilder(root), tab, 0)
        } else {
            @Suppress("DEPRECATION")
            root.startDrag(clip, View.DragShadowBuilder(root), tab, 0)
        }
        if (!started) finishCardDrag()
        return started
    }

    private fun handleCardDrag(event: DragEvent): Boolean {
        val dragged = event.localState as? BrowserTab ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> dock.tabs.indexOfFirst { it === dragged } > 0
            DragEvent.ACTION_DRAG_LOCATION -> {
                autoScrollCards(event.y)
                val target = cardBindings.minByOrNull { binding ->
                    val x = event.x - (binding.root.left + binding.root.width / 2f)
                    val y = event.y - (binding.root.top + binding.root.height / 2f)
                    x * x + y * y
                }?.tab
                if (target != null) {
                    dragTargetTab = target
                    val from = dock.tabs.indexOfFirst { it === dragged }
                    val rawTarget = dock.tabs.indexOfFirst { it === target }
                    val to = rawTarget.coerceAtLeast(1)
                    if (from > 0 && to > 0 && from != to) {
                        dock.moveTab(from, to)
                        onTabsChanged()
                        rebuildCards()
                    } else {
                        updateCardSelection()
                    }
                }
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                dragTargetTab = null
                updateCardSelection()
                true
            }
            DragEvent.ACTION_DROP -> true
            DragEvent.ACTION_DRAG_ENDED -> {
                finishCardDrag()
                true
            }
            else -> true
        }
    }

    private fun autoScrollCards(eventY: Float) {
        val viewportY = eventY - tabScroll.scrollY
        val edge = dp(64).toFloat()
        val delta = when {
            viewportY < edge -> -dp(18)
            viewportY > tabScroll.height - edge -> dp(18)
            else -> 0
        }
        if (delta != 0) tabScroll.scrollBy(0, delta)
    }

    private fun finishCardDrag() {
        dragTargetTab = null
        updateCardSelection()
    }

    private fun statusChip(value: String, active: Boolean): TextView =
        label(value, 11.5f, if (active) theme.primary else theme.muted, Typeface.BOLD).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(theme.surface2, 9f, if (active) theme.primary else theme.outline)
        }

    private fun actionButton(
        value: String,
        primary: Boolean = false,
        destructive: Boolean = false,
        action: () -> Unit
    ): TextView = label(value, 13f, when {
        primary -> Color.WHITE
        destructive -> theme.danger
        else -> theme.text
    }, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(13), dp(9), dp(13), dp(9))
        background = rounded(if (primary) theme.primary else theme.surface2, 11f, theme.outline)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun LinearLayout.addDistributedAction(
        value: String,
        destructive: Boolean = false,
        action: () -> Unit
    ) {
        val button = actionButton(value, destructive = destructive, action = action).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(7), dp(9), dp(7), dp(9))
        }
        val hasPrevious = childCount > 0
        addView(button, LinearLayout.LayoutParams(0, -2, value.length.coerceAtLeast(2).toFloat()).apply {
            if (hasPrevious) marginStart = dp(7)
        })
    }

    private fun open(index: Int) {
        if (dock.switchTo(index)) onActiveTabChanged()
        closeAnimated()
    }

    private fun togglePinned(index: Int) {
        if (index !in dock.tabs.indices || index == 0) return
        if (dock.tabs[index].pinned) dock.unpin(index) else dock.pin(index)
        onTabsChanged()
        rebuild()
    }

    private fun rename(index: Int, tab: BrowserTab) {
        if (dock.tabs.getOrNull(index) !== tab) return
        host.promptName(host.getString(R.string.dialog_rename_tab), tab.label) { value ->
            val currentIndex = dock.tabs.indexOfFirst { it === tab }
            if (currentIndex < 0) return@promptName
            dock.rename(currentIndex, value)
            onTabsChanged()
            rebuild()
        }
    }

    private fun changeDirectory(index: Int, tab: BrowserTab) {
        if (index == 0 || dock.tabs.getOrNull(index) !== tab) return
        AneDialog.input(
            activity = host,
            title = host.getString(R.string.tab_manager_directory_title),
            initial = tab.directory.absolutePath,
            hint = host.getString(R.string.tab_manager_directory_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            confirmLabel = host.getString(R.string.dialog_confirm),
            cancelLabel = host.getString(R.string.dialog_cancel),
            validate = { value ->
                val target = canonicalDirectory(value)
                when {
                    target == null -> host.getString(R.string.tab_manager_directory_invalid)
                    dock.indexOfDirectory(target).let { it >= 0 && dock.tabs[it] !== tab } ->
                        host.getString(R.string.tab_manager_directory_duplicate)
                    else -> null
                }
            },
            onConfirm = { value ->
                val target = canonicalDirectory(value) ?: return@input
                val currentIndex = dock.tabs.indexOfFirst { it === tab }
                if (currentIndex < 0) return@input
                val active = dock.currentTab
                if (dock.changeDirectory(currentIndex, target)) {
                    if (dock.currentTab === active && active === tab) onActiveTabChanged() else onTabsChanged()
                    rebuild()
                }
            }
        )
    }

    private fun canonicalDirectory(value: String): java.io.File? = runCatching {
        java.io.File(value.trim()).canonicalFile.takeIf { it.isDirectory && it.canRead() }
    }.getOrNull()

    private fun close(index: Int) {
        val closingTab = dock.tabs.getOrNull(index) ?: return
        val active = dock.currentTab
        if (!dock.close(index)) return
        if (selectedTab === closingTab) selectedTab = dock.currentTab
        if (dock.currentTab !== active) onActiveTabChanged() else onTabsChanged()
        rebuild()
    }

    private fun closeAllTemporary() {
        val active = dock.currentTab
        val closed = dock.closeTemporaryTabs()
        if (closed <= 0) return
        if (selectedTab?.let { selected -> dock.tabs.none { it === selected } } != false) {
            selectedTab = dock.currentTab
        }
        if (dock.currentTab !== active) onActiveTabChanged() else onTabsChanged()
        host.toast(host.getString(R.string.tab_manager_closed_count, closed))
        rebuild()
    }

    private fun closeAnimated() {
        if (::page.isInitialized) page.close()
    }

    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(host).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * host.resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * host.resources.displayMetrics.density + .5f).toInt()

    private fun availableWidthDp(): Int {
        return if (::page.isInitialized) page.usableWidthDp else {
            (host.resources.displayMetrics.widthPixels / host.resources.displayMetrics.density).toInt() - 36
        }.coerceAtLeast(1)
    }

    private fun cardColumns(): Int =
        (availableWidthDp() / MIN_CARD_WIDTH_DP).coerceIn(1, MAX_CARD_COLUMNS)

    private data class CardBinding(val tab: BrowserTab, val root: LinearLayout, val stripe: View)

    private companion object {
        const val COMPACT_WIDTH_DP = 480
        const val WIDE_CONTROLS_WIDTH_DP = 700
        const val MIN_CARD_WIDTH_DP = 310
        const val MAX_CARD_COLUMNS = 3
    }
}
