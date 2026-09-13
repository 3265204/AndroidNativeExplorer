package com.ane.filemanager.plugin.remotestorage

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavPathTest {
    @Test fun normalizesAndClampsParentsAtRoot() {
        assertEquals("/photos/2026", WebDavPath.normalize("//photos/./draft/../2026/"))
        assertEquals("/safe", WebDavPath.normalize("../../safe"))
    }

    @Test fun buildsChildrenAndParents() {
        assertEquals("/a/b", WebDavPath.child("/a", "b"))
        assertEquals("/a", WebDavPath.parent("/a/b"))
        assertEquals("/", WebDavPath.parent("/a"))
    }

    @Test fun encodesPathSegmentsWithoutEncodingSlashes() {
        assertEquals("/%E7%85%A7%E7%89%87/a%20b.jpg", WebDavPath.encode("/照片/a b.jpg"))
        assertEquals("/照片/a b.jpg", WebDavPath.decodePath(WebDavPath.encode("/照片/a b.jpg")))
        assertEquals("/a+b.txt", WebDavPath.decodePath("/a+b.txt"))
    }
}
