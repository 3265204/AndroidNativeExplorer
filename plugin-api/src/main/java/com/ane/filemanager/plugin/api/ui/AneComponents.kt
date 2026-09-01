package com.ane.filemanager.plugin.api.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

enum class AneTextRole {
    PAGE_TITLE,
    TOP_BAR_TITLE,
    BODY,
    LABEL,
    BREADCRUMB,
    BREADCRUMB_DIVIDER,
    CAPTION,
    BADGE,
    BADGE_COMPACT,
    LIST_TITLE,
    CHEVRON
}

enum class AneTextTone {
    TEXT,
    MUTED,
    PRIMARY,
    DANGER,
    ON_PRIMARY
}

/** Host-owned visual components. Plugins choose semantic roles instead of styling raw Views. */
object AneComponents {
    fun text(
        context: Context,
        theme: AneTheme,
        value: CharSequence = "",
        role: AneTextRole = AneTextRole.BODY,
        tone: AneTextTone = AneTextTone.TEXT
    ): TextView = TextView(context).apply {
        text = value
        textSize = when (role) {
            AneTextRole.PAGE_TITLE -> 22f
            AneTextRole.TOP_BAR_TITLE -> 16f
            AneTextRole.BODY -> 15f
            AneTextRole.LABEL -> 14f
            AneTextRole.BREADCRUMB -> 14f
            AneTextRole.BREADCRUMB_DIVIDER -> 17f
            AneTextRole.CAPTION -> 12f
            AneTextRole.BADGE -> 14f
            AneTextRole.BADGE_COMPACT -> 10f
            AneTextRole.LIST_TITLE -> 16f
            AneTextRole.CHEVRON -> 25f
        }
        setTextColor(theme.color(tone))
        typeface = Typeface.create(
            Typeface.DEFAULT,
            when (role) {
                AneTextRole.PAGE_TITLE,
                AneTextRole.LABEL,
                AneTextRole.BADGE,
                AneTextRole.BADGE_COMPACT,
                AneTextRole.LIST_TITLE -> Typeface.BOLD
                else -> Typeface.NORMAL
            }
        )
    }

    fun primaryButton(
        context: Context,
        theme: AneTheme,
        label: CharSequence,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = AneShapes.rounded(
            theme.primary,
            context.aneDp(AneUiTokens.RADIUS_MEDIUM_DP).toFloat()
        )
        setOnClickListener { onClick() }
    }

    fun textActionButton(
        context: Context,
        theme: AneTheme,
        label: CharSequence,
        contentDescription: CharSequence? = null,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(theme.primary)
        setBackgroundColor(Color.TRANSPARENT)
        this.contentDescription = contentDescription
        setOnClickListener { onClick() }
    }

    fun configureTextEditor(editor: EditText, theme: AneTheme) {
        editor.setTextColor(theme.text)
        editor.setHintTextColor(theme.muted)
        editor.setBackgroundColor(theme.background)
        editor.typeface = Typeface.MONOSPACE
        editor.textSize = AneTypography.editorTextSp(editor.context)
        editor.gravity = Gravity.TOP or Gravity.START
        editor.setPadding(
            editor.context.aneDp(TEXT_EDITOR_HORIZONTAL_PADDING_DP),
            editor.context.aneDp(TEXT_EDITOR_TOP_PADDING_DP),
            editor.context.aneDp(TEXT_EDITOR_HORIZONTAL_PADDING_DP),
            editor.context.aneDp(TEXT_EDITOR_BOTTOM_PADDING_DP)
        )
    }

    fun navigationButton(
        context: Context,
        theme: AneTheme,
        label: CharSequence,
        contentDescription: CharSequence,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        textSize = 28f
        setTextColor(theme.text)
        setBackgroundColor(Color.TRANSPARENT)
        this.contentDescription = contentDescription
        setOnClickListener { onClick() }
    }

