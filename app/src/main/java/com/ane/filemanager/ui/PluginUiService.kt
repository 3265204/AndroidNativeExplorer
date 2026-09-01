package com.ane.filemanager.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import com.ane.filemanager.MainActivity
import com.ane.filemanager.plugin.api.ui.AneComponents
import com.ane.filemanager.plugin.api.ui.AneBadgeKind
import com.ane.filemanager.plugin.api.ui.AneBreadcrumb
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.plugin.api.ui.AnePluginPage
import com.ane.filemanager.plugin.api.ui.AnePluginBrowserPage
import com.ane.filemanager.plugin.api.ui.AnePluginUi
import com.ane.filemanager.plugin.api.ui.AneMediaDirection
import com.ane.filemanager.plugin.api.ui.AneMediaPlaybackControls
import com.ane.filemanager.plugin.api.ui.AneMediaArtwork
import com.ane.filemanager.plugin.api.ui.AneShapes
import com.ane.filemanager.plugin.api.ui.AneTextRole
import com.ane.filemanager.plugin.api.ui.AneTextTone
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.plugin.api.ui.AneUiAction
import com.ane.filemanager.plugin.api.ui.AneUiTokens
import com.ane.filemanager.plugin.api.ui.aneDp
import com.ane.filemanager.ui.secondary.SecondaryPageScaffold

/** Current host implementation of the public plugin UI contract. */
internal class PluginUiService(private val host: MainActivity) : AnePluginUi {
    override val theme: AneTheme
        get() = AneTheme.resolve(host)

    override fun page(
        title: String,
        closeDescription: String,
        onClosed: () -> Unit
    ): AnePluginPage {
        val page = SecondaryPageScaffold(
            host = host,
            theme = theme,
            title = title,
            closeDescription = closeDescription,
            originX = host.resources.displayMetrics.widthPixels / 2f,
            originY = host.resources.displayMetrics.heightPixels / 2f,
            onClosed = onClosed
        )
        return object : AnePluginPage {
            override val content get() = page.content
            override val summary get() = page.summary
            override fun show() = page.show()
            override fun close() = page.close()
        }
    }

