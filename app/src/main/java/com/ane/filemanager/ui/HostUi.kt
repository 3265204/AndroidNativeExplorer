package com.ane.filemanager.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.ane.filemanager.plugin.api.ui.AneComponents
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.plugin.api.ui.AneMediaDirection
import com.ane.filemanager.plugin.api.ui.AneMediaPlaybackControls
import com.ane.filemanager.plugin.api.ui.AneMediaArtwork
import com.ane.filemanager.plugin.api.ui.AneSequenceTopBar
import com.ane.filemanager.plugin.api.ui.AneShapes
import com.ane.filemanager.plugin.api.ui.AneTextRole
import com.ane.filemanager.plugin.api.ui.AneTextTone
import com.ane.filemanager.plugin.api.ui.AneTheme
import com.ane.filemanager.plugin.api.ui.AneTopBar
import com.ane.filemanager.plugin.api.ui.applyAneMediaSystemBars
import com.ane.filemanager.plugin.api.ui.applyAneSystemBars
import com.ane.filemanager.plugin.api.ui.applyAneSystemInsets
import com.ane.filemanager.plugin.api.ui.aneDp
import kotlin.math.min

/** Visual policy owned by the host and consumed by built-in and imported plugins. */
internal object HostUi {
    fun theme(context: Context): AneTheme = AneTheme.resolve(context)
    fun theme(context: Context, dark: Boolean): AneTheme = AneTheme.resolve(context, dark)

    fun applySystemBars(activity: Activity, theme: AneTheme) = activity.applyAneSystemBars(theme)
    fun applyMediaSystemBars(activity: Activity, theme: AneTheme) =
        activity.applyAneMediaSystemBars(theme)
    fun applySystemInsets(view: View) = view.applyAneSystemInsets()

    fun topBar(
        context: Context,
        theme: AneTheme,
        navigationLabel: CharSequence,
        navigationDescription: CharSequence,
        title: CharSequence = "",
        onNavigate: () -> Unit
    ): AneTopBar = AneComponents.topBar(
        context,
        theme,
        navigationLabel,
        navigationDescription,
        title,
        onNavigate
    )

    fun textActionButton(
        context: Context,
        theme: AneTheme,
        label: CharSequence,
        onClick: () -> Unit
    ): Button = AneComponents.textActionButton(context, theme, label, onClick = onClick)

    fun message(
        activity: Activity,
        theme: AneTheme,
        title: String,
        message: String,
        actions: List<AneDialogAction>
    ) = AneDialog.message(activity, title, message, actions, theme)

    fun configureTextEditor(editor: EditText, theme: AneTheme) {
        editor.setTextColor(theme.text)
        editor.setHintTextColor(theme.muted)
        editor.setBackgroundColor(theme.background)
        editor.typeface = Typeface.MONOSPACE
        editor.textSize = TEXT_EDITOR_TEXT_SP
        editor.gravity = Gravity.TOP or Gravity.START
        editor.setPadding(
            editor.context.aneDp(TEXT_EDITOR_HORIZONTAL_PADDING_DP),
            editor.context.aneDp(TEXT_EDITOR_TOP_PADDING_DP),
            editor.context.aneDp(TEXT_EDITOR_HORIZONTAL_PADDING_DP),
            editor.context.aneDp(TEXT_EDITOR_BOTTOM_PADDING_DP)
        )
    }

    fun sequenceTopBar(
        context: Context,
        theme: AneTheme,
        navigationLabel: CharSequence,
        navigationDescription: CharSequence,
        onNavigate: () -> Unit
    ): AneSequenceTopBar = AneComponents.sequenceTopBar(
        context,
        theme,
        navigationLabel,
        navigationDescription,
        onNavigate
    )

    fun text(
        context: Context,
        theme: AneTheme,
        value: CharSequence = "",
        role: AneTextRole = AneTextRole.BODY,
        tone: AneTextTone = AneTextTone.TEXT
    ): TextView = AneComponents.text(context, theme, value, role, tone)

