package com.ane.filemanager.plugin.text

import android.content.Intent
import com.ane.filemanager.plugin.text.editor.TextEditorActivity
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginHost

class TextPluginEntry : AnePlugin {
    override fun supports(file: PluginFile) = file.extension in EXTENSIONS || file.mimeType.startsWith("text/")

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        host.activity.startActivity(Intent(host.activity, TextEditorActivity::class.java)
            .putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.path))
        return true
    }

    private companion object {
        val EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "csv", "tsv", "json", "json5", "xml", "html", "htm",
            "css", "scss", "sass", "less", "yaml", "yml", "toml", "ini", "conf", "config",
            "properties", "kt", "kts", "java", "gradle", "groovy", "js", "mjs", "cjs", "ts",
            "tsx", "jsx", "vue", "py", "rb", "php", "swift", "go", "rs", "c", "h", "cpp", "cc",
            "cxx", "hpp", "cs", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql",
            "graphql", "gql", "dockerfile"
        )
    }
}