    fun topBar(
        context: Context,
        theme: AneTheme,
        navigationLabel: CharSequence,
        navigationDescription: CharSequence,
        title: CharSequence = "",
        onNavigate: () -> Unit
    ): AneTopBar {
        val root = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.aneDp(8), 0, context.aneDp(8), 0)
            setBackgroundColor(theme.surface)
        }
        val navigation = navigationButton(
            context,
            theme,
            navigationLabel,
            navigationDescription,
            onNavigate
        )
        val titleView = text(
            context,
            theme,
            title,
            AneTextRole.TOP_BAR_TITLE
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val trailing = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        root.addView(
            navigation,
            LinearLayout.LayoutParams(
                context.aneDp(AneUiTokens.TOP_BAR_NAVIGATION_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(
            trailing,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return AneTopBar(context, root, titleView, trailing)
    }

    fun sequenceTopBar(
        context: Context,
        theme: AneTheme,
        navigationLabel: CharSequence,
        navigationDescription: CharSequence,
        onNavigate: () -> Unit
    ): AneSequenceTopBar {
        val topBar = topBar(
            context,
            theme,
            navigationLabel,
            navigationDescription,
            onNavigate = onNavigate
        )
        val position = text(
            context,
            theme,
            role = AneTextRole.CAPTION,
            tone = AneTextTone.MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(context.aneDp(10), 0, context.aneDp(4), 0)
        }
        topBar.addTrailing(position)
        return AneSequenceTopBar(topBar, position)
    }

    fun mediaSequenceStage(
        context: Context,
        theme: AneTheme,
        navigationLabel: CharSequence,
        navigationDescription: CharSequence,
        onNavigate: () -> Unit,
        navigation: AneMediaSequenceNavigation,
        stageStyle: AneMediaStageStyle = AneMediaStageStyle.MEDIA,
        onMoved: (AneMediaDirection) -> Unit
    ): AneMediaSequenceStage = AneMediaSequenceStage(
        topBar = sequenceTopBar(
            context,
            theme,
            navigationLabel,
            navigationDescription,
            onNavigate
        ),
        stage = mediaStage(context, theme, stageStyle),
        navigation = navigation,
        attachButton = { container, direction, symbol, description, onClick ->
            attachMediaSwitchButton(
                context,
                container,
                direction,
                symbol,
                description,
                onClick
            )
        },
        updateButton = ::updateMediaNavigation,
        onMoved = onMoved
    )

    fun mediaStage(context: Context): android.widget.FrameLayout =
        android.widget.FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

    fun mediaStage(
        context: Context,
        theme: AneTheme,
        style: AneMediaStageStyle
    ): android.widget.FrameLayout = android.widget.FrameLayout(context).apply {
        setBackgroundColor(
            when (style) {
                AneMediaStageStyle.CONTENT -> theme.background
                AneMediaStageStyle.MEDIA -> Color.BLACK
            }
        )
    }

    fun attachMediaSwitchButton(
        context: Context,
        container: android.widget.FrameLayout,
        direction: AneMediaDirection,
        symbol: CharSequence,
        contentDescription: CharSequence,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = symbol
        textSize = MEDIA_SWITCH_TEXT_SP
        setTextColor(Color.WHITE)
        this.contentDescription = contentDescription
        setPadding(0, 0, 0, context.aneDp(MEDIA_SWITCH_BOTTOM_PADDING_DP))
        background = AneShapes.rounded(
            Color.argb(MEDIA_SWITCH_BACKGROUND_ALPHA, 18, 22, 29),
            context.aneDp(MEDIA_SWITCH_RADIUS_DP).toFloat()
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        container.addView(
            this,
            android.widget.FrameLayout.LayoutParams(
                context.aneDp(MEDIA_SWITCH_WIDTH_DP),
                context.aneDp(MEDIA_SWITCH_HEIGHT_DP),
                when (direction) {
                    AneMediaDirection.PREVIOUS -> Gravity.START or Gravity.CENTER_VERTICAL
                    AneMediaDirection.NEXT -> Gravity.END or Gravity.CENTER_VERTICAL
                }
            ).apply {
                if (direction == AneMediaDirection.PREVIOUS) {
                    leftMargin = context.aneDp(MEDIA_SWITCH_EDGE_MARGIN_DP)
                } else {
                    rightMargin = context.aneDp(MEDIA_SWITCH_EDGE_MARGIN_DP)
                }
            }
        )
    }

    fun updateMediaNavigation(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else MEDIA_NAVIGATION_DISABLED_ALPHA
    }

    fun pageHeader(
        context: Context,
        theme: AneTheme,
        navigationIconRes: Int,
        navigationDescription: CharSequence,
        title: CharSequence,
        summary: CharSequence = "",
        onNavigate: () -> Unit
    ): AnePageHeader {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val navigation = ImageButton(context).apply {
            if (navigationIconRes != 0) setImageResource(navigationIconRes)
            setColorFilter(theme.text)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(
                context.aneDp(13),
                context.aneDp(13),
                context.aneDp(13),
                context.aneDp(13)
            )
            background = AneShapes.rounded(theme.surface, context.aneDp(18).toFloat())
            contentDescription = navigationDescription
            setOnClickListener { onNavigate() }
        }
        val titleView = text(
            context,
            theme,
            title,
            AneTextRole.PAGE_TITLE
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val summaryView = text(
            context,
            theme,
            summary,
            AneTextRole.CAPTION,
            AneTextTone.MUTED
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        root.addView(
            navigation,
            LinearLayout.LayoutParams(
                context.aneDp(AneUiTokens.MIN_TOUCH_TARGET_DP),
                context.aneDp(AneUiTokens.MIN_TOUCH_TARGET_DP)
            )
        )
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.aneDp(14), 0, 0, 0)
            addView(titleView)
            addView(summaryView)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return AnePageHeader(context, root, titleView, summaryView)
    }

    fun compactButton(
        context: Context,
        theme: AneTheme,
        label: CharSequence,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView = text(
        context,
        theme,
        label,
        AneTextRole.LABEL,
        if (primary) AneTextTone.ON_PRIMARY else AneTextTone.TEXT
    ).apply {
        gravity = Gravity.CENTER
        setPadding(
            context.aneDp(13),
            context.aneDp(8),
            context.aneDp(13),
            context.aneDp(8)
        )
        background = AneShapes.rounded(
            if (primary) theme.primary else theme.surface2,
            context.aneDp(AneUiTokens.RADIUS_SMALL_DP).toFloat(),
            if (primary) theme.primary else theme.outline
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun badge(
        context: Context,
        theme: AneTheme,
        label: CharSequence,
        backgroundColor: Int
    ): TextView = text(
        context = context,
        theme = theme,
        value = label,
        role = if (label.length > 3) AneTextRole.BADGE_COMPACT else AneTextRole.BADGE,
        tone = AneTextTone.ON_PRIMARY
    ).apply {
        gravity = Gravity.CENTER
        background = AneShapes.rounded(
            backgroundColor,
            context.aneDp(12).toFloat()
        )
    }

    fun emptyState(
        context: Context,
        theme: AneTheme,
        message: CharSequence
    ): TextView = text(
        context,
        theme,
        message,
        AneTextRole.BODY,
        AneTextTone.MUTED
    ).apply {
        gravity = Gravity.CENTER
        setPadding(
            context.aneDp(12),
            context.aneDp(56),
            context.aneDp(12),
            context.aneDp(56)
        )
    }

    fun listRow(
        context: Context,
        theme: AneTheme,
        leading: android.view.View,
        title: CharSequence,
        detail: CharSequence,
        chevron: Boolean,
        onClick: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            context.aneDp(12),
            context.aneDp(9),
            context.aneDp(12),
            context.aneDp(9)
        )
        background = AneShapes.rounded(
            theme.surface,
            context.aneDp(17).toFloat(),
            theme.outline
        )
        addView(
            leading,
            LinearLayout.LayoutParams(context.aneDp(46), context.aneDp(42))
        )
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.aneDp(13), 0, context.aneDp(8), 0)
            addView(text(context, theme, title, AneTextRole.LIST_TITLE).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(text(
                context,
                theme,
                detail,
                AneTextRole.CAPTION,
                AneTextTone.MUTED
            ))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chevron) addView(text(
            context,
            theme,
            "›",
            AneTextRole.CHEVRON,
            AneTextTone.MUTED
        ).apply { gravity = Gravity.CENTER })
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun AneTheme.color(tone: AneTextTone): Int = when (tone) {
        AneTextTone.TEXT -> text
        AneTextTone.MUTED -> muted
        AneTextTone.PRIMARY -> primary
        AneTextTone.DANGER -> danger
        AneTextTone.ON_PRIMARY -> Color.WHITE
    }

    private const val MEDIA_SWITCH_TEXT_SP = 32f
    private const val MEDIA_SWITCH_BOTTOM_PADDING_DP = 3
    private const val MEDIA_SWITCH_BACKGROUND_ALPHA = 150
    private const val MEDIA_SWITCH_RADIUS_DP = 22
    private const val MEDIA_SWITCH_WIDTH_DP = 54
    private const val MEDIA_SWITCH_HEIGHT_DP = 68
    private const val MEDIA_SWITCH_EDGE_MARGIN_DP = 12
    private const val MEDIA_NAVIGATION_DISABLED_ALPHA = .34f
    private const val TEXT_EDITOR_HORIZONTAL_PADDING_DP = 16
    private const val TEXT_EDITOR_TOP_PADDING_DP = 14
    private const val TEXT_EDITOR_BOTTOM_PADDING_DP = 24
}

class AneTopBar internal constructor(
    private val context: Context,
    val view: LinearLayout,
    val title: TextView,
    private val trailing: LinearLayout
) {
    fun addTrailing(child: android.view.View) {
        trailing.addView(
            child,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun attachTo(parent: LinearLayout) {
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.aneDp(AneUiTokens.TOP_BAR_HEIGHT_DP)
            )
        )
    }
}

class AneSequenceTopBar internal constructor(
    private val topBar: AneTopBar,
    val position: TextView
) {
    val title: TextView
        get() = topBar.title

    fun attachTo(parent: LinearLayout) = topBar.attachTo(parent)
}

class AnePageHeader internal constructor(
    private val context: Context,
    val view: LinearLayout,
    val title: TextView,
    val summary: TextView
) {
    fun attachTo(parent: LinearLayout) {
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.aneDp(AneUiTokens.PAGE_HEADER_HEIGHT_DP)
            )
        )
    }
}
