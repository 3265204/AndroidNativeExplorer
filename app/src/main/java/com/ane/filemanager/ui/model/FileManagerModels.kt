package com.ane.filemanager.ui.model

import android.graphics.RectF
import java.io.File
import com.ane.filemanager.navigation.BrowserTab

internal data class MotionSnapshot(
    val menuProgress: Float = 1f,
    val menuLayerProgress: Float = 1f,
    val animatedMenuLayer: Int = -1
)

internal data class TabMotionStart(
    val tab: BrowserTab,
    val left: Float
)

internal data class DockMotionSnapshot(
    val reorderStarts: List<TabMotionStart> = emptyList(),
    val reorderProgress: Float = 1f,
    val fromTab: Int = -1,
    val toTab: Int = -1,
    val indicatorProgress: Float = 1f,
    val contentProgress: Float = 1f,
    val direction: Int = 0
)

internal data class FileHit(val file: File, val rect: RectF)
internal data class TabHit(val index: Int, val rect: RectF)
internal data class MenuAction(
    val label: String,
    val enabled: Boolean = true,
    val runAt: ((x: Float, y: Float) -> Unit)? = null,
    val children: List<MenuAction> = emptyList(),
    val run: () -> Unit = {}
)
internal data class MenuHit(val action: MenuAction, val rect: RectF)
internal enum class LayoutMode { LIST, GRID }
internal enum class MenuKind { NONE, FAB, FILE, APP, SORT, TAB }

internal data class UiInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

internal data class AppearanceSettings(
    val layoutMode: LayoutMode,
    val dark: Boolean,
    val textSp: Int,
    val iconDp: Int,
    val spacingDp: Int
)

internal data class RenderState(
    val tabs: List<BrowserTab>,
    val activeTab: Int,
    val items: List<File>,
    val selected: Set<String>,
    val multiSelect: Boolean,
    val canAccessStorage: Boolean,
    val canReadDirectory: Boolean,
    val scrollY: Float,
    val dockScrollX: Float,
    val dockEditing: Boolean,
    val appearance: AppearanceSettings,
    val dragReady: Boolean,
    val dragging: Boolean,
    val tabDragging: Boolean,
    val draggedTabIndex: Int,
    val dragX: Float,
    val dragY: Float,
    val dragCount: Int,
    val menuKind: MenuKind,
    val menuLayers: List<List<MenuAction>>,
    val menuX: Float,
    val menuY: Float,
    val menuOriginX: Float,
    val menuOriginY: Float,
    val busyText: String?,
    val motion: MotionSnapshot,
    val dockMotion: DockMotionSnapshot,
    val deferPreviews: Boolean = false,
    val directoryTransitioning: Boolean = false,
    val insets: UiInsets = UiInsets()
)
