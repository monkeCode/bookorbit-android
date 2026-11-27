package com.bookorbit.feature.readinghelp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.bookorbit.R
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.db.BookOrbitDatabase
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.sync.SyncScheduler
import com.bookorbit.feature.ai.*
import com.bookorbit.feature.reader.ReaderAnnotationRepository
import com.bookorbit.feature.reader.ReaderController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.KeyStore
import java.util.UUID

class ReaderChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun followupUsesSecondaryProviderRendersMarkdownAndQueuesOneNoteAcrossReopen() {
        val context = compose.activity
        val name = "reader-chat-ui-${UUID.randomUUID()}"
        val alias = "$name-key"
        val database = Room.inMemoryDatabaseBuilder(context, BookOrbitDatabase::class.java).build()
        val diagnostics = com.bookorbit.core.diagnostics.DiagnosticLog(context)
        try {
            val storage = SecureStorage(context, name, alias, "$name-owner")
            val session = SessionManager(storage, Json).apply { setServerUrl("https://fixture.example"); signIn("fake", AuthUser(1, "fixture")) }
            val store = AiConnectionStore(storage, session)
            val owner = session.owner()!!
            val identity = ReaderChatIdentity(owner, 1, 42, "synthetic-file-revision")
            val snapshot = ReaderChatContext("chapter", "epubcfi(/6/2!/4/2/1:4)", "Synthetic chapter", "Only already-read synthetic words.",
                "epubcfi(/6/2!/4/2,/1:0,/1:4)", "Only")
            val seed = ReaderConversation("synthetic-chat", snapshot, listOf(ReaderChatMessage("q", "user", "Who?"), ReaderChatMessage("a", "assistant", "**Earlier answer**")))
            storage.putStringDurably(identity.storageKey(), Json.encodeToString(ReaderConversation.serializer(), seed))
            MockWebServer().use { provider -> MockWebServer().use { bookorbit ->
                store.save(owner, AiRoute.SERVER, AiProvider.OPENAI_COMPATIBLE, "fixture-model", "",
                    baseUrl = provider.url("/v1/").toString(), allowInsecure = true, directEnabled = true, fallbackToDirect = true)
                val api = Retrofit.Builder().baseUrl(bookorbit.url("/")).addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build().create(ApiService::class.java)
                val notes = ReaderAnnotationRepository(api, database.pendingAnnotationOpDao(), database.annotationCacheDao(), Json, SyncScheduler {}, session)
                val repository = ReaderChatRepository(storage, session, store, DirectReadingHelp(store, session, diagnostics), notes)
                var shown by mutableStateOf(true)
                compose.setContent { MaterialTheme { if (shown) ReaderChatSheet(identity, null, repository, ReaderController(), {}, onDismiss = { shown = false }) } }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Earlier answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(0, provider.requestCount); assertEquals(0, bookorbit.requestCount)
                compose.onNodeWithText(context.getString(R.string.chat_question)).performTextInput("Why?")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.onNodeWithText(context.getString(R.string.help_send)).performClick()
                compose.onNodeWithText(context.getString(R.string.chat_consent, "OpenAI-compatible", "fixture-model", snapshot.text.length, 2), substring = true).assertIsDisplayed()
                assertEquals(0, provider.requestCount)
                provider.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"**Follow-up answer**"}}]}"""))
                compose.onAllNodesWithText(context.getString(R.string.help_send)).onLast().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Follow-up answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, provider.requestCount); assertEquals(0, bookorbit.requestCount)
                val sent = provider.takeRequest()
                assertEquals("/v1/chat/completions", sent.path)
                val wire = sent.body.readUtf8()
                assertTrue(wire.contains(snapshot.text)); assertTrue(wire.contains("Who?")); assertTrue(wire.contains("**Earlier answer**")); assertTrue(wire.contains("Why?"))
                assertFalse(wire.contains("epubcfi")); assertNull(sent.getHeader("Cookie")); assertNull(sent.getHeader("Authorization"))
                compose.onAllNodesWithText(context.getString(R.string.chat_save_note)).onFirst().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.chat_note_queued)).fetchSemanticsNodes().isNotEmpty() }
                val queued = runBlocking { database.pendingAnnotationOpDao().forOwnedBook(1, 1) }
                assertEquals(1, queued.size); assertEquals("**Follow-up answer**", queued.single().note)
                assertEquals(42, queued.single().bookFileId); assertEquals(snapshot.noteCfi, queued.single().cfi)
                compose.runOnIdle { shown = false }; compose.waitForIdle()
                compose.runOnIdle { shown = true }
                compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.chat_note_queued)).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, provider.requestCount); assertEquals(0, bookorbit.requestCount)
                assertEquals(1, runBlocking { database.pendingAnnotationOpDao().forOwnedBook(1, 1) }.size)
            } }
        } finally {
            database.close(); diagnostics.close()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-owner")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
