package com.ane.filemanager.plugin.remotestorage

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.PluginHost

internal data class RemoteStorageStrings(
    val title: String,
    val open: String,
    val manage: String,
    val upload: String,
    val addConnection: String,
    val chooseCategory: String,
    val chooseProvider: String,
    val noConnections: String,
    val connectionActions: String,
    val browse: String,
    val edit: String,
    val remove: String,
    val cancel: String,
    val confirm: String,
    val close: String,
    val connectionName: String,
    val connectionNameHint: String,
    val serverUrl: String,
    val serverUrlHint: String,
    val gatewayUrl: String,
    val gatewayUrlHint: String,
    val gatewayRequiredTitle: String,
    val gatewayRequired: (String) -> String,
    val continueLabel: String,
    val nativeTransport: String,
    val gatewayTransport: String,
    val username: String,
    val usernameHint: String,
    val passwordTitle: (String) -> String,
    val invalidUrl: String,
    val duplicateName: String,
    val loading: String,
    val parent: String,
    val folderBadge: String,
    val fileBadge: String,
    val newFolder: String,
    val folderName: String,
    val itemActions: String,
    val download: String,
    val rename: String,
    val delete: String,
    val deleteTitle: String,
    val deleteMessage: (String) -> String,
    val downloading: String,
    val downloaded: (String) -> String,
    val creatingFolder: String,
    val renaming: String,
    val deleting: String,
    val remoteDirectory: String,
    val remoteDirectoryHint: String,
    val uploading: String,
    val uploaded: (Int) -> String,
    val filesOnly: String,
    val emptyFolder: String,
    val entries: (String, Int) -> String,
    val anonymous: String,
    val irreversible: String,
    val networkError: String,
    val webDavError: (WebDavException) -> String,
    val categoryLabel: (RemoteStorageCategory) -> String,
    val providerLabel: (RemoteStorageKind) -> String
) {
    companion object {
        fun resolve(host: PluginHost): RemoteStorageStrings {
            val context = localizedContext(host.activity, host.systemLocaleTags)
            fun text(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)
            return RemoteStorageStrings(
                title = text(R.string.remote_storage_title),
                open = text(R.string.remote_storage_open),
                manage = text(R.string.remote_storage_manage),
                upload = text(R.string.remote_storage_upload),
                addConnection = text(R.string.remote_storage_add_connection),
                chooseCategory = text(R.string.remote_storage_choose_category),
                chooseProvider = text(R.string.remote_storage_choose_provider),
                noConnections = text(R.string.remote_storage_no_connections),
                connectionActions = text(R.string.remote_storage_connection_actions),
                browse = text(R.string.remote_storage_browse),
                edit = text(R.string.remote_storage_edit),
                remove = text(R.string.remote_storage_remove),
                cancel = text(R.string.remote_storage_cancel),
                confirm = text(R.string.remote_storage_confirm),
                close = text(R.string.remote_storage_close),
                connectionName = text(R.string.remote_storage_connection_name),
                connectionNameHint = text(R.string.remote_storage_connection_name_hint),
                serverUrl = text(R.string.remote_storage_server_url),
                serverUrlHint = text(R.string.remote_storage_server_url_hint),
                gatewayUrl = text(R.string.remote_storage_gateway_url),
                gatewayUrlHint = text(R.string.remote_storage_gateway_url_hint),
                gatewayRequiredTitle = text(R.string.remote_storage_gateway_required_title),
                gatewayRequired = { text(R.string.remote_storage_gateway_required, it) },
                continueLabel = text(R.string.remote_storage_continue),
                nativeTransport = text(R.string.remote_storage_native_transport),
                gatewayTransport = text(R.string.remote_storage_gateway_transport),
                username = text(R.string.remote_storage_username),
                usernameHint = text(R.string.remote_storage_username_hint),
                passwordTitle = { text(R.string.remote_storage_password_title, it) },
                invalidUrl = text(R.string.remote_storage_invalid_url),
                duplicateName = text(R.string.remote_storage_duplicate_name),
                loading = text(R.string.remote_storage_loading),
                parent = text(R.string.remote_storage_parent),
                folderBadge = text(R.string.remote_storage_folder_badge),
                fileBadge = text(R.string.remote_storage_file_badge),
                newFolder = text(R.string.remote_storage_new_folder),
                folderName = text(R.string.remote_storage_folder_name),
                itemActions = text(R.string.remote_storage_item_actions),
                download = text(R.string.remote_storage_download),
                rename = text(R.string.remote_storage_rename),
                delete = text(R.string.remote_storage_delete),
                deleteTitle = text(R.string.remote_storage_delete_title),
                deleteMessage = { text(R.string.remote_storage_delete_message, it) },
                downloading = text(R.string.remote_storage_downloading),
                downloaded = { text(R.string.remote_storage_downloaded, it) },
                creatingFolder = text(R.string.remote_storage_creating_folder),
                renaming = text(R.string.remote_storage_renaming),
                deleting = text(R.string.remote_storage_deleting),
                remoteDirectory = text(R.string.remote_storage_remote_directory),
                remoteDirectoryHint = text(R.string.remote_storage_remote_directory_hint),
                uploading = text(R.string.remote_storage_uploading),
                uploaded = { count -> context.resources.getQuantityString(
                    R.plurals.remote_storage_uploaded, count, count
                ) },
                filesOnly = text(R.string.remote_storage_files_only),
                emptyFolder = text(R.string.remote_storage_empty_folder),
                entries = { path, count -> context.resources.getQuantityString(
                    R.plurals.remote_storage_entries, count, path, count
                ) },
                anonymous = text(R.string.remote_storage_anonymous),
                irreversible = text(R.string.remote_storage_irreversible),
                networkError = text(R.string.remote_storage_error_network),
                webDavError = { error ->
                    when (error.failure) {
                        WebDavFailure.AUTHENTICATION -> text(R.string.remote_storage_error_authentication)
                        WebDavFailure.FORBIDDEN -> text(R.string.remote_storage_error_forbidden)
                        WebDavFailure.NOT_FOUND -> text(R.string.remote_storage_error_not_found)
                        WebDavFailure.UNSUPPORTED_OR_EXISTS -> text(R.string.remote_storage_error_unsupported_or_exists)
                        WebDavFailure.PARENT_MISSING -> text(R.string.remote_storage_error_parent_missing)
                        WebDavFailure.ALREADY_EXISTS -> text(R.string.remote_storage_error_already_exists)
                        WebDavFailure.LOCKED -> text(R.string.remote_storage_error_locked)
                        WebDavFailure.STORAGE_FULL -> text(R.string.remote_storage_error_storage_full)
                        WebDavFailure.EMPTY_RESPONSE -> text(R.string.remote_storage_error_empty_response)
                        WebDavFailure.HTTP -> text(
                            R.string.remote_storage_error_http,
                            error.statusCode ?: 0
                        )
                    }
                },
                categoryLabel = { text(it.labelResource) },
                providerLabel = { text(it.labelResource) }
            )
        }

        private fun localizedContext(context: Context, languageTags: List<String>): Context {
            if (languageTags.isEmpty()) return context
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(languageTags.joinToString(",")))
            return context.createConfigurationContext(configuration)
        }
    }
}