    override fun browserPage(
        title: String,
        closeDescription: String,
        primaryActionLabel: CharSequence,
        onPrimaryAction: () -> Unit
    ): AnePluginBrowserPage {
        val base = page(title, closeDescription)
        val breadcrumbItems = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                host.aneDp(BROWSER_BREADCRUMB_HORIZONTAL_PADDING_DP),
                0,
                host.aneDp(BROWSER_BREADCRUMB_HORIZONTAL_PADDING_DP),
                0
            )
        }
        val breadcrumbBar = HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            background = AneShapes.rounded(
                theme.surface,
                host.aneDp(BROWSER_BREADCRUMB_RADIUS_DP).toFloat(),
                theme.outline,
                host.aneDp(1)
            )
            addView(breadcrumbItems, FrameLayout.LayoutParams(-2, -1))
        }
        val rowContainer = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, host.aneDp(BROWSER_LIST_BOTTOM_PADDING_DP))
        }
        base.content.addView(
            breadcrumbBar,
            LinearLayout.LayoutParams(-1, host.aneDp(BROWSER_BREADCRUMB_HEIGHT_DP)).apply {
                topMargin = host.aneDp(BROWSER_BREADCRUMB_TOP_MARGIN_DP)
                bottomMargin = host.aneDp(BROWSER_BREADCRUMB_BOTTOM_MARGIN_DP)
            }
        )
        base.content.addView(ScrollView(host).apply {
            isFillViewport = true
            addView(rowContainer, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        base.content.addView(
            AneComponents.primaryButton(host, theme, primaryActionLabel) {
                base.close()
                onPrimaryAction()
            },
            LinearLayout.LayoutParams(-1, host.aneDp(BROWSER_PRIMARY_BUTTON_HEIGHT_DP)).apply {
                topMargin = host.aneDp(BROWSER_PRIMARY_BUTTON_TOP_MARGIN_DP)
            }
        )
        return object : AnePluginBrowserPage {
            override val summary get() = base.summary

            override fun setBreadcrumbs(items: List<AneBreadcrumb>) {
                breadcrumbItems.removeAllViews()
                items.forEachIndexed { index, item ->
                    if (index > 0) breadcrumbItems.addView(AneComponents.text(
                        host,
                        theme,
                        "›",
                        AneTextRole.BREADCRUMB_DIVIDER,
                        AneTextTone.MUTED
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(
                            host.aneDp(BROWSER_DIVIDER_HORIZONTAL_PADDING_DP),
                            0,
                            host.aneDp(BROWSER_DIVIDER_HORIZONTAL_PADDING_DP),
                            0
                        )
                    })
                    breadcrumbItems.addView(AneComponents.text(
                        host,
                        theme,
                        item.label,
                        if (item.current) AneTextRole.LABEL else AneTextRole.BREADCRUMB,
                        if (item.current) AneTextTone.TEXT else AneTextTone.PRIMARY
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(
                            host.aneDp(BROWSER_ITEM_HORIZONTAL_PADDING_DP),
                            0,
                            host.aneDp(BROWSER_ITEM_HORIZONTAL_PADDING_DP),
                            0
                        )
                        isClickable = !item.current
                        if (!item.current) setOnClickListener { item.onClick() }
                    }, LinearLayout.LayoutParams(-2, -1))
                }
            }

            override fun setRows(rows: List<View>, emptyState: View?) {
                rowContainer.removeAllViews()
                rows.forEach { row ->
                    rowContainer.addView(
                        row,
                        LinearLayout.LayoutParams(-1, host.aneDp(BROWSER_ROW_HEIGHT_DP)).apply {
                            bottomMargin = host.aneDp(BROWSER_ROW_BOTTOM_MARGIN_DP)
                        }
                    )
                }
                emptyState?.let {
                    rowContainer.addView(it, LinearLayout.LayoutParams(-1, -2))
                }
            }

            override fun show() = base.show()
            override fun close() = base.close()
        }
    }

    override fun text(
        context: Context,
        value: CharSequence,
        role: AneTextRole,
        tone: AneTextTone
    ): TextView = AneComponents.text(context, theme, value, role, tone)

    override fun primaryButton(
        context: Context,
        label: CharSequence,
        onClick: () -> Unit
    ): Button = AneComponents.primaryButton(context, theme, label, onClick)

    override fun compactButton(
        context: Context,
        label: CharSequence,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView = AneComponents.compactButton(context, theme, label, primary, onClick)

    override fun compactButtonBar(
        context: Context,
        actions: List<AneUiAction>
    ): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            actions.forEachIndexed { index, action ->
                addView(
                    AneComponents.compactButton(
                        context,
                        theme,
                        action.label,
                        action.primary,
                        action.run
                    ),
                    LinearLayout.LayoutParams(-2, -1).apply {
                        if (index > 0) marginStart = context.aneDp(COMPACT_BUTTON_GAP_DP)
                    }
                )
            }
        }, android.view.ViewGroup.LayoutParams(-2, -1))
    }

    override fun populateConsolePage(page: AnePluginPage, console: View, keyBar: View) {
        console.setPadding(
            host.aneDp(CONSOLE_HORIZONTAL_PADDING_DP),
            host.aneDp(CONSOLE_VERTICAL_PADDING_DP),
            host.aneDp(CONSOLE_HORIZONTAL_PADDING_DP),
            host.aneDp(CONSOLE_VERTICAL_PADDING_DP)
        )
        console.background = AneShapes.rounded(
            theme.surface,
            host.aneDp(CONSOLE_RADIUS_DP).toFloat(),
            theme.outline,
            host.aneDp(1)
        )
        page.content.addView(
            console,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = host.aneDp(CONSOLE_TOP_MARGIN_DP)
            }
        )
        page.content.addView(
            keyBar,
            LinearLayout.LayoutParams(-1, host.aneDp(AneUiTokens.MIN_TOUCH_TARGET_DP)).apply {
                topMargin = host.aneDp(CONSOLE_KEY_BAR_MARGIN_DP)
            }
        )
    }

    override fun attachMediaSwitchButton(
        context: Context,
        container: FrameLayout,
        direction: AneMediaDirection,
        symbol: CharSequence,
        contentDescription: CharSequence,
        onClick: () -> Unit
    ): Button = HostUi.attachMediaSwitchButton(
        context, container, direction, symbol, contentDescription, onClick
    )

    override fun mediaPlaybackControls(
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
    ): AneMediaPlaybackControls = HostUi.mediaPlaybackControls(
        context,
        theme,
        previousSymbol,
        previousDescription,
        playSymbol,
        playDescription,
        nextSymbol,
        nextDescription,
        onPrevious,
        onPlay,
        onNext
    )

    override fun attachMediaArtwork(
        context: Context,
        container: FrameLayout,
        placeholderSymbol: CharSequence
    ): AneMediaArtwork = HostUi.attachMediaArtwork(
        context,
        theme,
        container,
        placeholderSymbol
    )

    override fun configureMediaControls(container: LinearLayout) =
        HostUi.configureMediaControls(container)

    override fun configureMediaRoot(root: View) = HostUi.configureMediaRoot(root)

    override fun mediaStage(context: Context): FrameLayout = HostUi.mediaStage(context)

    override fun attachMediaProgress(
        context: Context,
        container: FrameLayout
    ): ProgressBar = HostUi.attachMediaProgress(context, container)

    override fun updateMediaNavigation(button: Button, enabled: Boolean) =
        HostUi.updateMediaNavigation(button, enabled)

    override fun animateMediaExit(
        view: View,
        direction: AneMediaDirection,
        stageWidth: Int,
        onFinished: () -> Unit
    ) = HostUi.animateMediaExit(view, direction, stageWidth, onFinished)

    override fun animateMediaEnter(
        view: View,
        direction: AneMediaDirection,
        stageWidth: Int,
        onFinished: () -> Unit
    ) = HostUi.animateMediaEnter(view, direction, stageWidth, onFinished)

    override fun badge(
        context: Context,
        label: CharSequence,
        backgroundColor: Int
    ): TextView = AneComponents.badge(context, theme, label, backgroundColor)

    override fun badgeColor(kind: AneBadgeKind, seed: String): Int = when (kind) {
        AneBadgeKind.FOLDER -> if (theme.dark) {
            Color.rgb(196, 139, 45)
        } else {
            Color.rgb(232, 166, 50)
        }
        AneBadgeKind.FILE -> FILE_BADGE_COLORS[
            (seed.lowercase().hashCode() and Int.MAX_VALUE) % FILE_BADGE_COLORS.size
        ]
    }

    override fun emptyState(context: Context, message: CharSequence): TextView =
        AneComponents.emptyState(context, theme, message)

    override fun listRow(
        context: Context,
        leading: View,
        title: CharSequence,
        detail: CharSequence,
        chevron: Boolean,
        onClick: (() -> Unit)?
    ): LinearLayout = AneComponents.listRow(
        context,
        theme,
        leading,
        title,
        detail,
        chevron,
        onClick
    )

    override fun rounded(
        fill: Int,
        radiusPx: Float,
        outline: Int?,
        strokePx: Int
    ): GradientDrawable = AneShapes.rounded(fill, radiusPx, outline, strokePx)

    override fun message(title: String, message: String, actions: List<AneDialogAction>) =
        AneDialog.message(host, title, message, actions, theme)

    override fun choices(
        title: String,
        labels: List<String>,
        cancelLabel: String,
        onSelected: (Int) -> Unit
    ) = AneDialog.choices(host, title, labels, cancelLabel, theme, onSelected)

    override fun input(
        title: String,
        initial: String,
        hint: String,
        inputType: Int,
        confirmLabel: String,
        cancelLabel: String,
        validate: (String) -> String?,
        onCancel: (() -> Unit)?,
        onConfirm: (String) -> Unit
    ) = AneDialog.input(
        activity = host,
        title = title,
        initial = initial,
        hint = hint,
        inputType = inputType,
        confirmLabel = confirmLabel,
        cancelLabel = cancelLabel,
        colors = theme,
        validate = validate,
        onCancel = onCancel,
        onConfirm = onConfirm
    )

    private companion object {
        const val COMPACT_BUTTON_GAP_DP = 7
        const val CONSOLE_HORIZONTAL_PADDING_DP = 10
        const val CONSOLE_VERTICAL_PADDING_DP = 8
        const val CONSOLE_RADIUS_DP = 18
        const val CONSOLE_TOP_MARGIN_DP = 10
        const val CONSOLE_KEY_BAR_MARGIN_DP = 9
        const val BROWSER_BREADCRUMB_HORIZONTAL_PADDING_DP = 10
        const val BROWSER_BREADCRUMB_RADIUS_DP = 14
        const val BROWSER_LIST_BOTTOM_PADDING_DP = 8
        const val BROWSER_BREADCRUMB_HEIGHT_DP = 48
        const val BROWSER_BREADCRUMB_TOP_MARGIN_DP = 7
        const val BROWSER_BREADCRUMB_BOTTOM_MARGIN_DP = 8
        const val BROWSER_DIVIDER_HORIZONTAL_PADDING_DP = 8
        const val BROWSER_ITEM_HORIZONTAL_PADDING_DP = 6
        const val BROWSER_PRIMARY_BUTTON_HEIGHT_DP = 50
        const val BROWSER_PRIMARY_BUTTON_TOP_MARGIN_DP = 10
        const val BROWSER_ROW_HEIGHT_DP = 66
        const val BROWSER_ROW_BOTTOM_MARGIN_DP = 8
        val FILE_BADGE_COLORS = intArrayOf(
            Color.rgb(55, 112, 201),
            Color.rgb(37, 145, 124),
            Color.rgb(156, 91, 195),
            Color.rgb(205, 111, 45),
            Color.rgb(194, 70, 100)
        )
    }
}
