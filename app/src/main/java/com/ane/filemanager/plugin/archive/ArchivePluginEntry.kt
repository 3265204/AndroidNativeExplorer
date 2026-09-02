package com.ane.filemanager.plugin.archive

import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginFileAction
import com.ane.filemanager.plugin.api.PluginFileIcon
import com.ane.filemanager.plugin.api.PluginFileIconProvider
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.PluginSelectionActionProvider
import com.ane.filemanager.plugin.api.PluginTaskResult
import com.ane.filemanager.plugin.api.ui.ui
import com.ane.filemanager.plugin.api.file.fileQueries
import com.ane.filemanager.plugin.api.file.outputs
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ArchivePluginEntry : AnePlugin, PluginSelectionActionProvider, PluginFileIconProvider {
    override fun supports(file: PluginFile) = ArchiveVolumeResolver.matchesName(file.name)

    override fun fileIcon(file: PluginFile): PluginFileIcon? =
        PluginFileIcon.ARCHIVE.takeIf { supports(file) }

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        browse(file.toFile(), null, host)
        return true
    }

    override fun fileActions(file: PluginFile, host: PluginHost) = listOf(
        PluginFileAction("browse", host.activity.getString(R.string.archive_action_browse)) {
            browse(file.toFile(), null, host)
        },
        PluginFileAction("extract", host.activity.getString(R.string.archive_action_extract)) {
            beginExtract(file.toFile(), host)
        }
    )

    override fun selectionActions(files: List<PluginFile>, host: PluginHost): List<PluginFileAction> {
        val formats = ArchiveCompressor.availableFormats()
        if (files.isEmpty() || formats.isEmpty()) return emptyList()
        return listOf(PluginFileAction(
            "compress",
            host.activity.getString(R.string.archive_action_compress)
        ) { chooseCompressionFormat(files.map(PluginFile::toFile), formats, host) })
    }

    private fun chooseCompressionFormat(
        sources: List<File>,
        formats: List<WritableArchiveFormat>,
        host: PluginHost
    ) {
        val labels = formats.map { format ->
            host.activity.getString(when (format) {
                WritableArchiveFormat.ZIP -> R.string.archive_format_zip
                WritableArchiveFormat.SEVEN_Z -> R.string.archive_format_7z
                WritableArchiveFormat.TAR -> R.string.archive_format_tar
                WritableArchiveFormat.TAR_GZIP -> R.string.archive_format_tar_gzip
            })
        }.toTypedArray()
        host.ui.choices(
            title = host.activity.getString(R.string.archive_choose_format),
            labels = labels.toList(),
            cancelLabel = host.activity.getString(R.string.archive_cancel)
        ) { index -> compress(sources, formats[index], host) }
    }

    private fun compress(sources: List<File>, format: WritableArchiveFormat, host: PluginHost) {
        host.execute(host.activity.getString(R.string.archive_status_compressing), {
            var outputSession: com.ane.filemanager.plugin.api.file.AnePluginOutputSession? = null
            try {
                val fallback = host.activity.getString(R.string.archive_default_name)
                val parent = sources.firstOrNull()?.parentFile
                    ?.let { host.fileQueries.resolve(it.absolutePath) }
                    ?: return@execute PluginTaskResult(false, host.activity.getString(R.string.archive_error_compress_failed))
                outputSession = host.outputs.begin(
                    parent,
                    ArchiveCompressor.suggestedOutputName(sources, format, fallback)
                )
                ArchiveCompressor.compressTo(
                    sources,
                    format,
                    File(outputSession.stagingPath)
                )
                val output = outputSession.commit()
                PluginTaskResult.recordedOutput(output.path)
            } catch (_: Exception) {
                PluginTaskResult(false, host.activity.getString(R.string.archive_error_compress_failed))
            } finally {
                outputSession?.close()
            }
        }) { result ->
            if (result.success) host.toast(host.activity.getString(R.string.archive_compression_complete))
        }
    }

    private fun browse(source: File, password: CharArray?, host: PluginHost) {
        val entries = AtomicReference<List<ArchiveEntryInfo>?>()
        val error = AtomicReference<ArchivePluginError?>()
        host.execute(host.activity.getString(R.string.archive_status_reading), {
            when (val result = ArchiveExtractor.list(source, password)) {
                is ArchivePluginResult.Success -> {
                    entries.set(result.value)
                    PluginTaskResult(true)
                }
                is ArchivePluginResult.Failure -> {
                    error.set(result.error)
                    if (result.error in setOf(
                            ArchivePluginError.PASSWORD_REQUIRED,
                            ArchivePluginError.WRONG_PASSWORD
                        )) PluginTaskResult(false) else result.asTask(host)
                }
            }
        }) { result ->
            if (result.success) {
                ArchiveBrowserDialog(host, source, entries.get().orEmpty()) {
                    beginExtract(source, host)
                }.show()
            } else if (error.get() in setOf(
                    ArchivePluginError.PASSWORD_REQUIRED,
                    ArchivePluginError.WRONG_PASSWORD
                )) {
                host.requestPassword(
                    host.activity.getString(R.string.archive_password_title, source.name)
                ) { nextPassword ->
                    if (nextPassword != null) browse(source, nextPassword, host)
                }
            }
        }
    }

    private fun beginExtract(source: File, host: PluginHost) {
        val inspection = AtomicReference<ArchiveInspection?>()
        host.execute(host.activity.getString(R.string.archive_status_checking), {
            when (val result = ArchiveExtractor.inspect(source)) {
                is ArchivePluginResult.Success -> {
                    inspection.set(result.value)
                    PluginTaskResult(true)
                }
                is ArchivePluginResult.Failure -> result.asTask(host)
            }
        }) { result ->
            if (!result.success) return@execute
            if (inspection.get()?.passwordRequired == true) requestPassword(source, host)
            else extract(source, null, host)
        }
    }

    private fun requestPassword(source: File, host: PluginHost) {
        host.requestPassword(host.activity.getString(R.string.archive_password_title, source.name)) { password ->
            if (password != null) extract(source, password, host)
        }
    }

    private fun extract(source: File, password: CharArray?, host: PluginHost) {
        val error = AtomicReference<ArchivePluginError?>()
        host.execute(host.activity.getString(R.string.archive_status_extracting), {
            var outputSession: com.ane.filemanager.plugin.api.file.AnePluginOutputSession? = null
            try {
                val parent = source.parentFile?.let { host.fileQueries.resolve(it.absolutePath) }
                    ?: return@execute PluginTaskResult(
                        false,
                        host.activity.getString(R.string.archive_error_extract_failed, source.name)
                    )
                outputSession = host.outputs.begin(parent, ArchiveExtractor.suggestedOutputName(source))
                when (val result = ArchiveExtractor.extractTo(
                    source,
                    File(outputSession.stagingPath),
                    password
                )) {
                    is ArchivePluginResult.Success ->
                        PluginTaskResult.recordedOutput(outputSession.commit().path)
                    is ArchivePluginResult.Failure -> {
                        error.set(result.error)
                        result.asTask(host)
                    }
                }
            } finally {
                password?.fill('\u0000')
                outputSession?.close()
            }
        }) { result ->
            if (!result.success && error.get() in setOf(
                    ArchivePluginError.PASSWORD_REQUIRED,
                    ArchivePluginError.WRONG_PASSWORD
                )) requestPassword(source, host)
            else if (result.success) host.toast(host.activity.getString(R.string.archive_operation_complete))
        }
    }

    private fun ArchivePluginResult.Failure.asTask(host: PluginHost): PluginTaskResult {
        val message = when (error) {
            ArchivePluginError.SOURCE_MISSING -> host.activity.getString(R.string.archive_error_source_missing)
            ArchivePluginError.UNSUPPORTED -> host.activity.getString(R.string.archive_error_unsupported)
            ArchivePluginError.CORRUPT -> host.activity.getString(R.string.archive_error_corrupt, subject.orEmpty())
            ArchivePluginError.UNSAFE_ENTRY -> host.activity.getString(R.string.archive_error_unsafe)
            ArchivePluginError.PASSWORD_REQUIRED -> host.activity.getString(R.string.archive_error_password_required)
            ArchivePluginError.WRONG_PASSWORD -> host.activity.getString(R.string.archive_error_wrong_password)
            ArchivePluginError.CREATE_DIRECTORY -> host.activity.getString(R.string.archive_error_create_directory, subject.orEmpty())
            ArchivePluginError.NAME_EXISTS -> host.activity.getString(R.string.archive_error_name_exists)
            ArchivePluginError.EXTRACT_FAILED -> host.activity.getString(R.string.archive_error_extract_failed, subject.orEmpty())
            ArchivePluginError.MISSING_VOLUME -> host.activity.getString(R.string.archive_error_missing_volume, subject.orEmpty())
        }
        return PluginTaskResult(false, message)
    }
}
