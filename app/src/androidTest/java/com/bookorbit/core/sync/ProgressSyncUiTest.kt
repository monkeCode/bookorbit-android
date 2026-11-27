package com.bookorbit.core.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.bookorbit.R
import com.bookorbit.core.auth.*
import com.bookorbit.core.db.*
import com.bookorbit.core.model.*
import com.bookorbit.core.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class ProgressSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oneDialogAboveHomeResolvesBothEpubCoordinatesWithoutDiscardingSessionTime() = runBlocking {
        val context = compose.activity
        val name = "progress-ui-${UUID.randomUUID()}"
        val alias = "$name-key"
        val db = Room.inMemoryDatabaseBuilder(context, BookOrbitDatabase::class.java).build()
        try {
            val storage = SecureStorage(context, name, alias, "$name-owner")
            val account = SessionManager(storage, Json).apply { setServerUrl("https://synthetic.example"); signIn("synthetic", AuthUser(7, "fixture")) }
            val codec = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
            val remote = AtomicReference(ReadingPositionEntry(42, 20.0, "2026-11-27T00:00:00Z",
                ReadingTextPosition("epubcfi(/6/2!/4/2:20)"), NarrationPosition(10.0, "chapter#one", 0, 10.0)))
            val puts = AtomicInteger()
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.method == "GET" && request.path == "/reading-progress?fileId=42" ->
                            MockResponse().setBody(codec.encodeToString(ReadingPositionList.serializer(), ReadingPositionList(listOf(remote.get()))))
                        request.method == "PUT" && request.path == "/reading-progress" -> {
                            val write = codec.decodeFromString<ReadingPositionWrites>(request.body.readUtf8()).writes.single()
                            val current = remote.get()
                            val next = if (write.kind == "text") current.copy(percentage = write.percentage, text = write.text!!)
                                else current.copy(narration = write.narration!!.copy(percentage = write.percentage))
                            remote.set(next); puts.incrementAndGet()
                            MockResponse().setBody(codec.encodeToString(ReadingPositionResults.serializer(), ReadingPositionResults(listOf(ReadingPositionResult(42, "saved", next)))))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(codec.asConverterFactory("application/json".toMediaType())).build().create(ApiService::class.java)
                val sync = ProgressSync(db.progressSyncDao(), db.readerProgressDao(), db.audioProgressDao(), api, AudiobookAssetResolver(api), account, SyncScheduler {})
                val text = sync.open("text", 5, 42, "Synthetic book", ProgressPoint(42))
                val narration = sync.open("narration", 5, 42, "Synthetic book", ProgressPoint(42))
                sync.record(text.lease, ProgressPoint(42, 25.0, cfi = "epubcfi(/6/2!/4/2:25)"))
                sync.record(narration.lease, ProgressPoint(42, 11.0, seconds = 11.0, fragment = "chapter#one", section = 0))
                sync.drain()
                remote.set(remote.get().copy(percentage = 30.0, text = ReadingTextPosition("epubcfi(/6/2!/4/2:30)"),
                    narration = NarrationPosition(12.0, "chapter#one", 0, 12.0), updatedAt = "2026-11-27T00:00:01Z"))
                sync.flushPending()
                db.pendingReadingSessionDao().upsert(PendingReadingSessionEntity("queued-time", 42, "listen", 0, 30_000, 30, null, null, 7))
                lateinit var vm: PositionConflictsViewModel
                compose.runOnIdle {
                    vm = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PositionConflictsViewModel(sync, context) as T
                    })[PositionConflictsViewModel::class.java]
                }
                compose.setContent { MaterialTheme { Text("Home fixture"); PositionConflicts(vm) } }
                compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.sync_choose_phone)).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithText(context.getString(R.string.sync_text)).assertExists()
                compose.onNodeWithText(context.getString(R.string.sync_narration)).assertExists()
                assertEquals(0, puts.get())
                compose.onNodeWithText(context.getString(R.string.sync_choose_phone)).performClick()
                compose.waitUntil(10_000) { puts.get() == 2 && compose.onAllNodesWithText(context.getString(R.string.sync_choose_phone)).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Home fixture").assertIsDisplayed()
                assertEquals("epubcfi(/6/2!/4/2:25)", remote.get().text.cfi)
                assertEquals(11.0, remote.get().narration.seconds!!, 0.0)
                assertEquals(30, db.pendingReadingSessionDao().all().single().durationSeconds)
                assertTrue(db.progressSyncDao().pending(7).isEmpty())
                compose.runOnIdle { compose.activity.viewModelStore.clear() }
                sync.drain()
            }
        } finally {
            compose.runOnIdle { compose.activity.viewModelStore.clear() }
            db.close()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-owner")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test fun versionElevenMigrationKeepsExistingPositionsAndQueuedSessions() = runBlocking {
        val context = compose.activity
        val name = "progress-migration-${UUID.randomUUID()}.db"
        try {
            // Existing v11 tables have unchanged layouts. Remove only the new v12 table
            // from a test-owned database to exercise Room validation of the additive migration.
            Room.databaseBuilder(context, BookOrbitDatabase::class.java, name).build().let { db ->
                db.readerProgressDao().upsert(ReaderProgressEntity(42, "exact-cfi", 25.0, 100, true, writeId = "pending-write"))
                db.pendingReadingSessionDao().upsert(PendingReadingSessionEntity("offline-session", 42, "read", 0, 30_000, 30, null, null, 7))
                db.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE progress_sync"); it.version = 11
            }
            Room.databaseBuilder(context, BookOrbitDatabase::class.java, name).addMigrations(MIGRATION_11_12).build().let { db ->
                try {
                    assertEquals("exact-cfi", db.readerProgressDao().get(42)!!.cfi)
                    assertTrue(db.readerProgressDao().get(42)!!.dirty)
                    assertEquals("pending-write", db.readerProgressDao().get(42)!!.writeId)
                    assertEquals(30, db.pendingReadingSessionDao().all().single().durationSeconds)
                    assertTrue(db.progressSyncDao().pending(7).isEmpty())
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
