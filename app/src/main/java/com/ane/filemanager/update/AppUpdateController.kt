package com.ane.filemanager.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.ane.filemanager.BuildConfig
import com.ane.filemanager.MainActivity
import com.ane.filemanager.R
import com.ane.filemanager.plugin.api.ui.AneDialog
import com.ane.filemanager.plugin.api.ui.AneDialogAction
import com.ane.filemanager.provider.LocalFileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Checks GitHub Releases and hands verified APK updates to Android's package installer. */
internal class AppUpdateController(private val host: MainActivity) {
    private val preferences = host.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)

    var automaticUpdatesEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTOMATIC_UPDATES, false)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTOMATIC_UPDATES, value).apply()
        }

    fun checkOnLaunch() {
        pendingFile()?.let {
            requestInstall(it)
            return
        }
        if (automaticUpdatesEnabled && launchCheckStarted.compareAndSet(false, true)) {
            checkForUpdate(interactive = false)
        }
    }

    fun checkManually() = checkForUpdate(interactive = true)

    fun continuePendingInstallIfAllowed() {
        val pending = pendingFile() ?: return
        if (Build.VERSION.SDK_INT < 26 || host.packageManager.canRequestPackageInstalls()) {
            preferences.edit().remove(KEY_PENDING_APK).apply()
            openInstaller(pending)
        }
    }

    private fun checkForUpdate(interactive: Boolean) {
        if (!busy.compareAndSet(false, true)) {
            if (interactive) host.toast(host.getString(R.string.update_already_running))
            return
        }
        if (interactive) host.toast(host.getString(R.string.update_checking))
        Thread({
            val result = runCatching { fetchLatestRelease() }
            host.runOnUiThread {
                busy.set(false)
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { release -> handleRelease(release, interactive) },
                    onFailure = {
                        if (interactive) showMessage(
                            host.getString(R.string.update_check_failed_title),
                            host.getString(R.string.update_check_failed_message)
                        )
                    }
                )
            }
        }, "ane-update-check").start()
    }

    private fun handleRelease(release: Release, interactive: Boolean) {
        if (!AppVersion.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            if (interactive) showMessage(
                host.getString(R.string.update_up_to_date_title),
                host.getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME)
            )
            return
        }
        if (!interactive && automaticUpdatesEnabled) {
            download(release)
            return
        }
        val notes = release.notes.trim().take(MAX_NOTES_LENGTH).ifEmpty {
            host.getString(R.string.update_no_release_notes)
        }
        AneDialog.message(
            activity = host,
            title = host.getString(R.string.update_available_title),
            message = host.getString(
                R.string.update_available_message,
                BuildConfig.VERSION_NAME,
                release.version,
                notes
            ),
            actions = listOf(
                AneDialogAction(host.getString(R.string.update_later)),
                AneDialogAction(
                    host.getString(R.string.update_download_and_install),
                    primary = true,
                    run = { download(release) }
                )
            )
        )
    }

    private fun download(release: Release) {
        if (!busy.compareAndSet(false, true)) return
        host.toast(host.getString(R.string.update_downloading, release.version))
        Thread({
            val result = runCatching { downloadApk(release) }
            host.runOnUiThread {
                busy.set(false)
                result.fold(
                    onSuccess = { apk ->
                        preferences.edit().putString(KEY_PENDING_APK, apk.absolutePath).apply()
                        if (!host.isFinishing && !host.isDestroyed) requestInstall(apk)
                    },
                    onFailure = {
                        if (!host.isFinishing && !host.isDestroyed) showMessage(
                            host.getString(R.string.update_download_failed_title),
                            host.getString(R.string.update_download_failed_message)
                        )
                    }
                )
            }
        }, "ane-update-download").start()
    }

    private fun fetchLatestRelease(): Release {
        val connection = openConnection(
            if (BuildConfig.DEBUG) BETA_RELEASES_API else STABLE_RELEASE_API,
            API_MIME
        )
        return connection.useResponse { response ->
            val body = response.bufferedReader().use { it.readText() }
            val json = if (BuildConfig.DEBUG) {
                val releases = JSONArray(body)
                (0 until releases.length())
                    .map { releases.getJSONObject(it) }
                    .firstOrNull { !it.optBoolean("draft") && it.optBoolean("prerelease") }
                    ?: error("No beta release is available")
            } else {
                JSONObject(body)
            }
            releaseFrom(json)
        }
    }

    private fun releaseFrom(json: JSONObject): Release {
        val assets = json.getJSONArray("assets")
        val apkUrl = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .maxByOrNull { apkPreference(it.optString("name")) }
            ?.optString("browser_download_url")
            ?.takeIf(String::isNotBlank)
            ?: error("Latest release has no APK asset")
        return Release(
            version = json.getString("tag_name").removePrefix("v").removePrefix("V"),
            notes = json.optString("body"),
            apkUrl = apkUrl
        )
    }

    private fun downloadApk(release: Release): File {
        val directory = File(host.filesDir, UPDATE_DIRECTORY).apply { mkdirs() }
        val temporary = File(directory, "ane-update.apk.part")
        val destination = File(directory, "ane-update-${safeVersion(release.version)}.apk")
        directory.listFiles()?.filter { it != temporary && it != destination }?.forEach(File::delete)
        val connection = openConnection(release.apkUrl, APK_MIME)
        connection.useResponse { input ->
            BufferedInputStream(input).use { source ->
                BufferedOutputStream(temporary.outputStream()).use { target -> source.copyTo(target) }
            }
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        validateApk(destination)
        return destination
    }

    @Suppress("DEPRECATION")
    private fun validateApk(apk: File) {
        val signatureFlags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = host.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags)
            ?: error("Invalid APK")
        val installed = host.packageManager.getPackageInfo(host.packageName, signatureFlags)
        val archiveSigners = signerHashes(info)
        val installedSigners = signerHashes(installed)
        if (info.packageName != host.packageName ||
            versionCode(info) <= versionCode(installed) ||
            archiveSigners.isEmpty() || archiveSigners != installedSigners
        ) {
            apk.delete()
            error("APK identity, signature, or version does not match")
        }
    }

    private fun requestInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !host.packageManager.canRequestPackageInstalls()) {
            AneDialog.message(
                activity = host,
                title = host.getString(R.string.update_install_permission_title),
                message = host.getString(R.string.update_install_permission_message),
                actions = listOf(
                    AneDialogAction(host.getString(R.string.dialog_cancel)),
                    AneDialogAction(
                        host.getString(R.string.update_open_permission_settings),
                        primary = true,
                        run = ::openInstallPermissionSettings
                    )
                )
            )
        } else {
            preferences.edit().remove(KEY_PENDING_APK).apply()
            openInstaller(apk)
        }
    }

    @Suppress("InlinedApi")
    private fun openInstallPermissionSettings() {
        runCatching {
            host.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${host.packageName}")
            ))
        }.onFailure {
            host.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun openInstaller(apk: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(LocalFileProvider.uriFor(host, apk), APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            host.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage(
                host.getString(R.string.update_install_failed_title),
                host.getString(R.string.update_install_failed_message)
            )
        }
    }

    private fun pendingFile(): File? {
        val path = preferences.getString(KEY_PENDING_APK, null) ?: return null
        return File(path).takeIf(File::isFile) ?: run {
            preferences.edit().remove(KEY_PENDING_APK).apply()
            null
        }
    }

    private fun showMessage(title: String, message: String) {
        AneDialog.message(
            activity = host,
            title = title,
            message = message,
            actions = listOf(AneDialogAction(host.getString(R.string.dialog_confirm), primary = true))
        )
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ANE/${BuildConfig.VERSION_NAME}")
        }

    private inline fun <T> HttpURLConnection.useResponse(block: (java.io.InputStream) -> T): T = try {
        if (responseCode !in 200..299) error("HTTP $responseCode")
        block(inputStream)
    } finally {
        disconnect()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.map {
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()),
                Base64.NO_WRAP
            )
        }.toSet()
    }

    private fun safeVersion(version: String): String = version.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun apkPreference(name: String): Int = when {
        name.contains("universal", ignoreCase = true) -> 3
        name.contains("release", ignoreCase = true) -> 2
        else -> 1
    }

    private data class Release(val version: String, val notes: String, val apkUrl: String)

    private companion object {
        const val STABLE_RELEASE_API =
            "https://api.github.com/repos/3265204/AndroidNativeExplorer/releases/latest"
        const val BETA_RELEASES_API =
            "https://api.github.com/repos/3265204/AndroidNativeExplorer/releases?per_page=20"
        const val API_MIME = "application/vnd.github+json"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val PREFERENCES = "app-updates"
        const val KEY_AUTOMATIC_UPDATES = "automatic-updates"
        const val KEY_PENDING_APK = "pending-apk"
        const val UPDATE_DIRECTORY = "updates"
        const val NETWORK_TIMEOUT_MS = 20_000
        const val MAX_NOTES_LENGTH = 1_200
        val launchCheckStarted = AtomicBoolean(false)
    }
}
