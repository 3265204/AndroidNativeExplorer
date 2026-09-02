package com.ane.filemanager.interaction

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.ane.filemanager.R
import com.ane.filemanager.openwith.ChosenAppReceiver
import com.ane.filemanager.openwith.OpenWithStore
import com.ane.filemanager.provider.LocalFileProvider
import com.ane.filemanager.sharing.ShareMimeTypes
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Host business service for opening and sharing files outside ANE. */
internal class FileInteractionService(private val activity: Activity) {
    private val chooserRequestCode = AtomicInteger(1)

    fun open(file: File, forceChooser: Boolean = false): Boolean {
        val extension = fileExtension(file)
        val mime = mimeType(file)
        val uri = LocalFileProvider.uriFor(activity, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val associationKey = OpenWithStore.associationKey(mime, extension)
        if (!forceChooser) {
            OpenWithStore.get(activity, associationKey)?.let { component ->
                if (launchExternal(Intent(intent).setComponent(component))) return true
                OpenWithStore.remove(activity, associationKey)
            }
        }
        chooseOpenMode(file, intent, associationKey)
        return true
    }

    fun share(files: List<File>): Boolean {
        if (files.isEmpty() || files.any { !it.isFile }) return false
        val uris = ArrayList(files.map { LocalFileProvider.uriFor(activity, it) })
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
        return launchExternal(
            Intent.createChooser(target, activity.getString(R.string.share_file_chooser)),
            R.string.no_share_app
        )
    }

    private fun chooseOpenMode(file: File, target: Intent, associationKey: String) {
        com.ane.filemanager.plugin.api.ui.AneDialog.message(
            activity,
            activity.getString(R.string.open_mode_title),
            activity.getString(R.string.open_mode_message, file.name),
            listOf(
                com.ane.filemanager.plugin.api.ui.AneDialogAction(activity.getString(R.string.dialog_cancel)),
                com.ane.filemanager.plugin.api.ui.AneDialogAction(activity.getString(R.string.open_mode_once)) {
                    launchChooser(target, associationKey = null)
                },
                com.ane.filemanager.plugin.api.ui.AneDialogAction(
                    activity.getString(R.string.open_mode_always),
                    primary = true
                ) { launchChooser(target, associationKey) }
            )
        )
    }

    private fun launchChooser(target: Intent, associationKey: String?) {
        val chooser = if (associationKey == null) {
            Intent.createChooser(target, activity.getString(R.string.choose_file_app))
        } else {
            val callback = Intent(activity, ChosenAppReceiver::class.java)
                .putExtra(ChosenAppReceiver.EXTRA_ASSOCIATION_KEY, associationKey)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val sender = PendingIntent.getBroadcast(
                activity,
                chooserRequestCode.getAndIncrement(),
                callback,
                flags
            ).intentSender
            Intent.createChooser(target, activity.getString(R.string.choose_file_app), sender)
        }
        launchExternal(chooser)
    }

    private fun launchExternal(intent: Intent, failureMessage: Int = R.string.no_viewer): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        toast(failureMessage)
        false
    } catch (_: SecurityException) {
        toast(failureMessage)
        false
    }

    private fun fileExtension(file: File): String =
        MimeTypeMap.getFileExtensionFromUrl(file.name).lowercase()

    private fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension(file)) ?: "*/*"

    private fun toast(message: Int) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
