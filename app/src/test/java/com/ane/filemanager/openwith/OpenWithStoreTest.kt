package com.ane.filemanager.openwith

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenWithStoreTest {
    @Test
    fun knownTypesAssociateByMimeType() {
        assertEquals("mime:application/pdf", OpenWithStore.associationKey("application/pdf", "pdf"))
    }

    @Test
    fun unknownTypesAssociateByExtension() {
        assertEquals("extension:abc", OpenWithStore.associationKey("*/*", "ABC"))
        assertEquals("extension:*", OpenWithStore.associationKey("*/*", ""))
    }
}
