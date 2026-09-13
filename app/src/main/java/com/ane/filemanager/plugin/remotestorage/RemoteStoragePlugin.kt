package com.ane.filemanager.plugin.remotestorage

import android.text.InputType
import android.text.format.Formatter
import android.view.View
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginAppActionProvider
import com.ane.filemanager.plugin.api.PluginDirectoryActionProvider
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginFileAction
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.PluginSelectionActionProvider
import com.ane.filemanager.plugin.api.PluginTaskResult
import com.ane.filemanager.plugin.api.file.outputs
import com.ane.filemanager.plugin.api.ui.AneBadgeKind
import com.ane.filemanager.plugin.api.ui.AneBreadcrumb
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.plugin.api.ui.AnePluginBrowserPage
import com.ane.filemanager.plugin.api.ui.ui
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.text.DateFormat
import java.util.Date

class RemoteStoragePlugin :
    AnePlugin,
    PluginAppActionProvider,
    PluginDirectoryActionProvider,
    PluginSelectionActionProvider {
    private val openPages = mutableSetOf<AnePluginBrowserPage>()

    override fun supports(file: PluginFile): Boolean = false

    override fun onUnload() {
        openPages.toList().forEach { runCatching { it.close() } }
        openPages.clear()
    }

    override fun directoryActions(directory: PluginFile, host: PluginHost): List<PluginFileAction> {
        if (!directory.toFile().isDirectory) return emptyList()
        val strings = RemoteStorageStrings.resolve(host)
        return listOf(PluginFileAction("remote-storage.open", strings.open) {
            RemoteStoragePage(host, directory, strings).show()
        })
    }

    override fun appActions(directory: PluginFile, host: PluginHost): List<PluginFileAction> {
        if (!directory.toFile().isDirectory) return emptyList()
        val strings = RemoteStorageStrings.resolve(host)
        return listOf(PluginFileAction("remote-storage.manage", strings.manage) {
            RemoteStoragePage(host, directory, strings).show()
        })
    }

    override fun selectionActions(files: List<PluginFile>, host: PluginHost): List<PluginFileAction> {
        if (files.isEmpty()) return emptyList()
        val strings = RemoteStorageStrings.resolve(host)
        return listOf(PluginFileAction("remote-storage.upload", strings.upload) {
            uploadSelection(host, files, strings)
        })
    }

    private fun uploadSelection(host: PluginHost, files: List<PluginFile>, strings: RemoteStorageStrings) {
        if (files.any { it.toFile().isDirectory }) {
            host.toast(strings.filesOnly)
            return
        }
        val profiles = RemoteStorageProfileStore(host.activity).all()
        if (profiles.isEmpty()) {
            host.toast(strings.noConnections)
            return
        }
        host.ui.choices(
            title = strings.title,
            labels = profiles.map(RemoteStorageProfile::name),
            cancelLabel = strings.cancel
        ) { index ->
            val profile = profiles[index]
            host.ui.input(
                title = strings.remoteDirectory,
                initial = "/",
                hint = strings.remoteDirectoryHint,
                inputType = InputType.TYPE_CLASS_TEXT,
                confirmLabel = strings.confirm,
                cancelLabel = strings.cancel
            ) { remoteDirectory ->
                withPassword(host, profile, strings) { password ->
                    host.execute(strings.uploading, {
                        remoteTask(password, strings) {
                            val client = RemoteStorageClientFactory.create(profile, password)
                            files.forEach { selected ->
                                client.upload(
                                    selected.toFile(),
                                    WebDavPath.child(remoteDirectory, selected.name)
                                )
                            }
                            PluginTaskResult(true, strings.uploaded(files.size))
                        }
                    }) { result -> if (result.success) result.message?.let(host::toast) }
                }
            }
        }
    }

    private fun withPassword(
        host: PluginHost,
        profile: RemoteStorageProfile,
        strings: RemoteStorageStrings,
        action: (CharArray?) -> Unit
    ) {
        if (profile.username.isBlank()) action(null)
        else host.requestPassword(strings.passwordTitle(profile.name)) { password ->
            if (password != null) action(password)
        }
    }

    private fun remoteTask(
        password: CharArray?,
        strings: RemoteStorageStrings,
        block: () -> PluginTaskResult
    ): PluginTaskResult = try {
        block()
    } catch (error: WebDavException) {
        PluginTaskResult(false, strings.webDavError(error))
    } catch (_: IOException) {
        PluginTaskResult(false, strings.networkError)
    } finally {
        password?.fill('\u0000')
    }

    private inner class RemoteStoragePage(
        private val host: PluginHost,
        private val localDirectory: PluginFile,
        private val strings: RemoteStorageStrings
    ) {
        private val ui = host.ui
        private val store = RemoteStorageProfileStore(host.activity)
        private lateinit var page: AnePluginBrowserPage
        private var profile: RemoteStorageProfile? = null
        private var remotePath: String = "/"

        fun show() {
            page = ui.browserPage(
                title = strings.title,
                closeDescription = strings.close,
                primaryActionLabel = strings.addConnection,
                onPrimaryAction = ::chooseConnectionKind
            )
            openPages += page
            showProfiles()
            page.show()
        }

        private fun showProfiles() {
            profile = null
            val profiles = store.all()
            page.summary.text = if (profiles.isEmpty()) strings.noConnections
            else strings.irreversible
            page.setBreadcrumbs(listOf(AneBreadcrumb(strings.title, true)))
            page.setRows(
                profiles.map { item -> profileRow(item) },
                ui.emptyState(host.activity, strings.noConnections).takeIf { profiles.isEmpty() }
            )
        }

        private fun profileRow(item: RemoteStorageProfile): View {
            val kind = strings.providerLabel(item.kind)
            val transport = if (item.kind.nativeTransport) strings.nativeTransport else strings.gatewayTransport
            val detail = "$kind · $transport · ${item.username.ifBlank { strings.anonymous }} · ${item.baseUrl}"
            return ui.listRow(
                host.activity,
                ui.badge(host.activity, item.kind.badge, ui.badgeColor(AneBadgeKind.FOLDER, item.name)),
                item.name,
                detail,
                true
            ) { showProfileActions(item) }
        }

        private fun showProfileActions(item: RemoteStorageProfile) {
            ui.choices(
                title = strings.connectionActions,
                labels = listOf(strings.browse, strings.edit, strings.remove),
                cancelLabel = strings.cancel
            ) { selected ->
                when (selected) {
                    0 -> {
                        profile = item
                        remotePath = "/"
                        loadRemote()
                    }
                    1 -> editConnection(item)
                    2 -> {
                        store.remove(item.id)
                        showProfiles()
                    }
                }
            }
        }

        private fun chooseConnectionKind() {
            ui.choices(
                title = strings.chooseCategory,
                labels = RemoteStorageCategory.entries.map(strings.categoryLabel),
                cancelLabel = strings.cancel
            ) { categoryIndex ->
                val category = RemoteStorageCategory.entries[categoryIndex]
                val kinds = RemoteStorageKind.inCategory(category)
                ui.choices(
                    title = strings.chooseProvider,
                    labels = kinds.map(strings.providerLabel),
                    cancelLabel = strings.cancel
                ) { kindIndex -> prepareConnection(kinds[kindIndex]) }
            }
        }

        private fun prepareConnection(kind: RemoteStorageKind) {
            if (kind.nativeTransport) {
                askConnectionFields(null, kind)
                return
            }
            ui.message(
                strings.gatewayRequiredTitle,
                strings.gatewayRequired(strings.providerLabel(kind)),
                listOf(
                    AneDialogAction(strings.cancel),
                    AneDialogAction(strings.continueLabel, primary = true) {
                        askConnectionFields(null, kind)
                    }
                )
            )
        }

        private fun editConnection(existing: RemoteStorageProfile) {
            askConnectionFields(existing, existing.kind)
        }

        private fun askConnectionFields(existing: RemoteStorageProfile?, kind: RemoteStorageKind) {
            ui.input(
                title = strings.connectionName,
                initial = existing?.name ?: strings.providerLabel(kind),
                hint = strings.connectionNameHint,
                inputType = InputType.TYPE_CLASS_TEXT,
                confirmLabel = strings.confirm,
                cancelLabel = strings.cancel,
                validate = { name ->
                    when {
                        name.isBlank() -> strings.connectionName
                        store.all().any { it.id != existing?.id && it.name.equals(name, true) } -> strings.duplicateName
                        else -> null
                    }
                }
            ) { name ->
                ui.input(
                    title = if (kind.nativeTransport) strings.serverUrl else strings.gatewayUrl,
                    initial = existing?.baseUrl.orEmpty(),
                    hint = if (kind.nativeTransport) strings.serverUrlHint else strings.gatewayUrlHint,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                    confirmLabel = strings.confirm,
                    cancelLabel = strings.cancel,
                    validate = { url -> if (validUrl(url)) null else strings.invalidUrl }
                ) { baseUrl ->
                    ui.input(
                        title = strings.username,
                        initial = existing?.username.orEmpty(),
                        hint = strings.usernameHint,
                        inputType = InputType.TYPE_CLASS_TEXT,
                        confirmLabel = strings.confirm,
                        cancelLabel = strings.cancel
                    ) { username ->
                        store.save(
                            if (existing == null) store.create(name, baseUrl, username, kind)
                            else existing.copy(
                                name = name.trim(),
                                baseUrl = RemoteStorageProfileStore.normalizeBaseUrl(baseUrl),
                                username = username.trim()
                            )
                        )
                        showProfiles()
                    }
                }
            }
        }

        private fun loadRemote() {
            val current = profile ?: return
            withPassword(host, current, strings) { password ->
                var entries = emptyList<WebDavEntry>()
                host.execute(strings.loading, {
                    remoteTask(password, strings) {
                        entries = RemoteStorageClientFactory.create(current, password).list(remotePath)
                        PluginTaskResult(true)
                    }
                }) { result -> if (result.success) renderRemote(entries) }
            }
        }

        private fun renderRemote(entries: List<WebDavEntry>) {
            val current = profile ?: return
            page.summary.text = strings.entries(remotePath, entries.size)
            page.setBreadcrumbs(buildBreadcrumbs(current))
            val rows = buildList {
                if (remotePath != "/") add(remoteUpRow())
                add(newFolderRow())
                entries.forEach { add(remoteEntryRow(it)) }
            }
            page.setRows(rows, ui.emptyState(host.activity, strings.emptyFolder).takeIf { entries.isEmpty() })
        }

        private fun buildBreadcrumbs(current: RemoteStorageProfile): List<AneBreadcrumb> {
            val result = mutableListOf(
                AneBreadcrumb(strings.title, false) { showProfiles() },
                AneBreadcrumb(current.name, remotePath == "/") {
                    remotePath = "/"
                    loadRemote()
                }
            )
            var path = ""
            val segments = WebDavPath.normalize(remotePath).split('/').filter(String::isNotBlank)
            segments.forEachIndexed { index, segment ->
                path = WebDavPath.child(path, segment)
                val target = path
                result += AneBreadcrumb(segment, index == segments.lastIndex) {
                    remotePath = target
                    loadRemote()
                }
            }
            return result
        }

        private fun remoteUpRow(): View = ui.listRow(
            host.activity,
            ui.badge(host.activity, "..", ui.badgeColor(AneBadgeKind.FOLDER)),
            strings.parent,
            WebDavPath.parent(remotePath),
            true
        ) {
            remotePath = WebDavPath.parent(remotePath)
            loadRemote()
        }

        private fun newFolderRow(): View = ui.listRow(
            host.activity,
            ui.badge(host.activity, "+", ui.badgeColor(AneBadgeKind.FOLDER)),
            strings.newFolder,
            remotePath,
            true,
            ::createFolder
        )

        private fun remoteEntryRow(entry: WebDavEntry): View {
            val detail = if (entry.directory) {
                entry.modifiedAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) }.orEmpty()
            } else {
                entry.size?.let { Formatter.formatShortFileSize(host.activity, it) }.orEmpty()
            }
            return ui.listRow(
                host.activity,
                ui.badge(
                    host.activity,
                    if (entry.directory) strings.folderBadge else extensionBadge(entry.name, strings.fileBadge),
                    ui.badgeColor(if (entry.directory) AneBadgeKind.FOLDER else AneBadgeKind.FILE, entry.name)
                ),
                entry.name,
                detail,
                entry.directory
            ) {
                if (entry.directory) {
                    remotePath = entry.path
                    loadRemote()
                } else showEntryActions(entry)
            }
        }

        private fun showEntryActions(entry: WebDavEntry) {
            ui.choices(
                title = strings.itemActions,
                labels = listOf(strings.download, strings.rename, strings.delete),
                cancelLabel = strings.cancel
            ) { selected ->
                when (selected) {
                    0 -> download(entry)
                    1 -> rename(entry)
                    2 -> confirmDelete(entry)
                }
            }
        }

        private fun createFolder() {
            val current = profile ?: return
            ui.input(
                title = strings.folderName,
                inputType = InputType.TYPE_CLASS_TEXT,
                confirmLabel = strings.confirm,
                cancelLabel = strings.cancel,
                validate = { name -> if (validRemoteName(name)) null else strings.folderName }
            ) { name ->
                withPassword(host, current, strings) { password ->
                    host.execute(strings.creatingFolder, {
                        remoteTask(password, strings) {
                            RemoteStorageClientFactory.create(current, password)
                                .createDirectory(WebDavPath.child(remotePath, name))
                            PluginTaskResult(true)
                        }
                    }) { result -> if (result.success) loadRemote() }
                }
            }
        }

        private fun download(entry: WebDavEntry) {
            val current = profile ?: return
            withPassword(host, current, strings) { password ->
                host.execute(strings.downloading, {
                    remoteTask(password, strings) {
                        val output = host.outputs.begin(localDirectory, entry.name)
                        try {
                            FileOutputStream(output.stagingPath).use {
                                RemoteStorageClientFactory.create(current, password).download(entry.path, it)
                            }
                            val committed = output.commit()
                            PluginTaskResult.recordedOutput(committed.path, strings.downloaded(committed.name))
                        } finally {
                            output.close()
                        }
                    }
                }) { result -> if (result.success) result.message?.let(host::toast) }
            }
        }

        private fun rename(entry: WebDavEntry) {
            val current = profile ?: return
            ui.input(
                title = strings.rename,
                initial = entry.name,
                inputType = InputType.TYPE_CLASS_TEXT,
                confirmLabel = strings.confirm,
                cancelLabel = strings.cancel,
                validate = { name -> if (validRemoteName(name)) null else strings.rename }
            ) { newName ->
                if (newName == entry.name) return@input
                withPassword(host, current, strings) { password ->
                    host.execute(strings.renaming, {
                        remoteTask(password, strings) {
                            RemoteStorageClientFactory.create(current, password).move(
                                entry.path,
                                WebDavPath.child(WebDavPath.parent(entry.path), newName)
                            )
                            PluginTaskResult(true)
                        }
                    }) { result -> if (result.success) loadRemote() }
                }
            }
        }

        private fun confirmDelete(entry: WebDavEntry) {
            val current = profile ?: return
            ui.message(
                strings.deleteTitle,
                strings.deleteMessage(entry.name),
                listOf(
                    AneDialogAction(strings.cancel),
                    AneDialogAction(strings.delete, destructive = true) {
                        withPassword(host, current, strings) { password ->
                            host.execute(strings.deleting, {
                                remoteTask(password, strings) {
                                    RemoteStorageClientFactory.create(current, password).delete(entry.path)
                                    PluginTaskResult(true)
                                }
                            }) { result -> if (result.success) loadRemote() }
                        }
                    }
                )
            )
        }

        private fun validUrl(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
        }.getOrDefault(false)

        private fun validRemoteName(value: String): Boolean {
            val name = value.trim()
            return name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
        }

        private fun extensionBadge(name: String, fallback: String): String =
            name.substringAfterLast('.', "").take(4).uppercase().ifBlank { fallback }
    }
}
