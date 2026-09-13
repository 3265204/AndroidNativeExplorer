package com.ane.filemanager.plugin.remotestorage

import android.util.Base64
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

internal data class WebDavEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long?,
    val modifiedAt: Long?
)

internal enum class WebDavFailure {
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    UNSUPPORTED_OR_EXISTS,
    PARENT_MISSING,
    ALREADY_EXISTS,
    LOCKED,
    STORAGE_FULL,
    EMPTY_RESPONSE,
    HTTP
}

internal class WebDavException(
    val failure: WebDavFailure,
    val statusCode: Int? = null
) : IOException()

internal class WebDavClient(
    private val profile: RemoteStorageProfile,
    private val password: CharArray?
) : RemoteStorageClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    override fun list(path: String): List<WebDavEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop>
                </d:propfind>""".trimIndent().toRequestBody(XML)
        val request = request(path, trailingSlash = true)
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()
        return execute(request, setOf(200, 207)) { response ->
            response.body?.byteStream()?.use {
                parseMultiStatus(it.readBytes(), path, URI(profile.baseUrl).rawPath)
            }.orEmpty()
        }.sortedWith(compareByDescending<WebDavEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun exists(path: String): Boolean {
        val body = PROPFIND_MINIMAL.toRequestBody(XML)
        val request = request(path).header("Depth", "0").method("PROPFIND", body).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return false
            ensureSuccess(response.code, setOf(200, 207))
            return true
        }
    }

    override fun createDirectory(path: String) {
        val request = request(path, trailingSlash = true).method("MKCOL", EMPTY_BODY).build()
        execute(request, setOf(200, 201, 204)) { Unit }
    }

    override fun delete(path: String) {
        val request = request(path).delete().build()
        execute(request, setOf(200, 202, 204)) { Unit }
    }

    override fun move(source: String, destination: String) {
        val request = request(source)
            .header("Destination", url(destination))
            .header("Overwrite", "F")
            .method("MOVE", EMPTY_BODY)
            .build()
        execute(request, setOf(200, 201, 204)) { Unit }
    }

    override fun download(path: String, output: OutputStream) {
        execute(request(path).get().build(), setOf(200)) { response ->
            val body = response.body ?: throw WebDavException(WebDavFailure.EMPTY_RESPONSE)
            body.byteStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
        }
    }

    override fun upload(localFile: File, remotePath: String) {
        if (exists(remotePath)) throw WebDavException(WebDavFailure.ALREADY_EXISTS)
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = OCTET_STREAM
            override fun contentLength(): Long = localFile.length()
            override fun writeTo(sink: BufferedSink) {
                localFile.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                    buffer.fill(0)
                }
            }
        }
        execute(request(remotePath).put(body).build(), setOf(200, 201, 204)) { Unit }
    }

    private fun request(path: String, trailingSlash: Boolean = false): Request.Builder {
        val builder = Request.Builder().url(url(path, trailingSlash))
        authorizationHeader()?.let { builder.header("Authorization", it) }
        return builder
    }

    private fun url(path: String, trailingSlash: Boolean = false): String {
        val encoded = WebDavPath.encode(path).trimStart('/')
        val suffix = if (encoded.isEmpty()) "" else encoded + if (trailingSlash) "/" else ""
        return profile.baseUrl + suffix
    }

    private fun authorizationHeader(): String? {
        if (profile.username.isBlank()) return null
        val source = CharArray(profile.username.length + 1 + (password?.size ?: 0))
        profile.username.toCharArray().copyInto(source)
        source[profile.username.length] = ':'
        password?.copyInto(source, profile.username.length + 1)
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(source))
        source.fill('\u0000')
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        val value = "Basic " + Base64.encodeToString(bytes, Base64.NO_WRAP)
        bytes.fill(0)
        if (encoded.hasArray()) encoded.array().fill(0)
        return value
    }

    private fun <T> execute(request: Request, accepted: Set<Int>, block: (okhttp3.Response) -> T): T =
        client.newCall(request).execute().use { response ->
            ensureSuccess(response.code, accepted)
            block(response)
        }

    private fun ensureSuccess(code: Int, accepted: Set<Int>) {
        if (code in accepted) return
        val failure = when (code) {
            401 -> WebDavFailure.AUTHENTICATION
            403 -> WebDavFailure.FORBIDDEN
            404 -> WebDavFailure.NOT_FOUND
            405 -> WebDavFailure.UNSUPPORTED_OR_EXISTS
            409 -> WebDavFailure.PARENT_MISSING
            412 -> WebDavFailure.ALREADY_EXISTS
            423 -> WebDavFailure.LOCKED
            507 -> WebDavFailure.STORAGE_FULL
            else -> WebDavFailure.HTTP
        }
        throw WebDavException(failure, code)
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROPFIND_MINIMAL =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

        internal fun parseMultiStatus(
            xml: ByteArray,
            requestedPath: String,
            basePath: String = "/"
        ): List<WebDavEntry> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(xml.inputStream())
            val requested = WebDavPath.normalize(requestedPath)
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            return buildList {
                for (index in 0 until responses.length) {
                    val response = responses.item(index) as? Element ?: continue
                    val href = response.firstText("href") ?: continue
                    val path = remotePathFromHref(href, basePath)
                    if (path == requested) continue
                    val successfulProp = response.successfulProp() ?: continue
                    val directory = successfulProp.getElementsByTagNameNS("DAV:", "collection").length > 0
                    val displayName = successfulProp.firstText("displayname")
                    val name = displayName?.takeIf { it.isNotBlank() } ?: WebDavPath.name(path)
                    val size = successfulProp.firstText("getcontentlength")?.toLongOrNull()
                    val modified = successfulProp.firstText("getlastmodified")?.let(::parseHttpDate)
                    add(WebDavEntry(name, path, directory, size, modified))
                }
            }.distinctBy { it.path }
        }

        private fun Element.firstText(localName: String): String? =
            getElementsByTagNameNS("DAV:", localName).item(0)?.textContent?.trim()

        private fun Element.successfulProp(): Element? {
            val propstats = getElementsByTagNameNS("DAV:", "propstat")
            for (index in 0 until propstats.length) {
                val propstat = propstats.item(index) as? Element ?: continue
                if (propstat.firstText("status")?.contains(" 200 ") == true) {
                    return propstat.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element
                }
            }
            return null
        }

        private fun remotePathFromHref(href: String, basePath: String): String {
            val rawPath = runCatching { URI(href).rawPath }.getOrNull() ?: href.substringBefore('?')
            val decoded = WebDavPath.normalize(WebDavPath.decodePath(rawPath))
            val base = WebDavPath.normalize(WebDavPath.decodePath(basePath))
            return when {
                base == "/" -> decoded
                decoded == base -> "/"
                decoded.startsWith("$base/") -> WebDavPath.normalize(decoded.removePrefix(base))
                else -> decoded
            }
        }

        private fun parseHttpDate(value: String): Long? = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(value)?.time
        }.getOrNull()
    }
}
