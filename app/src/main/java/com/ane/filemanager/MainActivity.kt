package com.ane.filemanager

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.Toast
import android.text.InputType
import com.ane.filemanager.operation.FileProblem
import com.ane.filemanager.operation.fileProblemMessage
import com.ane.filemanager.openwith.ChosenAppReceiver
import com.ane.filemanager.openwith.OpenWithStore
import com.ane.filemanager.provider.LocalFileProvider
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.sharing.ShareMimeTypes
import com.ane.filemanager.ui.FileManagerView
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.ui.search.CurrentFolderSearch
import java.io.File

class MainActivity : Activity() {
    private lateinit var contentRoot: FrameLayout
    private lateinit var fileView: FileManagerView
    private var fullscreenOverlay: View? = null
    private var fullscreenOverlayBack: (() -> Unit)? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        }
        contentRoot = FrameLayout(this)
        fileView = FileManagerView(this)
        contentRoot.addView(fileView, FrameLayout.LayoutParams(-1, -1))
        setContentView(contentRoot)
        ensureStorageAccess()
    }

    fun showFullscreenOverlay(view: View, onBack: () -> Unit) {
        fullscreenOverlay?.let(contentRoot::removeView)
        fullscreenOverlay = view
        fullscreenOverlayBack = onBack
        contentRoot.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.requestApplyInsets()
    }

    fun removeFullscreenOverlay(view: View) {
        if (fullscreenOverlay !== view) return
        contentRoot.removeView(view)
        fullscreenOverlay = null
        fullscreenOverlayBack = null
    }

    override fun onResume() {
        super.onResume()
        if (::fileView.isInitialized) fileView.refresh()
    }

    override fun onStop() {
        if (::fileView.isInitialized) fileView.persistSession()
        super.onStop()
    }

    override fun onDestroy() {
        if (::fileView.isInitialized) fileView.close()
        super.onDestroy()
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                toast(getString(R.string.storage_permission_prompt))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT < 30 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 42
            )
        }
    }

    fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun initialDirectory(): File = Environment.getExternalStorageDirectory()
        ?.takeIf(File::isDirectory) ?: getExternalFilesDir(null) ?: filesDir

    fun promptName(title: String, initial: String, callback: (String) -> Unit) {
        AneDialog.input(
            activity = this,
            title = title,
            initial = initial,
            inputType = InputType.TYPE_CLASS_TEXT,
            confirmLabel = getString(R.string.dialog_confirm),
            cancelLabel = getString(R.string.dialog_cancel),
            validate = { value ->
                getString(R.string.dialog_invalid_name).takeIf {
                    value.isEmpty() || value == "." || value == ".." ||
                        value.contains('/') || value.contains('\\')
                }
            },
            onConfirm = callback
        )
    }

    fun promptPath(initial: String, callback: (String) -> Unit) {
        AneDialog.input(
            activity = this,
            title = getString(R.string.dialog_path_title),
            initial = initial,
            hint = getString(R.string.dialog_path_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            confirmLabel = getString(R.string.dialog_confirm),
            cancelLabel = getString(R.string.dialog_cancel),
            validate = { value -> getString(R.string.dialog_invalid_name).takeIf { value.isEmpty() } },
            onConfirm = callback
        )
    }

    fun showFileSearch(files: List<File>, callback: (File) -> Unit) {
        AneDialog.liveSearch(
            activity = this,
            title = getString(R.string.search_current_folder),
            hint = getString(R.string.search_query_hint),
            startTypingText = getString(R.string.search_start_typing),
            noResultsText = getString(R.string.search_no_results),
            resultCount = { getString(R.string.search_results_title, it) },
            cancelLabel = getString(R.string.dialog_cancel),
            items = files,
            label = File::getName,
            filter = CurrentFolderSearch::matches,
            onSelected = callback
        )
    }

    fun confirm(title: String, message: String, action: () -> Unit) {
        AneDialog.message(this, title, message, listOf(
            AneDialogAction(getString(R.string.dialog_cancel)),
            AneDialogAction(getString(R.string.dialog_confirm), primary = true, run = action)
        ))
    }

    fun resolveNameConflict(name: String, onReplace: () -> Unit, onKeepBoth: () -> Unit) {
        AneDialog.message(this, getString(R.string.dialog_name_conflict_title),
            getString(R.string.dialog_name_conflict_message, name), listOf(
                AneDialogAction(getString(R.string.dialog_cancel)),
                AneDialogAction(getString(R.string.dialog_keep_both), run = onKeepBoth),
                AneDialogAction(getString(R.string.dialog_replace), primary = true, run = onReplace)
            ))
    }

    internal fun resolveTransferFailure(
        problem: FileProblem,
        onRetry: () -> Unit,
        onSkip: () -> Unit,
        onCancel: () -> Unit
    ) {
        AneDialog.message(
            this,
            getString(R.string.dialog_transfer_error_title),
            getString(R.string.dialog_transfer_error_message, fileProblemMessage(problem)),
            listOf(
                AneDialogAction(getString(R.string.dialog_cancel), run = onCancel),
                AneDialogAction(getString(R.string.dialog_skip), run = onSkip),
                AneDialogAction(getString(R.string.dialog_retry), primary = true, run = onRetry)
            )
        )
    }

    fun openFile(file: File, forceChooser: Boolean = false): Boolean {
        val ext = fileExtension(file)
        val mime = mimeType(file)
        val uri = LocalFileProvider.uriFor(this, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val associationKey = OpenWithStore.associationKey(mime, ext)
        if (!forceChooser) {
            OpenWithStore.get(this, associationKey)?.let { component ->
                if (launchExternal(Intent(intent).setComponent(component))) return true
                OpenWithStore.remove(this, associationKey)
            }
        }
        chooseOpenMode(file, intent, associationKey)
        return true
    }

    fun shareFiles(files: List<File>): Boolean {
        if (files.isEmpty() || files.any { !it.isFile }) return false

        val uris = ArrayList(files.map { LocalFileProvider.uriFor(this, it) })
        val mimeTypes = files.map(::mimeType)
        val target = Intent(if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = ShareMimeTypes.common(mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(files.first().name, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.distinct().toTypedArray())
            }
        }
        val chooser = Intent.createChooser(target, getString(R.string.share_file_chooser))
        return launchExternal(chooser, R.string.no_share_app)
    }

    private fun chooseOpenMode(file: File, target: Intent, associationKey: String) {
        AneDialog.message(
            this,
            getString(R.string.open_mode_title),
            getString(R.string.open_mode_message, file.name),
            listOf(
                AneDialogAction(getString(R.string.dialog_cancel)),
                AneDialogAction(getString(R.string.open_mode_once)) {
                    launchChooser(target, associationKey = null)
                },
                AneDialogAction(getString(R.string.open_mode_always), primary = true) {
                    launchChooser(target, associationKey)
                }
            )
        )
    }

    private fun launchChooser(target: Intent, associationKey: String?) {
        val chooser = if (associationKey == null) {
            Intent.createChooser(target, getString(R.string.choose_file_app))
        } else {
            val callback = Intent(this, ChosenAppReceiver::class.java)
                .putExtra(ChosenAppReceiver.EXTRA_ASSOCIATION_KEY, associationKey)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val sender = PendingIntent.getBroadcast(
                this,
                nextChooserRequestCode++,
                callback,
                flags
            ).intentSender
            Intent.createChooser(target, getString(R.string.choose_file_app), sender)
        }
        launchExternal(chooser)
    }

    private fun launchExternal(intent: Intent, failureMessage: Int = R.string.no_viewer): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        toast(getString(failureMessage))
        false
    } catch (_: SecurityException) {
        toast(getString(failureMessage))
        false
    }

    private fun fileExtension(file: File): String =
        MimeTypeMap.getFileExtensionFromUrl(file.name).lowercase()

    private fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension(file)) ?: "*/*"

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        fullscreenOverlayBack?.let {
            it()
            return
        }
        if (::fileView.isInitialized && fileView.handleBack()) return
        AneDialog.message(this, getString(R.string.dialog_exit_title),
            getString(R.string.dialog_exit_message), listOf(
                AneDialogAction(getString(R.string.dialog_cancel)),
                AneDialogAction(getString(R.string.dialog_exit_confirm), primary = true, run = ::finish)
            ))
    }

    private companion object {
        var nextChooserRequestCode = 1
    }

}
