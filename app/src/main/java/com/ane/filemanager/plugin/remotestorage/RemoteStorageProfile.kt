package com.ane.filemanager.plugin.remotestorage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RemoteStorageProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val kind: RemoteStorageKind
)

internal class RemoteStorageProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun all(): List<RemoteStorageProfile> = runCatching {
        val data = JSONArray(preferences.getString(KEY_PROFILES, "[]"))
        buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                add(
                    RemoteStorageProfile(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        baseUrl = normalizeBaseUrl(item.getString("baseUrl")),
                        username = item.optString("username"),
                        kind = parseKind(item.optString("kind", RemoteStorageKind.WEBDAV.name))
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(profile: RemoteStorageProfile) {
        val values = all().toMutableList()
        val index = values.indexOfFirst { it.id == profile.id }
        if (index >= 0) values[index] = profile else values += profile
        write(values)
    }

    fun remove(id: String) = write(all().filterNot { it.id == id })

    fun create(name: String, baseUrl: String, username: String, kind: RemoteStorageKind) = RemoteStorageProfile(
        UUID.randomUUID().toString(), name.trim(), normalizeBaseUrl(baseUrl), username.trim(), kind
    )

    private fun write(values: List<RemoteStorageProfile>) {
        val data = JSONArray()
        values.forEach { profile ->
            data.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("username", profile.username)
                    .put("kind", profile.kind.name)
            )
        }
        preferences.edit().putString(KEY_PROFILES, data.toString()).apply()
    }

    companion object {
        private const val PREFERENCES = "ane.remote-storage.profiles"
        private const val KEY_PROFILES = "profiles"

        fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/') + "/"

        private fun parseKind(value: String): RemoteStorageKind = when (value) {
            "BAIDU_WEBDAV" -> RemoteStorageKind.BAIDU_NETDISK
            else -> runCatching { RemoteStorageKind.valueOf(value) }.getOrDefault(RemoteStorageKind.WEBDAV)
        }
    }
}
