package com.ane.filemanager.plugin.api.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.ane.filemanager.plugin.api.PluginHost

/**
 * UI capability exposed by the host. The API owns this contract; the host owns
 * the implementation and therefore the actual visual policy.
 */
interface AnePluginUi {
    val theme: AneTheme

    fun page(
        title: String,
        closeDescription: String,
        onClosed: () -> Unit = {}
    ): AnePluginPage

    fun browserPage(
        title: String,
        closeDescription: String,
        primaryActionLabel: CharSequence,
        onPrimaryAction: () -> Unit
    ): AnePluginBrowserPage

    fun text(
        context: Context,
        value: CharSequence = "",
        role: AneTextRole = AneTextRole.BODY,
        tone: AneTextTone = AneTextTone.TEXT
    ): TextView

    fun primaryButton(
        context: Context,
        label: CharSequence,
        onClick: () -> Unit
    ): Button

    fun compactButton(
        context: Context,
        label: CharSequence,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView

    fun compactButtonBar(
        context: Context,
        actions: List<AneUiAction>
    ): HorizontalScrollView

    /** Applies the host console layout, surface and key-bar spacing to a plugin page. */
    fun populateConsolePage(page: AnePluginPage, console: View, keyBar: View)

    /** Places a semantic previous/next overlay; plugins do not own styling or geometry. */
    fun attachMediaSwitchButton(
        context: Context,
        container: FrameLayout,
        direction: AneMediaDirection,
        symbol: CharSequence,
        contentDescription: CharSequence,
        onClick: () -> Unit
    ): Button

    fun mediaPlaybackControls(
        context: Context,
        previousSymbol: CharSequence,
        previousDescription: CharSequence,
        playSymbol: CharSequence,
        playDescription: CharSequence,
        nextSymbol: CharSequence,
        nextDescription: CharSequence,
        onPrevious: () -> Unit,
        onPlay: () -> Unit,
        onNext: () -> Unit
    ): AneMediaPlaybackControls

    fun attachMediaArtwork(
        context: Context,
        container: FrameLayout,
        placeholderSymbol: CharSequence
    ): AneMediaArtwork

    fun configureMediaControls(container: LinearLayout)

    fun configureMediaRoot(root: View)
    fun mediaStage(context: Context): FrameLayout
    fun attachMediaProgress(context: Context, container: FrameLayout): ProgressBar
    fun updateMediaNavigation(button: Button, enabled: Boolean)
    fun animateMediaExit(
        view: View,
        direction: AneMediaDirection,
        stageWidth: Int,
        onFinished: () -> Unit
    )
    fun animateMediaEnter(
        view: View,
        direction: AneMediaDirection,
        stageWidth: Int,
        onFinished: () -> Unit
    )

    fun badge(context: Context, label: CharSequence, backgroundColor: Int): TextView

    fun badgeColor(kind: AneBadgeKind, seed: String = ""): Int

    fun emptyState(context: Context, message: CharSequence): TextView

    fun listRow(
        context: Context,
        leading: View,
        title: CharSequence,
        detail: CharSequence,
        chevron: Boolean,
        onClick: (() -> Unit)? = null
    ): LinearLayout

    fun rounded(
        fill: Int,
        radiusPx: Float,
        outline: Int? = null,
        strokePx: Int = 1
    ): GradientDrawable

    fun message(title: String, message: String, actions: List<AneDialogAction>)

    fun choices(
        title: String,
        labels: List<String>,
        cancelLabel: String,
        onSelected: (Int) -> Unit
    )

    fun input(
        title: String,
        initial: String = "",
        hint: String = "",
        inputType: Int,
        confirmLabel: String,
        cancelLabel: String,
        validate: (String) -> String? = { null },
        onCancel: (() -> Unit)? = null,
        onConfirm: (String) -> Unit
    )
}

/** Host-owned full-screen page. Plugins own only the content inserted into it. */
interface AnePluginPage {
    val content: LinearLayout
    val summary: TextView
    fun show()
    fun close()
}

interface AnePluginBrowserPage {
    val summary: TextView
    fun setBreadcrumbs(items: List<AneBreadcrumb>)
    fun setRows(rows: List<View>, emptyState: View? = null)
    fun show()
    fun close()
}

data class AneBreadcrumb(
    val label: CharSequence,
    val current: Boolean,
    val onClick: () -> Unit = {}
)

enum class AneBadgeKind {
    FOLDER,
    FILE
}

data class AneUiAction(
    val label: CharSequence,
    val primary: Boolean = false,
    val run: () -> Unit
)

enum class AneMediaDirection {
    PREVIOUS,
    NEXT
}

class AneMediaPlaybackControls(
    val view: LinearLayout,
    val previous: Button,
    val play: Button,
    val next: Button
)

class AneMediaArtwork(
    val image: ImageView,
    val placeholder: TextView
)

/** Optional v3 capability implemented by the current host without changing PluginHost's ABI. */
interface PluginUiProvider {
    val pluginUi: AnePluginUi
}

val PluginHost.ui: AnePluginUi
    get() = (this as? PluginUiProvider)?.pluginUi
        ?: error("The current host does not provide the ANE UI capability")

/** Applies the host-compatible editor surface without adding a required provider method. */
fun AnePluginUi.configureTextEditor(editor: EditText) =
    AneComponents.configureTextEditor(editor, theme)
