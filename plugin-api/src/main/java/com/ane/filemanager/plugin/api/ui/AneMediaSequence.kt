package com.ane.filemanager.plugin.api.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Media navigation expressed without exposing the host's sequence implementation. */
class AneMediaSequenceNavigation(
    val currentTitle: () -> CharSequence,
    val positionLabel: () -> CharSequence,
    val hasPrevious: () -> Boolean,
    val hasNext: () -> Boolean,
    val moveBy: (delta: Int) -> Boolean
)

enum class AneMediaStageStyle {
    CONTENT,
    MEDIA
}

/**
 * Shared top bar, stage and previous/next orchestration for sequence-based media screens.
 * The media decoder/player and transition details remain plugin-owned.
 */
class AneMediaSequenceStage internal constructor(
    private val topBar: AneSequenceTopBar,
    val stage: FrameLayout,
    private val navigation: AneMediaSequenceNavigation,
    private val attachButton: (
        FrameLayout,
        AneMediaDirection,
        CharSequence,
        CharSequence,
        () -> Unit
    ) -> Button,
    private val updateButton: (Button, Boolean) -> Unit,
    private val onMoved: (AneMediaDirection) -> Unit
) {
    private var previousButton: Button? = null
    private var nextButton: Button? = null

    fun attachTo(parent: LinearLayout) {
        topBar.attachTo(parent)
        parent.addView(
            stage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
    }

    fun attachSwitchButtons(
        previousSymbol: CharSequence,
        previousDescription: CharSequence,
        nextSymbol: CharSequence,
        nextDescription: CharSequence
    ) {
        val previous = attachButton(
            stage,
            AneMediaDirection.PREVIOUS,
            previousSymbol,
            previousDescription
        ) { moveBy(-1) }
        val next = attachButton(
            stage,
            AneMediaDirection.NEXT,
            nextSymbol,
            nextDescription
        ) { moveBy(1) }
        bindNavigationButtons(previous, next)
    }

    /** Binds navigation supplied by plugin-specific controls, such as an audio transport row. */
    fun bindNavigationButtons(previous: Button, next: Button) {
        previousButton = previous
        nextButton = next
        refreshNavigation()
    }

    fun refresh() {
        topBar.title.text = navigation.currentTitle()
        topBar.position.text = navigation.positionLabel()
        refreshNavigation()
    }

    fun moveBy(delta: Int): Boolean {
        if (delta == 0 || !navigation.moveBy(delta)) return false
        refresh()
        onMoved(
            if (delta < 0) AneMediaDirection.PREVIOUS else AneMediaDirection.NEXT
        )
        return true
    }

    private fun refreshNavigation() {
        previousButton?.let { updateButton(it, navigation.hasPrevious()) }
        nextButton?.let { updateButton(it, navigation.hasNext()) }
    }
}

/** Builds the shared sequence component from the host UI capability without extending its ABI. */
fun AnePluginUi.mediaSequenceStage(
    context: Context,
    navigationLabel: CharSequence,
    navigationDescription: CharSequence,
    onNavigate: () -> Unit,
    navigation: AneMediaSequenceNavigation,
    stageStyle: AneMediaStageStyle = AneMediaStageStyle.MEDIA,
    onMoved: (AneMediaDirection) -> Unit
): AneMediaSequenceStage = AneMediaSequenceStage(
    topBar = AneComponents.sequenceTopBar(
        context,
        theme,
        navigationLabel,
        navigationDescription,
        onNavigate
    ),
    stage = when (stageStyle) {
        AneMediaStageStyle.CONTENT -> AneComponents.mediaStage(context, theme, stageStyle)
        AneMediaStageStyle.MEDIA -> mediaStage(context)
    },
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
