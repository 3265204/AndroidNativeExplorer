package com.ane.filemanager.ui.directory

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Uses the system's addition index, never modification time or a recursive storage scan. */
internal class RecentFiles(private val context: Context) {
    @Suppress("DEPRECATION")
    fun list(showHidden: Boolean): List<File> {
        val files = linkedSetOf<File>()
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA),
                "${MediaStore.Files.FileColumns.SIZE} >= 0", null,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
            )?.use { cursor ->
                while (files.size < 200 && cursor.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val path = cursor.getString(0) ?: continue
                    val file = File(path)
                    if (file.isFile && file.canRead() &&
                        (showHidden || path.split('/').none { it.startsWith('.') })) files += file
                }
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            // A removable media database can disappear during the query.
        } catch (_: SecurityException) {
            // Permission can be revoked while the asynchronous query is running.
        }
        return files.toList()
    }
}
