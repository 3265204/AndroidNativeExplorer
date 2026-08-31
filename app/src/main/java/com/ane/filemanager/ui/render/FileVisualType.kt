package com.ane.filemanager.ui.render

/**
 * A color-independent visual category for files.
 *
 * Keep this intentionally broader than the set of built-in viewers: the category is a
 * presentation hint, so a file should still be recognisable on grayscale and e-ink screens
 * even when ANE delegates opening it to another app.
 */
internal enum class FileVisualType {
    INSTALLER,
    TEXT,
    AUDIO,
    VIDEO,
    IMAGE,
    ARCHIVE,
    PDF,
    CODE,
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    GENERIC;

    companion object {
        fun from(fileName: String, archiveHint: Boolean = false): FileVisualType {
            if (archiveHint) return ARCHIVE
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                in INSTALLER_EXTENSIONS -> INSTALLER
                in TEXT_EXTENSIONS -> TEXT
                in AUDIO_EXTENSIONS -> AUDIO
                in VIDEO_EXTENSIONS -> VIDEO
                in IMAGE_EXTENSIONS -> IMAGE
                in ARCHIVE_EXTENSIONS -> ARCHIVE
                "pdf" -> PDF
                in CODE_EXTENSIONS -> CODE
                in DOCUMENT_EXTENSIONS -> DOCUMENT
                in SPREADSHEET_EXTENSIONS -> SPREADSHEET
                in PRESENTATION_EXTENSIONS -> PRESENTATION
                else -> GENERIC
            }
        }

        private val INSTALLER_EXTENSIONS = setOf("apk", "apks", "xapk", "aab")
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "rtf", "ini", "cfg", "conf"
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma", "amr", "mid", "midi"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "wmv", "flv", "ts"
        )
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "tif", "tiff", "avif"
        )
        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "gz", "gzip", "bz2", "xz", "tar", "tgz", "tbz", "tbz2", "txz", "jar"
        )
        private val CODE_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "json", "yaml", "yml", "html", "htm", "css", "js", "jsx",
            "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bash", "zsh",
            "sql", "gradle", "properties", "toml"
        )
        private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "odt", "pages", "epub", "mobi")
        private val SPREADSHEET_EXTENSIONS = setOf("xls", "xlsx", "ods", "numbers", "csv", "tsv")
        private val PRESENTATION_EXTENSIONS = setOf("ppt", "pptx", "odp", "key")
    }
}
