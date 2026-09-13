package com.ane.filemanager.plugin.remotestorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStorageCatalogTest {
    @Test fun catalogMatchesUnifiedAcceptanceTree() {
        assertEquals(
            listOf("WEBDAV", "SMB", "NFS", "SFTP", "FTP"),
            RemoteStorageKind.inCategory(RemoteStorageCategory.FILE_SYSTEM_LIKE).map(RemoteStorageKind::name)
        )
        assertEquals(
            listOf("S3", "S3_COMPATIBLE", "AZURE_BLOB", "GCS"),
            RemoteStorageKind.inCategory(RemoteStorageCategory.OBJECT_STORAGE).map(RemoteStorageKind::name)
        )
        assertEquals(
            listOf("GOOGLE_DRIVE", "ONEDRIVE", "DROPBOX", "BAIDU_NETDISK", "ALIYUN_DRIVE"),
            RemoteStorageKind.inCategory(RemoteStorageCategory.CLOUD_DRIVE).map(RemoteStorageKind::name)
        )
    }

    @Test fun onlyWebDavClaimsNativeTransport() {
        assertEquals(listOf(RemoteStorageKind.WEBDAV), RemoteStorageKind.entries.filter { it.nativeTransport })
        assertTrue(RemoteStorageKind.entries.filterNot { it.nativeTransport }.isNotEmpty())
    }
}
