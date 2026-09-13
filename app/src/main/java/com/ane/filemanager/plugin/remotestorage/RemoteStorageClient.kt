package com.ane.filemanager.plugin.remotestorage

import java.io.File
import java.io.OutputStream

internal interface RemoteStorageClient {
    fun list(path: String): List<WebDavEntry>
    fun createDirectory(path: String)
    fun delete(path: String)
    fun move(source: String, destination: String)
    fun download(path: String, output: OutputStream)
    fun upload(localFile: File, remotePath: String)
}

internal object RemoteStorageClientFactory {
    fun create(profile: RemoteStorageProfile, password: CharArray?): RemoteStorageClient =
        WebDavClient(profile, password)
}
