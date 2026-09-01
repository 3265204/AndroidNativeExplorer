package com.ane.filemanager.plugin.text

import com.ane.filemanager.plugin.api.AneIntentPluginEntry
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.text.editor.TextEditorActivity

class TextPluginEntry : AneIntentPluginEntry(TextEditorActivity::class.java, TextPluginFiles::supports)

internal object TextPluginFiles {
    private val extensions = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "json5", "xml", "html", "htm",
        "css", "scss", "sass", "less", "yaml", "yml", "toml", "ini", "conf", "config",
        "properties", "kt", "kts", "java", "gradle", "groovy", "js", "mjs", "cjs", "ts",
        "tsx", "jsx", "vue", "py", "rb", "php", "swift", "go", "rs", "c", "h", "cpp", "cc",
        "cxx", "hpp", "cs", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql",
        "graphql", "gql", "dockerfile"
    )

    fun supports(file: PluginFile): Boolean =
        file.extension.lowercase() in extensions || file.mimeType.startsWith("text/")
}
