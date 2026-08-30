package com.ane.filemanager.sharing

/** Chooses the narrowest MIME type that describes every shared file. */
internal object ShareMimeTypes {
    fun common(mimeTypes: List<String>): String {
        val distinct = mimeTypes.distinct()
        if (distinct.isEmpty()) return "*/*"
        if (distinct.size == 1) return distinct.single()
        if (distinct.any { it == "*/*" }) return "*/*"

        val topLevels = distinct.map { it.substringBefore('/') }.distinct()
        return if (topLevels.size == 1) "${topLevels.single()}/*" else "*/*"
    }
}
