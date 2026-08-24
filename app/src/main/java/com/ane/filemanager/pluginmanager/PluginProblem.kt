package com.ane.filemanager.pluginmanager

/** A host validation failure whose user-facing text is resolved from Android resources. */
internal class PluginProblem(
    val messageResource: Int,
    vararg values: Any
) : IllegalArgumentException() {
    val formatValues: Array<out Any> = values
}
