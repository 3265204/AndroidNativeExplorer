package com.ane.filemanager.plugin.terminal.ui

import android.app.Dialog
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.ane.filemanager.R
import com.ane.filemanager.plugin.terminal.TerminalEmulator
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.PluginTerminalListener
import com.ane.filemanager.plugin.api.PluginTerminalRequest
import com.ane.filemanager.plugin.api.PluginTerminalSession
import java.io.File

internal class TerminalConsoleDialog(
    private val host: PluginHost,
    private val startDirectory: File,
    private val onDismissed: () -> Unit
) {
    private val activity = host.activity
    private val palette = TerminalPalette.resolve(activity)
    private val dialog = Dialog(activity)
    private lateinit var terminalView: TerminalView
    private var session: PluginTerminalSession? = null
    private var terminalStarted = false

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(buildUi())
        dialog.setOnDismissListener {
            session?.close()
            session = null
            onDismissed()
        }
        dialog.window?.applyWindowStyle()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        terminalView.requestFocus()
        terminalView.post {
            if (dialog.isShowing && !terminalStarted) startTerminal()
        }
    }

    fun dismiss() = dialog.dismiss()

    private fun buildUi(): LinearLayout {
        val baseHorizontal = dp(18)
        val baseTop = dp(14)
        val baseBottom = dp(18)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseHorizontal, baseTop, baseHorizontal, baseBottom)
            setBackgroundColor(palette.background)
            setOnApplyWindowInsetsListener { _, insets ->
                val safe = systemInsets(insets)
                setPadding(
                    baseHorizontal + safe.left,
                    baseTop + safe.top,
                    baseHorizontal + safe.right,
                    baseBottom + safe.bottom
                )
                insets
            }
            addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(60)))
            addView(buildInfoCard(), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            })
            addView(buildTerminal(), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(buildKeyBar(), LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })
        }
    }

    private fun buildHeader() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageButton(activity).apply {
            val backIcon = resources.getIdentifier(
                SECONDARY_BACK_DRAWABLE,
                "drawable",
                activity.packageName
            )
            if (backIcon != 0) setImageResource(backIcon)
            setColorFilter(palette.text)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(18).toFloat()
            }
            contentDescription = activity.getString(R.string.terminal_close)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(label(activity.getString(R.string.terminal_title), 22f, palette.text, Typeface.BOLD))
            addView(label(activity.getString(R.string.terminal_summary), 13f, palette.muted).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun buildInfoCard() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(palette.surface, palette.outline, 14f)
        addView(label(
            activity.getString(R.string.terminal_start_directory, startDirectory.absolutePath),
            13f,
            palette.text,
            Typeface.BOLD
        ).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
    }

    private fun buildTerminal(): View {
        val appTextSp = activity.getSharedPreferences("appearance", 0)
            .getInt("textSp", 16)
        val defaultTextSp = (appTextSp - 2).coerceIn(11, 16)
        val textSp = terminalPreferences()
            .getInt(PREFERENCE_FONT_SP, defaultTextSp)
            .coerceIn(10, 22)
        terminalView = TerminalView(activity, palette, textSp).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(palette.surface, palette.outline, 18f)
        }
        return terminalView
    }

    private fun buildKeyBar() = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(keyButton(activity.getString(R.string.terminal_escape)) { terminalView.send("\u001b") })
            addView(keyButton(activity.getString(R.string.terminal_tab)) { terminalView.send("\t") }, keyLayout())
            addView(keyButton("↑") { terminalView.send("\u001b[A") }, keyLayout())
            addView(keyButton("↓") { terminalView.send("\u001b[B") }, keyLayout())
            addView(keyButton("←") { terminalView.send("\u001b[D") }, keyLayout())
            addView(keyButton("→") { terminalView.send("\u001b[C") }, keyLayout())
            addView(keyButton(activity.getString(R.string.terminal_control_c)) {
                terminalView.send(byteArrayOf(3))
            }, keyLayout())
            addView(keyButton(activity.getString(R.string.terminal_control_d)) {
                terminalView.send(byteArrayOf(4))
            }, keyLayout())
            addView(keyButton("A−") { adjustFont(-1) }, keyLayout())
            addView(keyButton("A+") { adjustFont(1) }, keyLayout())
            addView(keyButton(
                activity.getString(R.string.terminal_paste),
                primary = true,
                action = ::paste
            ), keyLayout())
        }, ViewGroup.LayoutParams(-2, -1))
    }

    private fun keyButton(text: String, primary: Boolean = false, action: () -> Unit) =
        TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (primary) Color.WHITE else palette.text)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = rounded(
                if (primary) palette.primary else palette.surface2,
                if (primary) palette.primary else palette.outline,
                11f
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun startTerminal() {
        terminalStarted = true
        terminalView.attach(
            writer = { bytes -> session?.write(bytes) },
            resize = { rows, columns -> session?.resize(rows, columns) }
        )
        val request = PluginTerminalRequest(
            executable = "/system/bin/sh",
            arguments = listOf("-i"),
            workingDirectory = startDirectory.absolutePath,
            environment = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor"),
            rows = terminalView.currentRows,
            columns = terminalView.currentColumns
        )
        session = runCatching {
            host.openTerminal(request, object : PluginTerminalListener {
                override fun onOutput(bytes: ByteArray) = terminalView.feed(bytes)

                override fun onExit(exitCode: Int?, signal: Int?) {
                    terminalView.feed(
                        "\r\n${activity.getString(R.string.terminal_shell_closed)}\r\n".toByteArray()
                    )
                }

                override fun onError(message: String) {
                    terminalView.feed(
                        "\r\n${activity.getString(R.string.terminal_unavailable)}: $message\r\n".toByteArray()
                    )
                }
            })
        }.getOrElse {
            terminalView.feed(
                (
                    "\r\n${activity.getString(R.string.terminal_unavailable)}: " +
                        "${it.message.orEmpty()}\r\n"
                ).toByteArray()
            )
            null
        }
        if (session == null) {
            terminalView.feed(
                "\r\n${activity.getString(R.string.terminal_api_upgrade_required)}\r\n".toByteArray()
            )
        } else {
            session?.resize(terminalView.currentRows, terminalView.currentColumns)
        }
    }

    private fun adjustFont(delta: Int) {
        terminalView.setTextSizeSp(terminalView.currentTextSizeSp + delta)
        terminalPreferences().edit()
            .putInt(PREFERENCE_FONT_SP, terminalView.currentTextSizeSp)
            .apply()
        terminalView.requestFocus()
    }

    private fun terminalPreferences() =
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun paste() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf {
            it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        }?.getItemAt(0)
        item?.coerceToText(activity)?.toString()?.let(terminalView::send)
        terminalView.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun Window.applyWindowStyle() {
        setBackgroundDrawable(ColorDrawable(palette.background))
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        statusBarColor = palette.background
        navigationBarColor = palette.background
        if (!palette.dark) {
            decorView.systemUiVisibility = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                decorView.systemUiVisibility = decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    private fun systemInsets(insets: WindowInsets): SafeInsets = if (Build.VERSION.SDK_INT >= 30) {
        val value = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        SafeInsets(value.left, value.top, value.right, value.bottom)
    } else {
        @Suppress("DEPRECATION")
        SafeInsets(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom
        )
    }

    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            setTypeface(typeface, style)
        }

    private fun rounded(fill: Int, outline: Int, radiusDp: Float) =
        TerminalPalette.rounded(fill, outline, dp(radiusDp).toFloat(), dp(1))

    private fun keyLayout() = LinearLayout.LayoutParams(-2, -1).apply { marginStart = dp(7) }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * activity.resources.displayMetrics.density + .5f).toInt()

    private data class SafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private companion object {
        const val SECONDARY_BACK_DRAWABLE = "ic_secondary_back"
        const val PREFERENCES_NAME = "ane-terminal"
        const val PREFERENCE_FONT_SP = "font-sp"
    }
}
