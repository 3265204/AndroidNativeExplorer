package com.ane.filemanager.pluginmanager

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject
import com.ane.filemanager.R
import com.ane.filemanager.localization.AppLanguage
import com.ane.filemanager.plugin.api.PluginApi

internal data class InstalledPluginPackage(val descriptor: PluginDescriptor, val codeFile: File)

internal class PluginPackageInstaller(private val context: Context) {
    private val root = File(context.filesDir, "ane-plugins")

    fun install(source: File): InstalledPluginPackage {
        ensure(source.isFile && source.extension.equals("zip", ignoreCase = true), R.string.plugin_error_choose_zip)
        ensure(root.isDirectory || root.mkdirs(), R.string.plugin_error_storage_create)
        val temporary = File(context.cacheDir, "ane-plugin-${UUID.randomUUID()}.tmp")
        var incompleteDirectory: File? = null
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output -> copyLimited(input, output, MAX_PACKAGE_SIZE) }
            }
            ensure(temporary.length() in 1..MAX_PACKAGE_SIZE, R.string.plugin_error_package_size)
            val descriptor = ZipFile(temporary).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_NAME)
                    ?: throw PluginProblem(R.string.plugin_error_manifest_missing)
                ensure(
                    !manifestEntry.isDirectory && manifestEntry.size in 1..MAX_MANIFEST_SIZE,
                    R.string.plugin_error_manifest_size
                )
                val parsed = zip.getInputStream(manifestEntry).use {
                    try {
                        parsePluginManifest(
                            readUtf8(it, MAX_MANIFEST_SIZE), PluginSource.IMPORTED,
                            AppLanguage.systemLanguageTags(context)
                        )
                    } catch (problem: PluginProblem) {
                        throw problem
                    } catch (_: Throwable) {
                        throw PluginProblem(R.string.plugin_error_manifest_invalid)
                    }
                }
                ensure(
                    parsed.apiVersion == PluginApi.VERSION,
                    R.string.plugin_error_api_incompatible,
                    parsed.apiVersion,
                    PluginApi.VERSION
                )
                val dex = zip.getEntry(DEX_NAME)
                    ?: throw PluginProblem(R.string.plugin_error_dex_missing)
                ensure(!dex.isDirectory && dex.size in 1..MAX_DEX_SIZE, R.string.plugin_error_dex_size)
                val actualDigest = zip.getInputStream(dex).use { sha256(it, MAX_DEX_SIZE) }
                ensure(actualDigest == parsed.codeSha256, R.string.plugin_error_digest_mismatch)
                parsed
            }

            val packageDigest = FileInputStream(temporary).use(::sha256)
            val targetDirectory = File(root, "${descriptor.id}-${packageDigest.take(16)}")
            if (!targetDirectory.exists()) {
                ensure(targetDirectory.mkdir(), R.string.plugin_error_directory_create)
                incompleteDirectory = targetDirectory
                val target = File(targetDirectory, CODE_NAME)
                // Android 14+ requires dynamically loaded code to be read-only before bytes are written.
                FileOutputStream(target).use { output ->
                    ensure(target.setReadOnly(), R.string.plugin_error_code_read_only)
                    FileInputStream(temporary).use { input -> input.copyTo(output, COPY_BUFFER) }
                }
                File(targetDirectory, MANIFEST_NAME).writeText(descriptorToJson(descriptor))
                incompleteDirectory = null
            }
            return InstalledPluginPackage(descriptor, File(targetDirectory, CODE_NAME))
        } catch (problem: Throwable) {
            incompleteDirectory?.let { runCatching { deleteTree(it) } }
            throw problem
        } finally {
            temporary.delete()
        }
    }

    fun installed(): List<InstalledPluginPackage> = root.listFiles()
        ?.asSequence()?.filter(File::isDirectory)?.mapNotNull { directory ->
            runCatching {
                val descriptor = parsePluginManifest(
                    File(directory, MANIFEST_NAME).readText(), PluginSource.IMPORTED,
                    AppLanguage.systemLanguageTags(context)
                )
                val code = File(directory, CODE_NAME)
                ensure(code.isFile && !code.canWrite(), R.string.plugin_error_code_not_read_only)
                InstalledPluginPackage(descriptor, code)
            }.getOrNull()
        }?.groupBy { it.descriptor.id }
        ?.mapNotNull { (_, versions) -> versions.maxByOrNull { it.codeFile.parentFile?.lastModified() ?: 0L } }
        .orEmpty()

    fun remove(id: String) {
        root.listFiles()?.filter { directory ->
            runCatching {
                parsePluginManifest(File(directory, MANIFEST_NAME).readText(), PluginSource.IMPORTED).id == id
            }.getOrDefault(false)
        }?.forEach(::deleteTree)
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach(::deleteTree)
        if (!file.delete()) throw PluginProblem(R.string.plugin_error_delete_file, file.name)
    }

    private fun descriptorToJson(value: PluginDescriptor): String = JSONObject()
        .put("id", value.id).put("name", value.defaultName).put("version", value.version)
        .put("description", value.defaultDescription)
        .put("defaultLocale", value.defaultLocale)
        .put("apiVersion", value.apiVersion).put("entryClass", value.entryClass)
        .put("priority", value.priority).put("codeSha256", value.codeSha256)
        .also { output ->
            if (value.localizations.isNotEmpty()) {
                output.put("localizations", JSONObject().apply {
                    value.localizations.forEach { (tag, localized) ->
                        put(tag, JSONObject().put("name", localized.name)
                            .put("description", localized.description))
                    }
                })
            }
        }.toString(2)

    private fun sha256(input: java.io.InputStream, limit: Long = MAX_PACKAGE_SIZE): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            ensure(total <= limit, R.string.plugin_error_unpacked_too_large)
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readUtf8(input: java.io.InputStream, limit: Long): String {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(input, output, limit)
        return output.toString(Charsets.UTF_8.name())
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            ensure(total <= limit, R.string.plugin_error_package_too_large)
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MANIFEST_NAME = "plugin.json"
        const val DEX_NAME = "classes.dex"
        const val CODE_NAME = "plugin.jar"
        const val COPY_BUFFER = 64 * 1024
        const val MAX_PACKAGE_SIZE = 64L * 1024 * 1024
        const val MAX_DEX_SIZE = 48L * 1024 * 1024
        const val MAX_MANIFEST_SIZE = 64L * 1024
    }

    private fun ensure(condition: Boolean, messageResource: Int, vararg values: Any) {
        if (!condition) throw PluginProblem(messageResource, *values)
    }
}
