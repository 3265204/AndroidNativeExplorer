package com.ane.filemanager.openwith

import android.content.ComponentName
import android.content.Context

internal object OpenWithStore {
    private const val PREFERENCES = "ane-open-with"

    fun associationKey(mimeType: String, extension: String): String =
        if (mimeType == "*/*") "extension:${extension.lowercase().ifBlank { "*" }}"
        else "mime:${mimeType.lowercase()}"

    fun get(context: Context, key: String): ComponentName? =
        context.getSharedPreferences(PREFERENCES, 0).getString(key, null)
            ?.let(ComponentName::unflattenFromString)

    fun put(context: Context, key: String, component: ComponentName) {
        context.getSharedPreferences(PREFERENCES, 0).edit()
            .putString(key, component.flattenToString())
            .apply()
    }

    fun remove(context: Context, key: String) {
        context.getSharedPreferences(PREFERENCES, 0).edit().remove(key).apply()
    }
}
