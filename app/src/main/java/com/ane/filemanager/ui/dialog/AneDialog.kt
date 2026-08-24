package com.ane.filemanager.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ane.filemanager.ui.theme.AppThemePalette
import kotlin.math.min

internal data class AneDialogAction(
    val label: String,
    val primary: Boolean = false,
    val destructive: Boolean = false,
    val run: () -> Unit = {}
)

/** App-owned modal surface with semantic theme colors and no platform window transitions. */
internal object AneDialog {
    fun input(
        activity: Activity,
        title: String,
        initial: String = "",
        hint: String = "",
        inputType: Int,
        confirmLabel: String,
        cancelLabel: String,
        colors: AppThemePalette = AppThemePalette.resolve(activity, hostDark(activity)),
        validate: (String) -> String? = { null },
        onCancel: (() -> Unit)? = null,
        onConfirm: (String) -> Unit
    ) {
        val frame = frame(activity, title, colors)
        val input = editText(activity, frame.palette).apply {
            setText(initial)
            this.hint = hint
            this.inputType = inputType
            isSingleLine = true
            setSelectAllOnFocus(initial.isNotEmpty())
        }
        val error = text(activity, "", 12f, frame.palette.danger).apply {
            visibility = View.GONE
            setPadding(dp(activity, 3), dp(activity, 6), dp(activity, 3), 0)
        }
        frame.body.addView(input, LinearLayout.LayoutParams(-1, -2))
        frame.body.addView(error, LinearLayout.LayoutParams(-1, -2))
        addButton(frame, AneDialogAction(cancelLabel)) { frame.dialog.cancel() }
        addButton(frame, AneDialogAction(confirmLabel, primary = true)) {
            val value = input.text.toString().trim()
            val problem = validate(value)
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
            } else {
                frame.dialog.dismiss()
                onConfirm(value)
            }
        }
        if (onCancel != null) frame.dialog.setOnCancelListener { onCancel() }
        show(frame, softInput = true)
        input.requestFocus()
    }

    fun message(
        activity: Activity,
        title: String,
        message: String,
        actions: List<AneDialogAction>,
        colors: AppThemePalette = AppThemePalette.resolve(activity, hostDark(activity))
    ) {
        val frame = frame(activity, title, colors)
        frame.body.addView(text(activity, message, 14.5f, frame.palette.text).apply {
            setLineSpacing(0f, 1.18f)
        }, LinearLayout.LayoutParams(-1, -2))
        actions.forEach { action ->
            addButton(frame, action) {
                frame.dialog.dismiss()
                action.run()
            }
        }
        show(frame)
    }

    fun choices(
        activity: Activity,
        title: String,
        labels: List<String>,
        cancelLabel: String,
        colors: AppThemePalette = AppThemePalette.resolve(activity, hostDark(activity)),
        onSelected: (Int) -> Unit
    ) {
        val frame = frame(activity, title, colors)
        val choices = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.forEachIndexed { index, label ->
            choices.addView(choiceRow(activity, label, frame.palette) {
                frame.dialog.dismiss()
                onSelected(index)
            })
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(choices, FrameLayout.LayoutParams(-1, -2))
        }
        frame.body.addView(scroll, LinearLayout.LayoutParams(
            -1, min(dp(activity, 360), dp(activity, 52) * labels.size.coerceAtLeast(1))
        ))
        addButton(frame, AneDialogAction(cancelLabel)) { frame.dialog.cancel() }
        show(frame)
    }

    fun <T> liveSearch(
        activity: Activity,
        title: String,
        hint: String,
        startTypingText: String,
        noResultsText: String,
        resultCount: (Int) -> String,
        cancelLabel: String,
        items: List<T>,
        label: (T) -> String,
        filter: (List<T>, String) -> List<T>,
        colors: AppThemePalette = AppThemePalette.resolve(activity, hostDark(activity)),
        onSelected: (T) -> Unit
    ) {
        val frame = frame(activity, title, colors)
        val input = editText(activity, frame.palette).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val summary = text(activity, startTypingText, 12.5f, frame.palette.muted).apply {
            setPadding(dp(activity, 3), dp(activity, 10), dp(activity, 3), dp(activity, 7))
        }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(results, FrameLayout.LayoutParams(-1, -2))
        }
        frame.body.addView(input, LinearLayout.LayoutParams(-1, -2))
        frame.body.addView(summary, LinearLayout.LayoutParams(-1, -2))
        frame.body.addView(scroll, LinearLayout.LayoutParams(-1, dp(activity, 330)))
        addButton(frame, AneDialogAction(cancelLabel)) { frame.dialog.cancel() }

        var searchGeneration = 0
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                val generation = ++searchGeneration
                input.postDelayed({
                    if (!frame.dialog.isShowing || generation != searchGeneration) return@postDelayed
                    val query = value?.toString()?.trim().orEmpty()
                    results.removeAllViews()
                    if (query.isEmpty()) {
                        summary.text = startTypingText
                        return@postDelayed
                    }
                    val matches = filter(items, query)
                    summary.text = if (matches.isEmpty()) noResultsText else resultCount(matches.size)
                    matches.take(MAX_VISIBLE_RESULTS).forEach { item ->
                        results.addView(choiceRow(activity, label(item), frame.palette) {
                            frame.dialog.dismiss()
                            onSelected(item)
                        })
                    }
                }, SEARCH_DEBOUNCE_MS)
            }

            override fun afterTextChanged(value: Editable?) = Unit
        })
        show(frame, softInput = true)
        input.requestFocus()
    }

    private fun frame(activity: Activity, title: String, palette: AppThemePalette): Frame {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 14))
            background = rounded(palette.surface, palette.outline, dp(activity, 22).toFloat())
            elevation = dp(activity, 14).toFloat()
        }
        root.addView(text(activity, title, 19f, palette.text, Typeface.BOLD).apply {
            setPadding(0, 0, 0, dp(activity, 14))
        }, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 16), 0, 0)
        }
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        return Frame(activity, dialog, buttons, body, palette)
    }

    private fun addButton(frame: Frame, action: AneDialogAction, overrideRun: () -> Unit) {
        val foreground = when {
            action.primary -> Color.WHITE
            action.destructive -> frame.palette.danger
            else -> frame.palette.text
        }
        val background = if (action.primary) frame.palette.primary else frame.palette.surface2
        val button = text(frame.activity, action.label, 14f, foreground, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            this.background = rounded(background, frame.palette.outline, dp(context, 11).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { overrideRun() }
        }
        frame.buttons.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(button.context, 8)
        })
    }

    private fun choiceRow(activity: Activity, label: String, palette: AppThemePalette, action: () -> Unit): TextView =
        text(activity, label, 15f, palette.text).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13))
            background = rounded(palette.surface2, palette.outline, dp(activity, 11).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 7) }
        }

    private fun editText(activity: Activity, palette: AppThemePalette) = EditText(activity).apply {
        setTextColor(palette.text)
        setHintTextColor(palette.muted)
        textSize = 15f
        setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
        background = rounded(palette.surface2, palette.primary, dp(activity, 12).toFloat())
    }

    private fun text(
        activity: Activity,
        value: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, style)
    }

    private fun show(frame: Frame, softInput: Boolean = false) {
        frame.dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .46f }
            if (softInput) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        frame.dialog.show()
        val activity = frame.activity
        val available = activity.resources.displayMetrics.widthPixels - dp(activity, 32)
        frame.dialog.window?.setLayout(min(available, dp(activity, 560)), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        setStroke(1, stroke)
    }

    private fun hostDark(activity: Activity): Boolean =
        activity.getSharedPreferences("appearance", 0).getBoolean("dark", false)

    private fun dp(activity: android.content.Context, value: Int): Int =
        (value * activity.resources.displayMetrics.density + .5f).toInt()

    private data class Frame(
        val activity: Activity,
        val dialog: Dialog,
        val buttons: LinearLayout,
        val body: LinearLayout,
        val palette: AppThemePalette
    )

    private const val SEARCH_DEBOUNCE_MS = 70L
    private const val MAX_VISIBLE_RESULTS = 100
}
