package com.bookorbit.feature.ai

import com.bookorbit.core.model.*
import com.bookorbit.core.network.ApiService
import com.bookorbit.feature.readinghelp.ReadingHelpRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ReadingHelpFallbackTest {
    private val request = ReadingHelpRequest("recap-note", 5, "en", listOf("Read text"))

    private class Fixture(fallback: Boolean) {
        val a = AiTestAccount()
        val owner = a.session.owner()!!
        val api = mockk<ApiService>()
        val direct = mockk<DirectReadingHelp>()
        val repo = ReadingHelpRepository(api, Json, a.store, direct, a.session)
        init {
            a.store.save(owner, AiRoute.SERVER, AiProvider.OPENAI, "model", "", "key", directEnabled = true, fallbackToDirect = fallback)
            coEvery { api.getAppInfo() } returns AppInfo("3.10.0", features = listOf("reading-help"), readingHelp = ReadingHelpCapability(true))
            coEvery { api.probeReadingHelp(any()) } returns Response.error(403, "{}".toResponseBody())
            every { direct.answer(any(), any(), any()) } returns flowOf(ReadingHelpEvent("done", "Answer"))
        }
    }

    @Test fun `opt in preflight fallback identifies and pins direct recipient before sending selected text`() = runBlocking {
        val f = Fixture(true)
        val access = f.repo.access()
        assertTrue(access.enabled)
        assertEquals(AiRoute.DIRECT, access.connection!!.route)
        assertEquals(ReadingHelpRepository.Problem.RESTRICTED, access.fallbackReason)
        val answer = f.repo.answer(request, access.revision, access.owner, access.connection.route).toList()
        assertEquals("Answer", answer.single().text)
        coVerify { f.api.probeReadingHelp(match { it.isEmpty() }) }
        verify(exactly = 0) { f.api.readingHelp(any()) }
        verify(exactly = 1) { f.direct.answer(request, f.owner, access.revision) }
        assertEquals(AiRoute.SERVER, f.a.store.connection(f.owner).route)
    }

    @Test fun `fallback disabled does not send to configured direct adapter`() = runBlocking {
        val f = Fixture(false)
        assertFalse(f.repo.access().enabled)
        assertTrue(runCatching { f.repo.answer(request).toList() }.isFailure)
        verify { f.direct wasNot Called }
    }

    @Test fun `server recovery after direct confirmation requires fresh consent instead of changing recipient`() = runBlocking {
        val f = Fixture(true); val access = f.repo.access()
        coEvery { f.api.probeReadingHelp(any()) } returns Response.error(400, "{}".toResponseBody())
        assertTrue(runCatching { f.repo.answer(request, access.revision, access.owner, AiRoute.DIRECT).toList() }.isFailure)
        verify { f.direct wasNot Called }; verify(exactly = 0) { f.api.readingHelp(any()) }
    }

    @Test fun `a submitted server model request is never replayed to the fallback provider`() = runBlocking {
        val f = Fixture(true)
        coEvery { f.api.probeReadingHelp(any()) } returns Response.error(400, "{}".toResponseBody())
        val access = f.repo.access()
        val call = mockk<retrofit2.Call<ResponseBody>>(relaxed = true)
        every { f.api.readingHelp(request) } returns call
        every { call.enqueue(any()) } answers { firstArg<retrofit2.Callback<ResponseBody>>().onFailure(call, java.io.IOException()) }
        assertTrue(runCatching { f.repo.answer(request, access.revision, access.owner, AiRoute.SERVER).toList() }.isFailure)
        verify(exactly = 1) { f.api.readingHelp(request) }
        verify { f.direct wasNot Called }
    }
}