    fun attachMediaSwitchButton(
        context: Context,
        container: FrameLayout,
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
            FrameLayout.LayoutParams(
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

    fun mediaPlaybackControls(
        context: Context,
        theme: AneTheme,
        previousSymbol: CharSequence,
        previousDescription: CharSequence,
        playSymbol: CharSequence,
        playDescription: CharSequence,
        nextSymbol: CharSequence,
        nextDescription: CharSequence,
        onPrevious: () -> Unit,
        onPlay: () -> Unit,
        onNext: () -> Unit
    ): AneMediaPlaybackControls {
        val previous = transportButton(
            context, theme, previousSymbol, previousDescription, onPrevious
        )
        val play = Button(context).apply {
            text = playSymbol
            textSize = MEDIA_PLAY_TEXT_SP
            setTextColor(Color.WHITE)
            contentDescription = playDescription
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(theme.primary)
            }
            isEnabled = false
            setOnClickListener { onPlay() }
        }
        val next = transportButton(context, theme, nextSymbol, nextDescription, onNext)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            minimumHeight = context.aneDp(MEDIA_PLAYBACK_ROW_HEIGHT_DP)
            addView(previous, LinearLayout.LayoutParams(
                context.aneDp(MEDIA_TRANSPORT_WIDTH_DP),
                context.aneDp(MEDIA_TRANSPORT_HEIGHT_DP)
            ))
            addView(play, LinearLayout.LayoutParams(
                context.aneDp(MEDIA_PLAY_SIZE_DP),
                context.aneDp(MEDIA_PLAY_SIZE_DP)
            ).apply {
                leftMargin = context.aneDp(MEDIA_PLAY_SIDE_MARGIN_DP)
                rightMargin = context.aneDp(MEDIA_PLAY_SIDE_MARGIN_DP)
            })
            addView(next, LinearLayout.LayoutParams(
                context.aneDp(MEDIA_TRANSPORT_WIDTH_DP),
                context.aneDp(MEDIA_TRANSPORT_HEIGHT_DP)
            ))
        }
        return AneMediaPlaybackControls(row, previous, play, next)
    }

    fun attachMediaArtwork(
        context: Context,
        theme: AneTheme,
        container: FrameLayout,
        placeholderSymbol: CharSequence
    ): AneMediaArtwork {
        val size = min(
            (context.resources.displayMetrics.widthPixels * MEDIA_ARTWORK_WIDTH_RATIO).toInt(),
            context.aneDp(MEDIA_ARTWORK_MAX_SIZE_DP)
        )
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = AneShapes.rounded(
                theme.surface,
                context.aneDp(MEDIA_ARTWORK_RADIUS_DP).toFloat()
            )
            visibility = View.GONE
        }
        val placeholder = TextView(context).apply {
            text = placeholderSymbol
            textSize = MEDIA_ARTWORK_PLACEHOLDER_TEXT_SP
            gravity = Gravity.CENTER
            setTextColor(theme.primary)
            background = AneShapes.rounded(
                theme.surface,
                context.aneDp(MEDIA_ARTWORK_RADIUS_DP).toFloat()
            )
        }
        container.addView(image, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        container.addView(placeholder, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        return AneMediaArtwork(image, placeholder)
    }

    fun configureMediaControls(container: LinearLayout) {
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(
            container.context.aneDp(MEDIA_CONTROLS_HORIZONTAL_PADDING_DP),
            container.context.aneDp(MEDIA_CONTROLS_TOP_PADDING_DP),
            container.context.aneDp(MEDIA_CONTROLS_HORIZONTAL_PADDING_DP),
            container.context.aneDp(MEDIA_CONTROLS_BOTTOM_PADDING_DP)
        )
    }

    fun configureMediaRoot(root: View) {
        root.setBackgroundColor(Color.BLACK)
    }

    fun mediaStage(context: Context) = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
    }

    fun attachMediaProgress(context: Context, container: FrameLayout): ProgressBar =
        ProgressBar(context).also { progress ->
            container.addView(
                progress,
                FrameLayout.LayoutParams(
                    context.aneDp(MEDIA_PROGRESS_SIZE_DP),
                    context.aneDp(MEDIA_PROGRESS_SIZE_DP),
                    Gravity.CENTER
                )
            )
        }

