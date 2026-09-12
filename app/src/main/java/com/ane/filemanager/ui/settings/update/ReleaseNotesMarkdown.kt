package com.ane.filemanager.ui.settings.update

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import com.ane.filemanager.plugin.api.ui.AneTheme

/** A small, safe Markdown renderer for GitHub release notes shown inside the app. */
internal object ReleaseNotesMarkdown {
    fun render(context: Context, source: String, theme: AneTheme): CharSequence {
        val output = SpannableStringBuilder()
        var fencedCode = false
        source.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                fencedCode = !fencedCode
                return@forEach
            }
            if (fencedCode) {
                val start = output.length
                output.append(line.ifEmpty { " " })
                output.setSpan(TypefaceSpan("monospace"), start, output.length, spanFlags)
                output.setSpan(BackgroundColorSpan(theme.surface2), start, output.length, spanFlags)
                output.setSpan(
                    LeadingMarginSpan.Standard(dp(context, CODE_MARGIN_DP)),
                    start,
                    output.length,
                    spanFlags
                )
                output.append('\n')
                return@forEach
            }

            val trimmed = line.trimStart()
            when {
                trimmed.isBlank() -> appendBlankLine(output)
                HORIZONTAL_RULE.matches(trimmed) -> {
                    output.append("────────────\n")
                }
                HEADING.matches(trimmed) -> {
                    val match = HEADING.matchEntire(trimmed)!!
                    val level = match.groupValues[1].length
                    val start = output.length
                    appendInline(output, match.groupValues[2], theme)
                    output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, spanFlags)
                    output.setSpan(
                        RelativeSizeSpan(if (level == 1) 1.28f else if (level == 2) 1.18f else 1.08f),
                        start,
                        output.length,
                        spanFlags
                    )
                    output.append('\n')
                }
                UNORDERED_ITEM.matches(line) -> {
                    val match = UNORDERED_ITEM.matchEntire(line)!!
                    output.append("•  ")
                    appendInline(output, taskMarker(match.groupValues[1]), theme)
                    output.append('\n')
                }
                ORDERED_ITEM.matches(line) -> {
                    val match = ORDERED_ITEM.matchEntire(line)!!
                    output.append(match.groupValues[1]).append(".  ")
                    appendInline(output, match.groupValues[2], theme)
                    output.append('\n')
                }
                BLOCK_QUOTE.matches(line) -> {
                    val match = BLOCK_QUOTE.matchEntire(line)!!
                    val start = output.length
                    output.append("│  ")
                    appendInline(output, match.groupValues[1], theme)
                    output.setSpan(ForegroundColorSpan(theme.muted), start, output.length, spanFlags)
                    output.append('\n')
                }
                else -> {
                    appendInline(output, trimmed, theme)
                    output.append('\n')
                }
            }
        }
        while (output.isNotEmpty() && output.last() == '\n') output.delete(output.length - 1, output.length)
        return output
    }

    private fun appendInline(output: SpannableStringBuilder, value: String, theme: AneTheme) {
        var index = 0
        while (index < value.length) {
            val token = inlineTokenAt(value, index)
            if (token == null) {
                output.append(value[index])
                index++
                continue
            }
            val start = output.length
            when (token.kind) {
                InlineKind.LINK -> {
                    appendInline(output, token.label, theme)
                    if (token.destination.startsWith("https://") || token.destination.startsWith("http://")) {
                        output.setSpan(URLSpan(token.destination), start, output.length, spanFlags)
                        output.setSpan(ForegroundColorSpan(theme.primary), start, output.length, spanFlags)
                    }
                }
                InlineKind.CODE -> {
                    output.append(token.label)
                    output.setSpan(TypefaceSpan("monospace"), start, output.length, spanFlags)
                    output.setSpan(BackgroundColorSpan(theme.surface2), start, output.length, spanFlags)
                }
                InlineKind.BOLD -> {
                    appendInline(output, token.label, theme)
                    output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, spanFlags)
                }
                InlineKind.ITALIC -> {
                    appendInline(output, token.label, theme)
                    output.setSpan(StyleSpan(Typeface.ITALIC), start, output.length, spanFlags)
                }
                InlineKind.STRIKE -> {
                    appendInline(output, token.label, theme)
                    output.setSpan(StrikethroughSpan(), start, output.length, spanFlags)
                }
            }
            index = token.endExclusive
        }
    }

    private fun inlineTokenAt(value: String, start: Int): InlineToken? {
        if (value.startsWith("![", start)) {
            val labelEnd = value.indexOf("](", start + 2)
            val end = if (labelEnd >= 0) value.indexOf(')', labelEnd + 2) else -1
            if (end > labelEnd) return InlineToken(
                InlineKind.LINK,
                value.substring(start + 2, labelEnd),
                value.substring(labelEnd + 2, end),
                end + 1
            )
        }
        if (value[start] == '[') {
            val labelEnd = value.indexOf("](", start + 1)
            val end = if (labelEnd >= 0) value.indexOf(')', labelEnd + 2) else -1
            if (end > labelEnd) return InlineToken(
                InlineKind.LINK,
                value.substring(start + 1, labelEnd),
                value.substring(labelEnd + 2, end),
                end + 1
            )
        }
        val delimiter = when {
            value.startsWith("**", start) -> "**" to InlineKind.BOLD
            value.startsWith("__", start) && underscoreCanOpen(value, start, 2) ->
                "__" to InlineKind.BOLD
            value.startsWith("~~", start) -> "~~" to InlineKind.STRIKE
            value[start] == '`' -> "`" to InlineKind.CODE
            value[start] == '*' -> "*" to InlineKind.ITALIC
            value[start] == '_' && underscoreCanOpen(value, start, 1) ->
                "_" to InlineKind.ITALIC
            else -> return null
        }
        val contentStart = start + delimiter.first.length
        val end = findClosingDelimiter(value, delimiter.first, contentStart)
        if (end <= contentStart) return null
        return InlineToken(
            delimiter.second,
            value.substring(contentStart, end),
            "",
            end + delimiter.first.length
        )
    }

    private fun appendBlankLine(output: SpannableStringBuilder) {
        if (output.isEmpty()) return
        val trailingNewlines = (output.length - 1 downTo 0).takeWhile { output[it] == '\n' }.count()
        if (trailingNewlines < 2) repeat(2 - trailingNewlines) { output.append('\n') }
    }

    private fun taskMarker(value: String): String = when {
        value.startsWith("[x] ", ignoreCase = true) -> "☑  ${value.drop(4)}"
        value.startsWith("[ ] ") -> "☐  ${value.drop(4)}"
        else -> value
    }

    private fun underscoreCanOpen(value: String, start: Int, length: Int): Boolean {
        val before = value.getOrNull(start - 1)
        val after = value.getOrNull(start + length)
        return (before == null || !before.isLetterOrDigit()) && after != null && !after.isWhitespace()
    }

    private fun findClosingDelimiter(value: String, delimiter: String, contentStart: Int): Int {
        var candidate = value.indexOf(delimiter, contentStart)
        while (candidate >= 0) {
            val after = value.getOrNull(candidate + delimiter.length)
            if (!delimiter.startsWith('_') || after == null || !after.isLetterOrDigit()) return candidate
            candidate = value.indexOf(delimiter, candidate + delimiter.length)
        }
        return -1
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + .5f).toInt()

    private data class InlineToken(
        val kind: InlineKind,
        val label: String,
        val destination: String,
        val endExclusive: Int
    )

    private enum class InlineKind { LINK, CODE, BOLD, ITALIC, STRIKE }

    private val HEADING = Regex("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$")
    private val UNORDERED_ITEM = Regex("^\\s*[-+*]\\s+(.+)$")
    private val ORDERED_ITEM = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
    private val BLOCK_QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val HORIZONTAL_RULE = Regex("^ {0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
    private const val CODE_MARGIN_DP = 8
    private const val spanFlags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
}
