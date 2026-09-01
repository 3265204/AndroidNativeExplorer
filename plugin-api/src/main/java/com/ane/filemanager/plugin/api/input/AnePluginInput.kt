package com.ane.filemanager.plugin.api.input

import com.ane.filemanager.plugin.api.PluginHost

enum class PluginTerminalKey {
    ESCAPE,
    TAB,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    CONTROL_C,
    CONTROL_D
}

/** Input policy contract. Terminal sequences are produced by the host input layer. */
interface AnePluginInput {
    fun terminalShortcut(key: PluginTerminalKey): ByteArray

    fun terminalHardware(
        keyCode: Int,
        metaState: Int,
        unicodeCodePoint: Int
    ): ByteArray?

    fun terminalCharacters(value: String, metaState: Int): ByteArray
}

/** Optional v3 capability implemented by the current host without changing PluginHost's ABI. */
interface PluginInputProvider {
    val pluginInput: AnePluginInput
}

val PluginHost.input: AnePluginInput
    get() = (this as? PluginInputProvider)?.pluginInput
        ?: error("The current host does not provide the ANE input capability")
