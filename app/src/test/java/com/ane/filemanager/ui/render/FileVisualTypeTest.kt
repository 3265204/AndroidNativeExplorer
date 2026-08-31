package com.ane.filemanager.ui.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FileVisualTypeTest {
    @Test
    fun recognisesTypesThatNeedDistinctMonochromeIcons() {
        assertEquals(FileVisualType.INSTALLER, FileVisualType.from("release.APK"))
        assertEquals(FileVisualType.TEXT, FileVisualType.from("notes.txt"))
        assertEquals(FileVisualType.AUDIO, FileVisualType.from("song.flac"))
        assertEquals(FileVisualType.VIDEO, FileVisualType.from("clip.webm"))
        assertEquals(FileVisualType.IMAGE, FileVisualType.from("photo.heic"))
        assertEquals(FileVisualType.PDF, FileVisualType.from("manual.pdf"))
        assertEquals(FileVisualType.SPREADSHEET, FileVisualType.from("budget.xlsx"))
    }

    @Test
    fun archivePluginHintWinsForUnusualExtensions() {
        assertEquals(FileVisualType.ARCHIVE, FileVisualType.from("backup.part01", archiveHint = true))
    }

    @Test
    fun extensionlessAndHiddenFilesRemainGeneric() {
        assertEquals(FileVisualType.GENERIC, FileVisualType.from("README"))
        assertEquals(FileVisualType.GENERIC, FileVisualType.from(".nomedia"))
    }
}
