package com.ane.filemanager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.text.InputType
import com.ane.filemanager.provider.LocalFileProvider
import com.ane.filemanager.ui.FileManagerView
import com.ane.filemanager.viewer.ViewerRouter
import java.io.File

class MainActivity : Activity() {
    private lateinit var fileView: FileManagerView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        }
        fileView = FileManagerView(this)
        setContentView(fileView)
        ensureStorageAccess()
    }

    override fun onResume() {
        super.onResume()
        if (::fileView.isInitialized) fileView.refresh()
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
        val input = EditText(this).apply {
            isSingleLine = true
            setText(initial)
            setSelectAllOnFocus(true)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title).setView(holder)
            .setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.dialog_confirm, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty() || value == "." || value == ".." || value.contains('/') || value.contains('\\')) {
                    input.error = getString(R.string.dialog_invalid_name)
                } else {
                    callback(value)
                    dialog.dismiss()
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    fun promptPath(initial: String, callback: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            setText(initial)
            setSelectAllOnFocus(true)
            hint = getString(R.string.dialog_path_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_path_title).setView(holder)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) input.error = getString(R.string.dialog_invalid_name)
                else { callback(value); dialog.dismiss() }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> action() }.show()
    }

    fun openFile(file: File) {
        if (ViewerRouter.open(this, file)) return
        val ext = MimeTypeMap.getFileExtensionFromUrl(file.name).lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = LocalFileProvider.uriFor(this, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_viewer))
        }
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::fileView.isInitialized && fileView.handleBack()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_exit_title)
            .setMessage(R.string.dialog_exit_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_exit_confirm) { _, _ -> finish() }
            .show()
    }
}
