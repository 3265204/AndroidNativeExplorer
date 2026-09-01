package com.ane.filemanager.plugin.api

import android.app.Activity
import android.content.Intent

/**
 * Configuration-driven entry for plugins that open one Activity for a supported file.
 * Domain-specific entries only provide the target Activity and their file filter.
 */
abstract class AneIntentPluginEntry(
    private val activityClass: Class<out Activity>,
    private val filter: (PluginFile) -> Boolean
) : AnePlugin {
    final override fun supports(file: PluginFile): Boolean = filter(file)

    final override fun open(file: PluginFile, host: PluginHost): Boolean {
        host.activity.startActivity(
            Intent(host.activity, activityClass).putExtra(EXTRA_FILE_PATH, file.path)
        )
        return true
    }

    companion object {
        const val EXTRA_FILE_PATH = "com.ane.filemanager.plugin.extra.FILE_PATH"
    }
}
