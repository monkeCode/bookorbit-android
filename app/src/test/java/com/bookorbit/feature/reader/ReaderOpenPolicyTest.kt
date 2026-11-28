package com.bookorbit.feature.reader

import com.bookorbit.core.settings.ReaderInterface
import org.junit.Assert.*
import org.junit.Test

class ReaderOpenPolicyTest {
    @Test fun `reader choices contain only built in and BookOrbit`() {
        assertEquals(listOf(ReaderInterface.APP, ReaderInterface.SERVER_WEB), ReaderInterface.entries)
    }

    @Test fun `both old native modes migrate to the same built in reader`() {
        for (stored in listOf(null, "APP", "APP_STREAMING", "unknown"))
            assertEquals(ReaderInterface.APP, ReaderInterface.fromStored(stored))
        assertEquals(ReaderInterface.SERVER_WEB, ReaderInterface.fromStored("SERVER_WEB"))
    }

    @Test fun `undownloaded EPUB always streams and explicit download opens locally`() {
        assertTrue(shouldStreamEpub("epub", false))
        assertTrue(shouldStreamEpub("EPUB", false))
        assertFalse(shouldStreamEpub("epub", true))
        assertFalse(shouldStreamEpub("pdf", false))
        assertFalse(shouldStreamEpub("fb2", false))
    }
}
