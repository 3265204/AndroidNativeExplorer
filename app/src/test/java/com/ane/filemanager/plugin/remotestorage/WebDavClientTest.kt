package com.ane.filemanager.plugin.remotestorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class WebDavClientTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() {
        server.shutdown()
    }

    @Test fun parsesMultiStatusAndSkipsRequestedCollection() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/</d:href>
                <d:propstat><d:prop><d:displayname>root</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/%E7%85%A7%E7%89%87/</d:href>
                <d:propstat><d:prop><d:displayname>照片</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/report.pdf</d:href>
                <d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent()

        val entries = WebDavClient.parseMultiStatus(xml.toByteArray(), "/", "/dav/")

        assertEquals(2, entries.size)
        assertEquals("照片", entries[0].name)
        assertEquals("/照片", entries[0].path)
        assertTrue(entries[0].directory)
        assertEquals("report.pdf", entries[1].name)
        assertFalse(entries[1].directory)
        assertEquals(42L, entries[1].size)
    }

    @Test fun listsRootWithPropfindAndMapsServerBasePath() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/file.txt</d:href><d:propstat><d:prop><d:displayname>file.txt</d:displayname><d:getcontentlength>3</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()))

        val entries = client().list("/")
        val request = server.takeRequest()

        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals(listOf("/file.txt"), entries.map(WebDavEntry::path))
    }

    @Test fun uploadRefusesOverwriteThenStreamsNewFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        val source = File.createTempFile("remote-storage", ".txt").apply { writeText("abc") }
        try {
            client().upload(source, "/a b.txt")
            val probe = server.takeRequest()
            val upload = server.takeRequest()
            assertEquals("PROPFIND", probe.method)
            assertEquals("0", probe.getHeader("Depth"))
            assertEquals("PUT", upload.method)
            assertEquals("/dav/a%20b.txt", upload.path)
            assertEquals("abc", upload.body.readUtf8())
        } finally {
            source.delete()
        }
    }

    @Test fun downloadStreamsResponseAndMoveDisablesOverwrite() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().writeUtf8("content")))
        server.enqueue(MockResponse().setResponseCode(201))
        val output = ByteArrayOutputStream()

        client().download("/old.txt", output)
        client().move("/old.txt", "/new.txt")

        assertEquals("content", output.toString("UTF-8"))
        assertEquals("GET", server.takeRequest().method)
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals(server.url("/dav/new.txt").toString(), move.getHeader("Destination"))
    }

    private fun client() = WebDavClient(
        RemoteStorageProfile(
            id = "test",
            name = "Test",
            baseUrl = server.url("/dav/").toString(),
            username = "",
            kind = RemoteStorageKind.WEBDAV
        ),
        password = null
    )
}
