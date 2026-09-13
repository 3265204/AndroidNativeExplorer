package com.ane.filemanager.plugin.remotestorage

import java.net.URLDecoder
import java.net.URLEncoder

internal object WebDavPath {
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        val safe = mutableListOf<String>()
        parts.forEach { part ->
            if (part == "..") {
                if (safe.isNotEmpty()) safe.removeAt(safe.lastIndex)
            } else {
                safe += part
            }
        }
        return if (safe.isEmpty()) "/" else "/" + safe.joinToString("/")
    }

    fun child(parent: String, name: String): String = normalize("${normalize(parent)}/$name")

    fun parent(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/').ifBlank { "/" }
    }

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    fun encode(path: String): String = normalize(path).split('/').joinToString("/") { segment ->
        if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    fun decodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        // URLDecoder treats '+' as form-data whitespace, but '+' is literal inside a URI path.
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    }
}
