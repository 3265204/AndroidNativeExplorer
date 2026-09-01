package com.ane.filemanager.pluginmanager

import android.text.InputType
import android.webkit.MimeTypeMap
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.core.file.TextFileService
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.input.HostPluginInput
import com.ane.filemanager.plugin.api.file.PluginFileServiceProvider
import com.ane.filemanager.plugin.api.input.PluginInputProvider
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.PluginUiProvider
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginFileAction
import com.ane.filemanager.plugin.api.PluginFileIcon
import com.ane.filemanager.plugin.api.PluginFileIconProvider
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.PluginApi
import com.ane.filemanager.plugin.api.PluginDirectoryActionProvider
import com.ane.filemanager.plugin.api.PluginSelectionActionProvider
import com.ane.filemanager.plugin.api.PluginTaskResult
import com.ane.filemanager.plugin.api.PluginTerminalListener
import com.ane.filemanager.plugin.api.PluginTerminalRequest
import com.ane.filemanager.plugin.api.PluginTerminalSession
import com.ane.filemanager.pluginmanager.pty.HostPtyTerminalSession
import com.ane.filemanager.ui.PluginUiService
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class PluginAction(val label: String, val run: () -> Unit)

internal data class PluginManagerEntry(
    val descriptor: PluginDescriptor,
    val enabled: Boolean,
    val error: String?
)

private data class PluginRecord(
    val descriptor: PluginDescriptor,
    val codeFile: File?,
    var instance: AnePlugin? = null,
    var error: String? = null
)

