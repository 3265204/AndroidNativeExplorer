package com.ane.filemanager.plugin.archive

import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.AnePlugin
import com.ane.filemanager.plugin.api.PluginFile
import com.ane.filemanager.plugin.api.PluginFileAction
import com.ane.filemanager.plugin.api.PluginHost
import com.ane.filemanager.plugin.api.PluginSelectionActionProvider
import com.ane.filemanager.plugin.api.PluginTaskResult
import com.ane.filemanager.ui.dialog.AneDialog
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ArchivePluginEntry : AnePlugin, PluginSelectionActionProvider {
    override fun supports(file: PluginFile) = ArchiveVolumeResolver.matchesName(file.name)

    override fun open(file: PluginFile, host: PluginHost): Boolean {
        begin(file.toFile(), host)
        return true
    }

    override fun fileActions(file: PluginFile, host: PluginHost) = listOf(
        PluginFileAction("extract", host.activity.getString(R.string.archive_action_extract)) { begin(file.toFile(), host) }
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
        AneDialog.choices(
            activity = host.activity,
            title = host.activity.getString(R.string.archive_choose_format),
            labels = labels.toList(),
            cancelLabel = host.activity.getString(R.string.archive_cancel)
        ) { index -> compress(sources, formats[index], host) }
    }

    private fun compress(sources: List<File>, format: WritableArchiveFormat, host: PluginHost) {
        host.execute(host.activity.getString(R.string.archive_status_compressing), {
            try {
                val output = ArchiveCompressor.compress(
                    sources,
                    format,
                    host.activity.getString(R.string.archive_default_name)
                )
                PluginTaskResult(true, outputPath = output.absolutePath, outputCreated = true)
            } catch (_: Exception) {
                PluginTaskResult(false, host.activity.getString(R.string.archive_error_compress_failed))
            }
        }) { result ->
            if (result.success) host.toast(host.activity.getString(R.string.archive_compression_complete))
        }
    }

    private fun begin(source: File, host: PluginHost) {
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
            try {
                when (val result = ArchiveExtractor.extract(source, password)) {
                    is ArchivePluginResult.Success -> PluginTaskResult(
                        success = true,
                        outputPath = result.value.absolutePath,
                        outputCreated = true
                    )
                    is ArchivePluginResult.Failure -> {
                        error.set(result.error)
                        result.asTask(host)
                    }
                }
            } finally {
                password?.fill('\u0000')
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
