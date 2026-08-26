package com.ane.filemanager.pluginmanager

internal fun resolvePluginEnabled(
    id: String,
    defaultEnabled: Boolean,
    disabledIds: Set<String>,
    explicitlyEnabledIds: Set<String>
): Boolean = when (id) {
    in disabledIds -> false
    in explicitlyEnabledIds -> true
    else -> defaultEnabled
}
