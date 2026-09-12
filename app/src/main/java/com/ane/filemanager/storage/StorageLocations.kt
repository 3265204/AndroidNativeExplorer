package com.ane.filemanager.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

internal data class StorageLocation(val directory: File, val label: String)

internal object StorageLocations {
    fun mounted(context: Context): List<StorageLocation> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.mapNotNull { volume ->
            if (volume.state != Environment.MEDIA_MOUNTED &&
                volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val root = if (Build.VERSION.SDK_INT >= 30) volume.directory else {
                if (volume.isPrimary) Environment.getExternalStorageDirectory()
                else volume.uuid?.let { File("/storage", it) }
            }
            root?.takeIf { it.isDirectory && it.canRead() }?.let {
                StorageLocation(it.canonicalFile, volume.getDescription(context))
            }
        }.distinctBy { it.directory.path }
    }
}
