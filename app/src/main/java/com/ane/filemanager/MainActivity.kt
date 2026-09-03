package com.ane.filemanager

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import android.text.InputType
import com.ane.filemanager.core.file.FileTypeResolver
import com.ane.filemanager.operation.FileProblem
import com.ane.filemanager.operation.fileProblemMessage
import com.ane.filemanager.interaction.FileInteractionService
import com.ane.filemanager.core.file.FileQueryService
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.provider.LocalDocumentsProvider
import com.ane.filemanager.sharing.ShareMimeTypes
import com.ane.filemanager.ui.FileManagerView
import com.ane.filemanager.ui.onboarding.OnboardingStore
import com.ane.filemanager.ui.onboarding.OnboardingWorkspace
import com.ane.filemanager.update.AppUpdateController
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import java.io.File

class MainActivity : Activity() {
    private lateinit var contentRoot: FrameLayout
    private lateinit var fileView: FileManagerView
    private var pickerRequest: PickerRequest? = null
    private var pickerButton: Button? = null
    private val fullscreenOverlays = mutableListOf<FullscreenOverlay>()
    private val onboardingStore by lazy { OnboardingStore(this) }
    private val fileInteractionsDelegate = lazy { FileInteractionService(this) }
    private val fileInteractions by fileInteractionsDelegate
    private val updateControllerDelegate = lazy { AppUpdateController(this) }
    internal val updateController: AppUpdateController get() = updateControllerDelegate.value

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        }
        pickerRequest = PickerRequest.from(intent)
        val viewedDirectory = resolveViewedDirectory(intent)
        val onboardingWorkspace = if (
            !BuildConfig.DEBUG && pickerRequest == null && !onboardingStore.isCompleted()
        ) {
            OnboardingWorkspace.prepare(this)
        } else {
            if (BuildConfig.DEBUG || onboardingStore.isCompleted()) OnboardingWorkspace.clear(this)
            null
        }
        contentRoot = FrameLayout(this)
        fileView = FileManagerView(
            host = this,
            launchDirectory = viewedDirectory,
            pickerAllowsMultiple = pickerRequest?.allowsMultiple == true,
            fileFilter = { pickerRequest?.accepts(it) != false },
            onPickerFileOpened = pickerRequest?.let { { file -> handlePickerFileOpened(file) } },
            onSelectionChanged = ::updatePickerButton,
            onboardingWorkspace = onboardingWorkspace,
            onOnboardingCompleted = {
                onboardingStore.markCompleted()
                toast(getString(R.string.tutorial_complete_toast))
                recreate()
            }
        )
        contentRoot.addView(fileView, FrameLayout.LayoutParams(-1, -1))
        pickerRequest?.let { addPickerButton() }
        setContentView(contentRoot)
        if (onboardingWorkspace == null) ensureStorageAccess()
        if (pickerRequest == null && onboardingWorkspace == null) updateController.checkOnLaunch()
    }

    fun showFullscreenOverlay(view: View, onBack: () -> Unit) {
        fullscreenOverlays.removeAll { it.view === view }
        fullscreenOverlays += FullscreenOverlay(view, onBack)
        contentRoot.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.requestApplyInsets()
    }

    fun removeFullscreenOverlay(view: View) {
        val removed = fullscreenOverlays.removeAll { it.view === view }
        if (!removed) return
        contentRoot.removeView(view)
        fullscreenOverlays.lastOrNull()?.view?.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        if (::fileView.isInitialized) fileView.refresh()
        if (updateControllerDelegate.isInitialized()) updateController.continuePendingInstallIfAllowed()
    }

    override fun onStop() {
        if (::fileView.isInitialized) fileView.persistSession()
        super.onStop()
    }

    override fun onDestroy() {
        if (fileInteractionsDelegate.isInitialized()) fileInteractions.close()
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

    /** Resolves directory URIs sent by file-transfer apps such as LocalSend. */
    private fun resolveViewedDirectory(intent: Intent): File? {
        if (intent.action != Intent.ACTION_VIEW || intent.type !in DIRECTORY_MIME_TYPES) return null
        val uri = intent.data ?: return null
        val directory = runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let(::File)
                "content" -> resolveDocumentDirectory(uri)
                else -> null
            }?.canonicalFile
        }.getOrNull()
        return directory?.takeIf { it.isDirectory && it.canRead() }
    }

    private fun resolveDocumentDirectory(uri: Uri): File? {
        val documentId = when {
            DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
            DocumentsContract.isDocumentUri(this, uri) -> DocumentsContract.getDocumentId(uri)
            else -> return null
        }
        if (uri.authority == "$packageName.documents") {
            return if (documentId == "root") initialDirectory() else File(initialDirectory(), documentId)
        }
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null

        if (documentId.startsWith("raw:")) return File(documentId.removePrefix("raw:"))
        val volumeId = documentId.substringBefore(':', missingDelimiterValue = documentId)
        val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")
        val volumeRoot = when (volumeId.lowercase()) {
            "primary" -> initialDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> File("/storage", volumeId)
        }
        return if (relativePath.isEmpty()) volumeRoot else File(volumeRoot, relativePath)
    }

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
            filter = FileQueryService::matchingName,
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

    fun openFile(file: File, forceChooser: Boolean = false): Boolean =
        fileInteractions.open(file, forceChooser)

    fun shareFiles(files: List<File>): Boolean = fileInteractions.share(files)

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        fullscreenOverlays.lastOrNull()?.let {
            it.onBack()
            return
        }
        if (::fileView.isInitialized && fileView.handleBack()) return
        if (pickerRequest != null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        AneDialog.message(this, getString(R.string.dialog_exit_title),
            getString(R.string.dialog_exit_message), listOf(
                AneDialogAction(getString(R.string.dialog_cancel)),
                AneDialogAction(getString(R.string.dialog_exit_confirm), primary = true, run = ::finish)
            ))
    }

    private fun addPickerButton() {
        val selectsDirectory = pickerRequest?.selectsDirectory == true
        val button = Button(this).apply {
            isAllCaps = false
            isEnabled = selectsDirectory
            text = getString(if (selectsDirectory) R.string.picker_select_folder else R.string.picker_select)
            setOnClickListener {
                if (selectsDirectory) returnPickedDirectory(fileView.pickerDirectory())
                else returnPickedFiles(fileView.selectedFiles())
            }
        }
        val density = resources.displayMetrics.density
        val horizontalMargin = (20 * density).toInt()
        val bottomMarginAboveDock = (74 * density).toInt()
        contentRoot.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM
        ).apply {
            marginEnd = horizontalMargin
            bottomMargin = bottomMarginAboveDock
        })
        pickerButton = button
    }

    private fun updatePickerButton(files: List<File>) {
        if (pickerRequest?.selectsDirectory == true) return
        pickerButton?.apply {
            isEnabled = files.isNotEmpty()
            text = if (files.isEmpty()) getString(R.string.picker_select)
            else getString(R.string.picker_select_count, files.size)
        }
    }

    private fun handlePickerFileOpened(file: File) {
        if (pickerRequest?.selectsDirectory != true) returnPickedFiles(listOf(file))
    }

    private fun returnPickedFiles(selected: List<File>) {
        val request = pickerRequest ?: return
        val files = selected.filter(File::isFile).let {
            if (request.allowsMultiple) it else it.take(1)
        }
        if (files.isEmpty()) return

        val uris = files.map { LocalDocumentsProvider.uriFor(this, it) }
        val result = Intent().apply {
            data = uris.first()
            clipData = ClipData.newUri(contentResolver, files.first().name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            type = ShareMimeTypes.common(files.map(request::mimeTypeFor))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (request.persistable) addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun returnPickedDirectory(directory: File) {
        if (!directory.isDirectory) return
        val uri = LocalDocumentsProvider.treeUriFor(this, directory)
        val result = Intent().apply {
            data = uri
            clipData = ClipData.newUri(contentResolver, directory.name, uri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private class PickerRequest(
        val allowsMultiple: Boolean,
        val persistable: Boolean,
        val selectsDirectory: Boolean,
        private val acceptedTypes: List<String>
    ) {
        fun accepts(file: File): Boolean {
            if (selectsDirectory) return false
            val actual = mimeTypeFor(file)
            return acceptedTypes.any { requested ->
                requested == "*/*" || requested == actual ||
                    requested.endsWith("/*") && actual.startsWith(requested.substringBefore('/') + "/")
            }
        }

        fun mimeTypeFor(file: File): String =
            FileTypeResolver.mimeType(file, "application/octet-stream")

        companion object {
            fun from(intent: Intent): PickerRequest? {
                if (intent.action != Intent.ACTION_GET_CONTENT &&
                    intent.action != Intent.ACTION_OPEN_DOCUMENT &&
                    intent.action != Intent.ACTION_OPEN_DOCUMENT_TREE
                ) {
                    return null
                }
                val extraTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                    ?.filter(String::isNotBlank)
                    .orEmpty()
                val types = extraTypes.ifEmpty { listOf(intent.type ?: "*/*") }
                return PickerRequest(
                    allowsMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                    persistable = intent.action != Intent.ACTION_GET_CONTENT,
                    selectsDirectory = intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE,
                    acceptedTypes = types
                )
            }
        }
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        val DIRECTORY_MIME_TYPES = setOf(
            "inode/directory",
            "resource/folder",
            DocumentsContract.Document.MIME_TYPE_DIR
        )
    }

    private data class FullscreenOverlay(val view: View, val onBack: () -> Unit)

}
