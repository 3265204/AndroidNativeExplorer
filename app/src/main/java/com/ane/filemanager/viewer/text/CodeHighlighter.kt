package com.ane.filemanager.viewer.text

import android.graphics.Color
import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

internal object CodeHighlighter {
    private const val MAX_HIGHLIGHT_CHARS = 80_000
    private const val MAX_SPANS = 1_200

    private val codeExtensions = setOf(
        "kt", "kts", "java", "gradle", "groovy", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue",
        "py", "rb", "php", "swift", "go", "rs", "c", "h", "cpp", "cc", "cxx", "hpp", "cs",
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "json", "json5", "xml", "html",
        "htm", "css", "scss", "sass", "less", "yaml", "yml", "toml", "ini", "graphql", "gql"
    )

    fun isCode(extension: String) = extension.lowercase() in codeExtensions

    fun prefersNoWrap(extension: String): Boolean {
        val ext = extension.lowercase()
        return isCode(ext) && ext !in setOf("xml", "html", "htm", "vue")
    }

    data class HighlightRange(val start: Int, val end: Int, val color: Int)

    fun compute(source: String, extension: String, dark: Boolean, sourceOffset: Int = 0): List<HighlightRange> {
        if (!isCode(extension) && extension.lowercase() !in setOf("md", "markdown")) return emptyList()
        val end = minOf(source.length, MAX_HIGHLIGHT_CHARS)
        if (end == 0) return emptyList()
        val value = source.substring(0, end)
        val ranges = ArrayList<HighlightRange>()
        val occupied = BooleanArray(end)
        val comment = if (dark) Color.rgb(106, 153, 85) else Color.rgb(74, 128, 61)
        val string = if (dark) Color.rgb(206, 145, 120) else Color.rgb(163, 71, 40)
        val keyword = if (dark) Color.rgb(197, 134, 192) else Color.rgb(126, 34, 206)
        val number = if (dark) Color.rgb(181, 206, 168) else Color.rgb(3, 105, 161)
        val symbol = if (dark) Color.rgb(86, 156, 214) else Color.rgb(29, 78, 216)
        val ext = extension.lowercase()

        when {
            ext in setOf("py", "rb", "sh", "bash", "zsh", "fish", "ps1", "yaml", "yml", "toml") ->
                color(value, occupied, ranges, Pattern.compile("(?m)#.*$"), comment, true, sourceOffset)
            ext == "sql" -> color(value, occupied, ranges, Pattern.compile("(?m)--.*$"), comment, true, sourceOffset)
            ext !in setOf("json", "json5", "xml", "html", "htm", "md", "markdown") -> {
                color(value, occupied, ranges, Pattern.compile("(?m)//.*$"), comment, true, sourceOffset)
                color(value, occupied, ranges, Pattern.compile("/\\*[\\s\\S]*?\\*/"), comment, true, sourceOffset)
            }
        }
        color(value, occupied, ranges,
            Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`"),
            string, true, sourceOffset)

        if (ext in setOf("xml", "html", "htm", "vue")) {
            color(value, occupied, ranges, Pattern.compile("</?[A-Za-z][A-Za-z0-9:_-]*"), symbol, false, sourceOffset)
        } else if (ext in setOf("md", "markdown")) {
            color(value, occupied, ranges, Pattern.compile("(?m)^#{1,6}\\s+.*$"), symbol, false, sourceOffset)
            color(value, occupied, ranges, Pattern.compile("`[^`]+`"), string, false, sourceOffset)
        } else {
            val words = keywordsFor(ext)
            if (words.isNotEmpty()) {
                color(value, occupied, ranges,
                    Pattern.compile("\\b(?:${words.joinToString("|") { Pattern.quote(it) }})\\b", Pattern.CASE_INSENSITIVE),
                    keyword, false, sourceOffset)
            }
            color(value, occupied, ranges,
                Pattern.compile("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b"), number, false, sourceOffset)
        }
        return ranges
    }

    fun apply(editable: Editable, ranges: List<HighlightRange>) {
        editable.getSpans(0, editable.length, SyntaxColorSpan::class.java).forEach(editable::removeSpan)
        ranges.forEach { range ->
            if (range.start >= 0 && range.end <= editable.length && range.start < range.end) {
                editable.setSpan(SyntaxColorSpan(range.color), range.start, range.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun color(
        source: String,
        occupied: BooleanArray,
        ranges: MutableList<HighlightRange>,
        pattern: Pattern,
        color: Int,
        reserve: Boolean,
        sourceOffset: Int
    ) {
        val matcher = pattern.matcher(source)
        while (matcher.find() && ranges.size < MAX_SPANS) {
            val start = matcher.start()
            val end = matcher.end()
            if (start >= end || (start until end).any { occupied[it] }) continue
            ranges += HighlightRange(start + sourceOffset, end + sourceOffset, color)
            if (reserve) for (index in start until end) occupied[index] = true
        }
    }

    private fun keywordsFor(extension: String): Set<String> = when (extension) {
        "py" -> setOf("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "not", "or", "pass", "raise", "return", "True", "False", "try", "while", "with", "yield")
        "sql" -> setOf("select", "from", "where", "join", "inner", "left", "right", "on", "insert", "update", "delete", "create", "table", "alter", "drop", "group", "order", "by", "having", "limit", "as", "and", "or", "not", "null", "into", "values", "set")
        "sh", "bash", "zsh", "fish" -> setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "in", "local", "export", "return")
        "json", "json5" -> setOf("true", "false", "null")
        else -> setOf("abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "data", "default", "do", "else", "enum", "extends", "false", "final", "finally", "for", "fun", "function", "if", "implements", "import", "in", "interface", "internal", "is", "let", "new", "null", "object", "open", "override", "package", "private", "protected", "public", "return", "sealed", "static", "struct", "super", "switch", "this", "throw", "throws", "true", "try", "typealias", "typeof", "val", "var", "void", "when", "while")
    }
}

private class SyntaxColorSpan(color: Int) : ForegroundColorSpan(color)
