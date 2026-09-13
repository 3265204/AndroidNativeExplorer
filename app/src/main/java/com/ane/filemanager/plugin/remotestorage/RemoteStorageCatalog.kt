package com.ane.filemanager.plugin.remotestorage

import com.ane.filemanager.R

internal enum class RemoteStorageCategory(
    val labelResource: Int
) {
    FILE_SYSTEM_LIKE(R.string.remote_storage_category_file_system_like),
    OBJECT_STORAGE(R.string.remote_storage_category_object_storage),
    CLOUD_DRIVE(R.string.remote_storage_category_cloud_drive)
}

/**
 * Product-facing provider taxonomy. Only [WEBDAV] currently has a native transport; all other
 * providers use a user-controlled WebDAV gateway while keeping their real backend identity.
 */
internal enum class RemoteStorageKind(
    val category: RemoteStorageCategory,
    val labelResource: Int,
    val badge: String,
    val nativeTransport: Boolean = false
) {
    WEBDAV(RemoteStorageCategory.FILE_SYSTEM_LIKE, R.string.remote_storage_provider_webdav, "DAV", true),
    SMB(RemoteStorageCategory.FILE_SYSTEM_LIKE, R.string.remote_storage_provider_smb, "SMB"),
    NFS(RemoteStorageCategory.FILE_SYSTEM_LIKE, R.string.remote_storage_provider_nfs, "NFS"),
    SFTP(RemoteStorageCategory.FILE_SYSTEM_LIKE, R.string.remote_storage_provider_sftp, "SFTP"),
    FTP(RemoteStorageCategory.FILE_SYSTEM_LIKE, R.string.remote_storage_provider_ftp, "FTP"),

    S3(RemoteStorageCategory.OBJECT_STORAGE, R.string.remote_storage_provider_s3, "S3"),
    S3_COMPATIBLE(RemoteStorageCategory.OBJECT_STORAGE, R.string.remote_storage_provider_s3_compatible, "S3"),
    AZURE_BLOB(RemoteStorageCategory.OBJECT_STORAGE, R.string.remote_storage_provider_azure_blob, "AZ"),
    GCS(RemoteStorageCategory.OBJECT_STORAGE, R.string.remote_storage_provider_gcs, "GCS"),

    GOOGLE_DRIVE(RemoteStorageCategory.CLOUD_DRIVE, R.string.remote_storage_provider_google_drive, "GD"),
    ONEDRIVE(RemoteStorageCategory.CLOUD_DRIVE, R.string.remote_storage_provider_onedrive, "OD"),
    DROPBOX(RemoteStorageCategory.CLOUD_DRIVE, R.string.remote_storage_provider_dropbox, "DB"),
    BAIDU_NETDISK(RemoteStorageCategory.CLOUD_DRIVE, R.string.remote_storage_provider_baidu_netdisk, "BD"),
    ALIYUN_DRIVE(RemoteStorageCategory.CLOUD_DRIVE, R.string.remote_storage_provider_aliyun_drive, "ALI");

    companion object {
        fun inCategory(category: RemoteStorageCategory): List<RemoteStorageKind> =
            entries.filter { it.category == category }
    }
}
