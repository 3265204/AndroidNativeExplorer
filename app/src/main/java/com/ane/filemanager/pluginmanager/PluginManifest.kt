package com.ane.filemanager.pluginmanager

import com.ane.filemanager.R
import org.json.JSONObject
import java.util.Locale

enum class PluginSource { BUNDLED, IMPORTED }

data class PluginLocalization(val name: String, val description: String)

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val defaultName: String,
    val defaultDescription: String,
    val defaultLocale: String?,
    val apiVersion: Int,
    val entryClass: String,
    val priority: Int,
    val source: PluginSource,
    val codeSha256: String? = null,
    val localizations: Map<String, PluginLocalization> = emptyMap()
)

internal fun parsePluginManifest(
    json: String,
    source: PluginSource,
    preferredLanguageTags: List<String> = emptyList()
): PluginDescriptor {
    val value = JSONObject(json)
    val id = value.getString("id").trim()
    ensure(id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}")), R.string.plugin_error_id_invalid)
    val defaultName = value.getString("name").trim()
    ensure(defaultName.isNotEmpty() && defaultName.length <= 80, R.string.plugin_error_name_invalid)
    val defaultDescription = value.optString("description", "").trim().take(240)
    val defaultLocale = value.optString("defaultLocale", "").trim().ifBlank { null }
    val localizations = parseLocalizations(value.optJSONObject("localizations"))
    val localized = localizePluginMetadata(
        defaultName, defaultDescription, defaultLocale, localizations, preferredLanguageTags
    )
    val entryClass = value.getString("entryClass").trim()
    ensure(entryClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*")), R.string.plugin_error_entry_invalid)
    val digest = value.optString("codeSha256").lowercase().ifBlank { null }
    if (source == PluginSource.IMPORTED) {
        ensure(
            digest?.matches(Regex("[0-9a-f]{64}")) == true,
            R.string.plugin_error_digest_missing
        )
    }
    return PluginDescriptor(
        id = id,
        name = localized.name,
        version = value.optString("version", "0.0.0").take(40),
        description = localized.description,
        defaultName = defaultName,
        defaultDescription = defaultDescription,
        defaultLocale = defaultLocale,
        apiVersion = value.getInt("apiVersion"),
        entryClass = entryClass,
        priority = value.optInt("priority", 0),
        source = source,
        codeSha256 = digest,
        localizations = localizations
    )
}

internal fun localizePluginMetadata(
    defaultName: String,
    defaultDescription: String,
    defaultLocale: String?,
    localizations: Map<String, PluginLocalization>,
    preferredLanguageTags: List<String>
): PluginLocalization {
    val match = preferredLanguageTags.asSequence().mapNotNull { requested ->
        val locale = Locale.forLanguageTag(requested)
        val declaredDefault = defaultLocale?.let(Locale::forLanguageTag)
        if (declaredDefault != null && (
                locale.toLanguageTag() == declaredDefault.toLanguageTag() ||
                    locale.language == declaredDefault.language
            )) {
            PluginLocalization(defaultName, defaultDescription)
        } else {
            localizations[locale.toLanguageTag()] ?: localizations[locale.language]
        }
    }.firstOrNull()
    return PluginLocalization(
        match?.name?.ifBlank { defaultName } ?: defaultName,
        match?.description?.ifBlank { defaultDescription } ?: defaultDescription
    )
}

private fun parseLocalizations(value: JSONObject?): Map<String, PluginLocalization> {
    if (value == null) return emptyMap()
    return value.keys().asSequence().mapNotNull { rawTag ->
        val tag = Locale.forLanguageTag(rawTag).toLanguageTag()
        if (tag == "und") return@mapNotNull null
        val item = value.optJSONObject(rawTag) ?: return@mapNotNull null
        val name = item.optString("name", "").trim().take(80)
        val description = item.optString("description", "").trim().take(240)
        if (name.isBlank() && description.isBlank()) null
        else tag to PluginLocalization(name, description)
    }.toMap()
}

private fun ensure(condition: Boolean, messageResource: Int) {
    if (!condition) throw PluginProblem(messageResource)
}
