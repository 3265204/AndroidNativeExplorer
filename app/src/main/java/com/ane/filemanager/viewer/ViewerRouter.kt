package com.ane.filemanager.viewer

import android.app.Activity
import android.content.Intent
import com.ane.filemanager.viewer.audio.AudioViewerActivity
import com.ane.filemanager.viewer.image.ImageViewerActivity
import com.ane.filemanager.viewer.text.TextEditorActivity
import com.ane.filemanager.viewer.video.VideoViewerActivity
import java.io.File

internal object ViewerRouter {
    private val textExtensions = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "json5", "xml", "html", "htm",
        "css", "scss", "sass", "less", "yaml", "yml", "toml", "ini", "conf", "config", "properties",
        "kt", "kts", "java", "gradle", "groovy", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue",
        "py", "rb", "php", "swift", "go", "rs", "c", "h", "cpp", "cc", "cxx", "hpp", "cs",
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "graphql", "gql", "dockerfile"
    )

    fun open(activity: Activity, file: File): Boolean {
        val extension = file.extension.lowercase()
        val target = when {
            extension in MediaKind.IMAGE.extensions -> ImageViewerActivity::class.java
            extension in MediaKind.VIDEO.extensions -> VideoViewerActivity::class.java
            extension in MediaKind.AUDIO.extensions -> AudioViewerActivity::class.java
            extension in textExtensions || isProbablyText(file) -> TextEditorActivity::class.java
            else -> return false
        }
        activity.startActivity(Intent(activity, target).putExtra(ViewerContract.EXTRA_PATH, file.absolutePath))
        return true
    }

    private fun isProbablyText(file: File): Boolean {
        if (!file.isFile || file.length() > 8L * 1024L * 1024L) return false
        return try {
            val bytes = ByteArray(minOf(2048L, file.length()).toInt())
            val count = file.inputStream().buffered().use { it.read(bytes) }
            if (count <= 0) true else {
                var suspicious = 0
                for (index in 0 until count) {
                    val value = bytes[index].toInt() and 0xff
                    if (value == 0) return false
                    if (value < 0x09 || value in 0x0e..0x1f) suspicious++
                }
                suspicious * 20 < count
            }
        } catch (_: Exception) {
            false
        }
    }
}
