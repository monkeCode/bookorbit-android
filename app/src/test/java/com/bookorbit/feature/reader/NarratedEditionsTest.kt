package com.bookorbit.feature.reader

import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.model.BookDetail
import com.bookorbit.core.model.BookFileRef
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NarratedEditionsTest {
    @Test fun `mixed mp3 and readalong book discovers streamed EPUB without downloading its archive`() = runTest {
        val session = SessionManager(mockk<SecureStorage>(relaxed = true), Json).apply {
            setServerUrl("https://synthetic.example"); signIn("synthetic", AuthUser(7, "fixture"))
        }
        val source = mockk<ReaderSource>()
        val streaming = mockk<NativeNarrationStreaming>()
        val timeline = NativeNarrationTimeline(NativeNarrationPlaylist(5, 84, 10.0,
            listOf(NativeNarrationClip(0, 0, "one.smil", "one.xhtml", "p1", "audio.mp3", "audio/mpeg", 0.0, 10.0)),
            listOf(NativeNarrationSection(0, "one.xhtml", "One", "one.smil", 0.0, 10.0)),
            listOf(NativeNarrationResource("audio.mp3", "audio/mpeg", 100))), 5, 84)
        val plan = mockk<NativeNarrationPlan>(relaxed = true)
        every { plan.timeline } returns timeline
        coEvery { source.availableFile(5, any()) } returns null
        coEvery { streaming.open(5, 84, any()) } returns plan
        val service = NarratedEditions(source, mockk(), streaming, mockk(), mockk(), mockk(), session, mockk(), mockk())
        val result = service.discover(BookDetail(5, 1, "Test", "", "", files = listOf(
            BookFileRef(100, "mp3", "primary"), BookFileRef(84, "epub", "supplementary"))))
        assertEquals(listOf(84), result.map { it.fileId })
        assertTrue(result.single().fingerprint.startsWith("stream-v2:"))
        coVerify(exactly = 1) { streaming.open(5, 84, any()) }
        coVerify(exactly = 0) { source.resolveFile(any(), any()) }
        verify(exactly = 1) { plan.close() }
    }
}
