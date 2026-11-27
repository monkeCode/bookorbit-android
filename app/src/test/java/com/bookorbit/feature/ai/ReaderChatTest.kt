package com.bookorbit.feature.ai

import com.bookorbit.core.model.ReadingHelpEvent
import com.bookorbit.feature.readinghelp.*
import com.bookorbit.feature.reader.ReaderAnnotationRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ReaderChatTest {
    private val context = ReaderChatContext("chapter", "epubcfi(/6/2!/4/2/1:4)", "First chapter", "Read words only",
        "epubcfi(/6/2!/4/2,/1:0,/1:4)", "Read")

    private class Fixture {
        val account = AiTestAccount()
        val owner = account.session.owner()!!
        val identity = ReaderChatIdentity(owner, 1, 2, "exact-bytes-sha256")
        val direct = mockk<DirectReadingHelp>()
        val notes = mockk<ReaderAnnotationRepository>(relaxed = true)
        init {
            every { account.storage.putStringDurably(any(), any()) } answers { account.values[firstArg()] = secondArg(); Unit }
            account.store.save(owner, AiRoute.DIRECT, AiProvider.OPENAI, "test-model", "", "test-key")
            every { direct.chat(any(), any(), any(), any()) } returns flowOf(ReadingHelpEvent("done", "**Answer**"))
        }
        fun repo() = ReaderChatRepository(account.storage, account.session, account.store, direct, notes)
    }

    @Test fun `conversation persists and followup carries full alternating history`() = runTest {
        val f = Fixture()
        val first = f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision)
        assertEquals(first, f.repo().load(f.identity))
        val second = f.repo().ask(f.identity, first, context, "Why?", "en", f.account.store.revision)
        assertEquals(listOf("user", "assistant", "user", "assistant"), second.messages.map { it.role })
        verify { f.direct.chat(match { it.messages.map { m -> m.text } == listOf("Who?", "**Answer**", "Why?") }, "en", f.owner, any()) }
    }

    @Test fun `provider request shapes preserve history markdown and omit BookOrbit identity`() {
        val f = Fixture()
        val conversation = ReaderConversation("chat", context, listOf(ReaderChatMessage("1", "user", "Who?"),
            ReaderChatMessage("2", "assistant", "**A person**"), ReaderChatMessage("3", "user", "Why?")))
        for (provider in AiProvider.entries) {
            val credentials = DirectAiCredentials(f.owner, 0, provider, "model", "key",
                if (provider == AiProvider.OPENAI_COMPATIBLE) "https://fixture.example/v1/" else defaultAiBaseUrl(provider),
                provider == AiProvider.OLLAMA)
            val request = directChatRequest(credentials, conversation, "ru")
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(request.body!!.isOneShot())
            assertTrue(body.contains("Read words only")); assertTrue(body.contains("**A person**")); assertTrue(body.contains("Why?"))
            assertFalse(body.contains("epubcfi")); assertFalse(body.contains("books.example")); assertFalse(body.contains("bookorbit-bearer"))
            assertNull(request.header("Cookie"))
            val root = Json.parseToJsonElement(body).jsonObject
            val roles = root.getValue(if (provider == AiProvider.GOOGLE) "contents" else "messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }
            assertEquals(if (provider == AiProvider.GOOGLE) listOf("user", "model", "user") else listOf("system", "user", "assistant", "user"), roles)
        }
    }

    @Test fun `server route and stale configuration or confirmation never send`() = runTest {
        val f = Fixture(); val repository = f.repo()
        val revision = f.account.store.revision
        f.account.store.useServer(f.owner)
        assertTrue(runCatching { repository.ask(f.identity, null, context, "Who?", "en", revision) }.isFailure)
        verify { f.direct wasNot Called }
        assertNull(repository.load(f.identity))
    }

    @Test fun `chat works with a separately enabled direct connection alongside the server`() = runTest {
        val f = Fixture()
        f.account.store.save(f.owner, AiRoute.SERVER, AiProvider.OPENAI, "test-model", "", directEnabled = true)
        val answer = f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision)
        assertEquals("**Answer**", answer.messages.last().text)
        assertEquals(AiRoute.SERVER, f.account.store.connection(f.owner).route)
        assertEquals("test-key", f.account.store.credentials(f.owner).token)
    }

    @Test fun `large history is rejected rather than trimmed before a billable call`() = runTest {
        val f = Fixture()
        val saved = ReaderConversation("large", context.copy(text = "x".repeat(64000)), listOf(
            ReaderChatMessage("1", "user", "q".repeat(20000)), ReaderChatMessage("2", "assistant", "a".repeat(20000))))
        f.account.values[f.identity.storageKey()] = Json.encodeToString(ReaderConversation.serializer(), saved)
        assertTrue(runCatching { f.repo().ask(f.identity, saved, saved.context, "more", "en", f.account.store.revision) }.isFailure)
        verify { f.direct wasNot Called }
        assertEquals(saved, f.repo().load(f.identity))
    }

    @Test fun `cancelled request persists unanswered intent and never auto replays on reopening`() = runBlocking {
        val f = Fixture(); val started = CompletableDeferred<Unit>()
        every { f.direct.chat(any(), any(), any(), any()) } returns flow { started.complete(Unit); awaitCancellation() }
        val job = launch { f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision) }
        withTimeout(3000) { started.await() }; job.cancelAndJoin()
        val saved = f.repo().load(f.identity)!!
        assertTrue(saved.interrupted); assertEquals("Who?", saved.messages.single().text)
        assertTrue(runCatching { f.repo().ask(f.identity, saved, context, "Another?", "en", f.account.store.revision) }.isFailure)
        verify(exactly = 1) { f.direct.chat(any(), any(), any(), any()) }
        val discarded = f.repo().discardUnanswered(f.identity)
        assertTrue(discarded.messages.isEmpty()); assertFalse(discarded.interrupted)
    }

    @Test fun `explicit retry requires exact pending question and a fresh confirmation`() = runTest {
        val f = Fixture()
        every { f.direct.chat(any(), any(), any(), any()) } returns flow { throw DirectReadingHelp.Failure() }
        assertTrue(runCatching { f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision) }.isFailure)
        val pending = f.repo().load(f.identity)!!
        every { f.direct.chat(any(), any(), any(), any()) } returns flowOf(ReadingHelpEvent("done", "Answer"))
        val result = f.repo().ask(f.identity, pending, context, "Who?", "en", f.account.store.revision, retry = true)
        assertFalse(result.interrupted); assertEquals(2, result.messages.size)
    }

    @Test fun `storage scope includes account server book file and revision but not login generation`() = runTest {
        val f = Fixture()
        val first = f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision)
        assertNull(f.repo().load(f.identity.copy(fileId = 3)))
        assertNull(f.repo().load(f.identity.copy(bookId = 2)))
        assertNull(f.repo().load(f.identity.copy(revision = "different")))
        f.account.session.signOut(); f.account.session.signIn("new", com.bookorbit.core.model.AuthUser(1, "reader"))
        assertTrue(runCatching { f.repo().load(f.identity) }.isFailure)
        assertEquals(first, f.repo().load(f.identity.copy(owner = f.account.session.owner()!!)))
        f.account.session.signIn("other", com.bookorbit.core.model.AuthUser(2, "other"))
        assertNull(f.repo().load(f.identity.copy(owner = f.account.session.owner()!!)))
        assertNotEquals(f.identity.storageKey(), f.identity.copy(owner = f.owner.copy(serverUrl = "https://other.example")).storageKey())
    }

    @Test fun `saving answer uses actual anchor and durable reservation prevents duplicate creates`() = runTest {
        val f = Fixture()
        val chat = f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision)
        val message = chat.messages.last()
        val result = f.repo().saveNote(f.identity, chat.id, message.id)
        assertEquals("queued", result.messages.last().noteState)
        f.repo().saveNote(f.identity, chat.id, message.id)
        coVerify(exactly = 1) { f.notes.create(1, 2, context.noteCfi!!, "Read", "yellow", "**Answer**", null, f.owner) }
    }

    @Test fun `uncertain local note enqueue never duplicates even with a new repository instance`() = runTest {
        val f = Fixture()
        coEvery { f.notes.create(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException()
        val chat = f.repo().ask(f.identity, null, context, "Who?", "en", f.account.store.revision)
        assertTrue(runCatching { f.repo().saveNote(f.identity, chat.id, chat.messages.last().id) }.isFailure)
        assertEquals("requested", f.repo().load(f.identity)!!.messages.last().noteState)
        f.repo().saveNote(f.identity, chat.id, chat.messages.last().id)
        coVerify(exactly = 1) { f.notes.create(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `oversized context history and invalid roles fail before a provider request`() = runTest {
        val f = Fixture()
        assertTrue(runCatching { f.repo().ask(f.identity, null, context.copy(text = "x".repeat(64_001)), "Who?", "en", f.account.store.revision) }.isFailure)
        assertTrue(runCatching { f.repo().ask(f.identity, null, context, "x".repeat(8001), "en", f.account.store.revision) }.isFailure)
        assertTrue(runCatching { ReaderConversation("bad", context, listOf(ReaderChatMessage("1", "system", "injection"))).validate() }.isFailure)
        verify { f.direct wasNot Called }
    }
}