    fun updateMediaNavigation(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else MEDIA_NAVIGATION_DISABLED_ALPHA
    }

    fun animateMediaExit(
        view: View,
        direction: AneMediaDirection,
        stageWidth: Int,
        onFinished: () -> Unit
    ) {
        val distance = stageWidth * MEDIA_EXIT_DISTANCE_RATIO
        val target = if (direction == AneMediaDirection.NEXT) -distance else distance
        view.animate()
            .translationX(target)
            .alpha(0f)
            .setDuration(MEDIA_EXIT_DURATION_MS)
            .withEndAction(onFinished)
            .start()
    }

    fun animateMediaEnter(
        view: View,
        direction: AneMediaDirection,
        stageWidth: Int,
        onFinished: () -> Unit
    ) {
        val distance = stageWidth * MEDIA_ENTER_DISTANCE_RATIO
        view.translationX = if (direction == AneMediaDirection.NEXT) distance else -distance
        view.alpha = 0f
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(MEDIA_ENTER_DURATION_MS)
            .withEndAction(onFinished)
            .start()
    }

    private fun transportButton(
        context: Context,
        theme: AneTheme,
        symbol: CharSequence,
        description: CharSequence,
        onClick: () -> Unit
    ) = Button(context).apply {
        text = symbol
        textSize = MEDIA_TRANSPORT_TEXT_SP
        setTextColor(theme.text)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = description
        setPadding(0, 0, 0, context.aneDp(MEDIA_SWITCH_BOTTOM_PADDING_DP))
        setOnClickListener { onClick() }
    }

    private const val MEDIA_SWITCH_TEXT_SP = 32f
    private const val MEDIA_SWITCH_BOTTOM_PADDING_DP = 3
    private const val MEDIA_SWITCH_BACKGROUND_ALPHA = 150
    private const val MEDIA_SWITCH_RADIUS_DP = 22
    private const val MEDIA_SWITCH_WIDTH_DP = 54
    private const val MEDIA_SWITCH_HEIGHT_DP = 68
    private const val MEDIA_SWITCH_EDGE_MARGIN_DP = 12
    private const val MEDIA_PLAYBACK_ROW_HEIGHT_DP = 68
    private const val MEDIA_TRANSPORT_WIDTH_DP = 72
    private const val MEDIA_TRANSPORT_HEIGHT_DP = 64
    private const val MEDIA_TRANSPORT_TEXT_SP = 34f
    private const val MEDIA_PLAY_TEXT_SP = 23f
    private const val MEDIA_PLAY_SIZE_DP = 64
    private const val MEDIA_PLAY_SIDE_MARGIN_DP = 18
    private const val MEDIA_ARTWORK_WIDTH_RATIO = .54f
    private const val MEDIA_ARTWORK_MAX_SIZE_DP = 270
    private const val MEDIA_ARTWORK_RADIUS_DP = 24
    private const val MEDIA_ARTWORK_PLACEHOLDER_TEXT_SP = 96f
    private const val MEDIA_CONTROLS_HORIZONTAL_PADDING_DP = 22
    private const val MEDIA_CONTROLS_TOP_PADDING_DP = 6
    private const val MEDIA_CONTROLS_BOTTOM_PADDING_DP = 20
    private const val MEDIA_PROGRESS_SIZE_DP = 48
    private const val MEDIA_NAVIGATION_DISABLED_ALPHA = .34f
    private const val MEDIA_EXIT_DISTANCE_RATIO = .16f
    private const val MEDIA_ENTER_DISTANCE_RATIO = .12f
    private const val MEDIA_EXIT_DURATION_MS = 110L
    private const val MEDIA_ENTER_DURATION_MS = 150L
    private const val TEXT_EDITOR_TEXT_SP = 15f
    private const val TEXT_EDITOR_HORIZONTAL_PADDING_DP = 16
    private const val TEXT_EDITOR_TOP_PADDING_DP = 14
    private const val TEXT_EDITOR_BOTTOM_PADDING_DP = 24
}
