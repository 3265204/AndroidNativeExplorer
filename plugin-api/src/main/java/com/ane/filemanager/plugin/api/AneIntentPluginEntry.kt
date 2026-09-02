package com.ane.filemanager.plugin.api

import android.app.Activity
import android.content.Intent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
        val sessionId = AnePluginHostSessions.register(host)
        try {
            host.activity.startActivity(
                Intent(host.activity, activityClass)
                    .putExtra(EXTRA_FILE_PATH, file.path)
                    .putExtra(EXTRA_HOST_SESSION_ID, sessionId)
            )
        } catch (error: RuntimeException) {
            AnePluginHostSessions.release(sessionId)
            throw error
        }
        return true
    }

    companion object {
        const val EXTRA_FILE_PATH = "com.ane.filemanager.plugin.extra.FILE_PATH"
        const val EXTRA_HOST_SESSION_ID = "com.ane.filemanager.plugin.extra.HOST_SESSION_ID"
    }
}

/**
 * Bridges a bundled plugin Activity back to the host capability object that launched it.
 *
 * Imported plugins normally render through [PluginHost] directly. Bundled viewers use Android
 * Activities for media lifecycle integration, so the launch carries an opaque in-process session
 * instead of making those Activities reach into host implementation singletons.
 */
object AnePluginHostSessions {
    private val hosts = ConcurrentHashMap<String, PluginHost>()

    fun register(host: PluginHost): String = UUID.randomUUID().toString().also { hosts[it] = host }

    fun resolve(sessionId: String?): PluginHost? = sessionId?.let(hosts::get)

    fun release(sessionId: String?) {
        if (sessionId != null) hosts.remove(sessionId)
    }

    fun releaseHost(host: PluginHost) {
        hosts.entries.removeIf { it.value === host }
    }
}