/** In-process plugin host. Bundled and imported plugins are discovered from manifests, never a hardcoded type list. */
internal class PluginRegistry(
    private val activity: MainActivity,
    private val setBusy: (String?) -> Unit,
    private val reportOutput: (File, Boolean) -> Unit
) {
    private val preferences = activity.getSharedPreferences("ane-plugin-state", 0)
    private val installer = PluginPackageInstaller(activity)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ane-plugin-worker") }
    private val closed = AtomicBoolean(false)
    private var records = emptyList<PluginRecord>()

    private val pluginHost = object :
        PluginHost,
        PluginUiProvider,
        PluginInputProvider,
        PluginFileServiceProvider {
        override val activity get() = this@PluginRegistry.activity
        override val systemLocaleTags get() = AppLanguage.systemLanguageTags(activity)
        override val hostLocaleTags get() = AppLanguage.hostLanguageTags(activity)
        override val pluginUi = PluginUiService(activity)
        override val pluginInput = HostPluginInput
        override val pluginFiles = TextFileService

        override fun toast(message: String) = activity.toast(message)

        override fun requestPassword(title: String, callback: (CharArray?) -> Unit) {
            AneDialog.input(
                activity = activity,
                title = title,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                confirmLabel = activity.getString(R.string.plugin_dialog_confirm),
                cancelLabel = activity.getString(R.string.plugin_dialog_cancel),
                onCancel = { callback(null) },
                onConfirm = { callback(it.toCharArray()) }
            )
        }

        override fun execute(
            label: String,
            task: () -> PluginTaskResult,
            callback: (PluginTaskResult) -> Unit
        ) {
            if (closed.get()) return
            setBusy(label)
            executor.execute {
                val result = runCatching(task).getOrElse {
                    PluginTaskResult(false, it.userMessage())
                }
                activity.runOnUiThread {
                    if (closed.get()) return@runOnUiThread
                    setBusy(null)
                    val message = result.message
                    if (!result.success && !message.isNullOrBlank()) activity.toast(message)
                    if (result.success) {
                        result.outputPath?.let { reportOutput(File(it), result.outputCreated) }
                    }
                    callback(result)
                }
            }
        }

        override fun reportOutput(path: String, created: Boolean) {
            reportOutput(File(path), created)
        }

        override fun openTerminal(
            request: PluginTerminalRequest,
            listener: PluginTerminalListener
        ): PluginTerminalSession? = HostPtyTerminalSession.open(request, listener)
    }

    init { reload() }

    fun open(file: File): Boolean {
        val pluginFile = file.asPluginFile()
        for (record in matching(pluginFile)) {
            val handled = runCatching { record.instance?.open(pluginFile, pluginHost) == true }
                .getOrElse {
                    activity.toast(activity.getString(R.string.plugin_runtime_error, record.descriptor.name))
                    false
                }
            if (handled) return true
        }
        return false
    }

    fun contextActions(file: File): List<PluginAction> {
        val pluginFile = file.asPluginFile()
        return matching(pluginFile).flatMap { record ->
            runCatching { record.instance?.fileActions(pluginFile, pluginHost).orEmpty() }
                .getOrElse {
                    activity.toast(activity.getString(R.string.plugin_runtime_error, record.descriptor.name))
                    emptyList()
                }.map { action -> action.guarded(record) }
        }
    }

    fun selectionActions(files: List<File>): List<PluginAction> {
        if (files.isEmpty()) return emptyList()
        val selected = files.map { it.asPluginFile() }
        return records.flatMap { record ->
            val provider = record.instance as? PluginSelectionActionProvider ?: return@flatMap emptyList()
            runCatching { provider.selectionActions(selected, pluginHost) }
                .getOrElse {
                    activity.toast(activity.getString(R.string.plugin_runtime_error, record.descriptor.name))
                    emptyList()
                }.map { action -> action.guarded(record) }
        }
    }

    fun directoryActions(directory: File): List<PluginAction> {
        val pluginDirectory = directory.asPluginFile()
        return records.flatMap { record ->
            val provider = record.instance as? PluginDirectoryActionProvider
                ?: return@flatMap emptyList()
            runCatching { provider.directoryActions(pluginDirectory, pluginHost) }
                .getOrElse {
                    activity.toast(activity.getString(R.string.plugin_runtime_error, record.descriptor.name))
                    emptyList()
                }.map { action -> action.guarded(record) }
        }
    }

    fun fileIcon(file: File): PluginFileIcon? {
        val pluginFile = file.asPluginFile()
        records.forEach { record ->
            if (record.instance == null) return@forEach
            val provider = record.instance as? PluginFileIconProvider ?: return@forEach
            runCatching { provider.fileIcon(pluginFile) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun managerEntries(): List<PluginManagerEntry> = records.map {
        PluginManagerEntry(it.descriptor, isEnabled(it.descriptor), it.error)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val disabled = disabledIds().toMutableSet()
        val explicitlyEnabled = explicitlyEnabledIds().toMutableSet()
        if (enabled) {
            disabled.remove(id)
            explicitlyEnabled.add(id)
        } else {
            disabled.add(id)
            explicitlyEnabled.remove(id)
        }
        preferences.edit()
            .putStringSet(DISABLED_IDS, disabled)
            .putStringSet(ENABLED_IDS, explicitlyEnabled)
            .apply()
        reload()
    }

    fun install(file: File, callback: (Boolean) -> Unit) {
        pluginHost.execute(activity.getString(R.string.status_installing_plugin), {
            installer.install(file)
            PluginTaskResult(true)
        }) { result ->
            if (result.success) {
                reload()
                activity.toast(activity.getString(R.string.plugin_installed))
            }
            callback(result.success)
        }
    }

    fun remove(id: String) {
        unloadAll()
        runCatching { installer.remove(id) }
            .onFailure { activity.toast(it.userMessage()) }
        val disabled = disabledIds().toMutableSet().apply { remove(id) }
        val explicitlyEnabled = explicitlyEnabledIds().toMutableSet().apply { remove(id) }
        preferences.edit()
            .putStringSet(DISABLED_IDS, disabled)
            .putStringSet(ENABLED_IDS, explicitlyEnabled)
            .apply()
        reload()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            unloadAll()
            executor.shutdown()
        }
    }

    private fun reload() {
        unloadAll()
        val imported = installer.installed().associateBy { it.descriptor.id }
        val bundled = bundledDescriptors().associateBy(PluginDescriptor::id)
        val descriptors = imported.mapValues { it.value.descriptor }.toMutableMap()
        bundled.forEach { (id, descriptor) -> descriptors[id] = descriptor }
        records = descriptors.values.map { descriptor ->
            PluginRecord(
                descriptor,
                imported[descriptor.id]?.codeFile?.takeIf { descriptor.source == PluginSource.IMPORTED }
            )
        }.sortedWith(compareByDescending<PluginRecord> { it.descriptor.priority }
            .thenBy { it.descriptor.name.lowercase() })
        records.filter { isEnabled(it.descriptor) }.forEach(::load)
    }

    private fun bundledDescriptors(): List<PluginDescriptor> = activity.assets.list(BUNDLED_MANIFESTS)
        .orEmpty().filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                activity.assets.open("$BUNDLED_MANIFESTS/$name").bufferedReader().use {
                    parsePluginManifest(
                        it.readText(), PluginSource.BUNDLED,
                        AppLanguage.systemLanguageTags(activity)
                    )
                }
            }.getOrNull()
        }.filter { PluginApi.supports(it.apiVersion) }

    private fun load(record: PluginRecord) {
        runCatching {
            val loader = record.codeFile?.let { code ->
                val optimized = File(activity.codeCacheDir, "ane-plugin-dex").apply { mkdirs() }
                DexClassLoader(code.absolutePath, optimized.absolutePath, null, activity.classLoader)
            } ?: activity.classLoader
            val type = Class.forName(record.descriptor.entryClass, true, loader)
            val instance = type.getDeclaredConstructor().newInstance()
            if (instance !is AnePlugin) throw PluginProblem(R.string.plugin_error_entry_contract)
            instance.onLoad(pluginHost)
            record.instance = instance
        }.onFailure { record.error = it.userMessage() }
    }

    private fun unloadAll() {
        records.forEach { record ->
            runCatching { record.instance?.onUnload() }
            record.instance = null
        }
    }

    private fun matching(file: PluginFile): List<PluginRecord> = records.filter { record ->
        record.instance != null && runCatching { record.instance?.supports(file) == true }.getOrDefault(false)
    }

    private fun PluginFileAction.guarded(record: PluginRecord) = PluginAction(label) {
        runCatching(run).onFailure {
            activity.toast(activity.getString(R.string.plugin_runtime_error, record.descriptor.name))
        }
    }

    private fun File.asPluginFile(): PluginFile {
        val extension = extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        return PluginFile(absolutePath, name, extension, mime)
    }

    private fun isEnabled(descriptor: PluginDescriptor): Boolean = resolvePluginEnabled(
        descriptor.id,
        descriptor.defaultEnabled,
        disabledIds(),
        explicitlyEnabledIds()
    )
    private fun disabledIds(): Set<String> = preferences.getStringSet(DISABLED_IDS, emptySet()).orEmpty()
    private fun explicitlyEnabledIds(): Set<String> =
        preferences.getStringSet(ENABLED_IDS, emptySet()).orEmpty()

    private fun Throwable.userMessage(): String = if (this is PluginProblem) {
        activity.getString(messageResource, *formatValues)
    } else {
        activity.getString(R.string.plugin_unknown_error)
    }

    companion object {
        private const val BUNDLED_MANIFESTS = "ane-plugins"
        private const val DISABLED_IDS = "disabled-plugin-ids"
        private const val ENABLED_IDS = "enabled-plugin-ids"
    }
}
