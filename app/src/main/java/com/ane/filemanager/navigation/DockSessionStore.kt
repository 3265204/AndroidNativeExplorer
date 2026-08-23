package com.ane.filemanager.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

internal data class RestoredDockSession(
    val tabs: List<BrowserTab>,
    val activeDirectory: File
)

/** Durable dock state used across process death and in-place application updates. */
internal class DockSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun restore(storageRoot: File, storageLabel: String, labelFor: (File) -> String): RestoredDockSession? {
        val raw = preferences.getString(KEY_STATE, null) ?: return null
        return runCatching {
            val state = JSONObject(raw)
            if (state.optInt("version", 0) != VERSION) return@runCatching null
            val savedTabs = state.optJSONArray("tabs") ?: return@runCatching null
            val restored = mutableListOf<BrowserTab>()
            val knownPaths = mutableSetOf<String>()

            // Storage is an invariant: it is always present, fixed and leftmost.
            val canonicalRoot = canonicalPath(storageRoot)
            restored += BrowserTab(storageLabel, storageRoot, true)
            knownPaths += canonicalRoot

            for (index in 0 until savedTabs.length()) {
                val saved = savedTabs.optJSONObject(index) ?: continue
                val directory = saved.optString("path").takeIf(String::isNotBlank)?.let(::File) ?: continue
                if (!directory.isDirectory || !directory.canRead()) continue
                val canonical = canonicalPath(directory)
                if (!knownPaths.add(canonical)) continue
                val history = ArrayDeque<File>()
                val savedHistory = saved.optJSONArray("history") ?: JSONArray()
                for (historyIndex in 0 until savedHistory.length()) {
                    val previous = File(savedHistory.optString(historyIndex))
                    if (previous.isDirectory && previous.canRead()) history.addLast(previous)
                }
                restored += BrowserTab(
                    label = saved.optString("label").ifBlank { labelFor(directory) },
                    directory = directory,
                    pinned = saved.optBoolean("pinned", false),
                    history = history
                )
            }

            val activePath = state.optString("activePath")
            val active = restored.firstOrNull { canonicalPath(it.directory) == activePath }?.directory
                ?: storageRoot
            RestoredDockSession(restored, active)
        }.getOrNull()
    }

    fun save(tabs: List<BrowserTab>, activeIndex: Int) {
        if (tabs.isEmpty()) return
        val savedTabs = JSONArray()
        tabs.forEach { tab ->
            savedTabs.put(JSONObject().apply {
                put("label", tab.label)
                put("path", canonicalPath(tab.directory))
                put("pinned", tab.pinned)
                put("history", JSONArray().apply {
                    tab.history.forEach { put(canonicalPath(it)) }
                })
            })
        }
        val state = JSONObject().apply {
            put("version", VERSION)
            put("activePath", canonicalPath(tabs[activeIndex.coerceIn(tabs.indices)].directory))
            put("tabs", savedTabs)
        }
        // Dock changes are infrequent and the payload is tiny. A synchronous commit
        // guarantees that an immediate force-stop cannot race the disk write.
        preferences.edit().putString(KEY_STATE, state.toString()).commit()
    }

    private fun canonicalPath(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }

    private companion object {
        const val PREFERENCES = "dock_sessions"
        const val KEY_STATE = "state"
        const val VERSION = 1
    }
}
