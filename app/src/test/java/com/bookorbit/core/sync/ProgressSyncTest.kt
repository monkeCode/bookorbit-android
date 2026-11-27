package com.bookorbit.core.sync

import com.bookorbit.core.auth.*
import com.bookorbit.core.db.*
import com.bookorbit.core.model.*
import com.bookorbit.core.network.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ProgressSyncTest {
    private class Dao : ProgressSyncDao {
        val rows = ConcurrentHashMap<String, ProgressSyncEntry>()
        private val state = MutableStateFlow<List<ProgressSyncEntry>>(emptyList())
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(value: ProgressSyncEntry) { rows[value.key] = value; state.value = rows.values.toList() }
        override suspend fun pending(userId: Int) = rows.values.filter { it.userId == userId && it.dirty }
        override suspend fun knownOwners(kind: String, bookId: Int, fileId: Int) = rows.values.filter { it.kind == kind && it.bookId == bookId && it.fileId == fileId }.map { it.userId }.distinct()
        override fun conflicts(userId: Int) = state.map { all -> all.filter { it.userId == userId && it.conflict } }
    }
    private class Fixture {
        val dao = Dao()
        val textCache = mockk<ReaderProgressDao>(relaxed = true)
        val audioCache = mockk<AudioProgressDao>(relaxed = true)
        val session = SessionManager(mockk<SecureStorage>(relaxed = true), Json).apply {
            setServerUrl("https://synthetic.example"); signIn("synthetic", AuthUser(1, "fixture"))
        }
        val api = mockk<ApiService>()
        val assets = mockk<AudiobookAssetResolver>()
        val scheduler = mockk<SyncScheduler>(relaxed = true)
        val sync = ProgressSync(dao, textCache, audioCache, api, assets, session, scheduler)
        var server = ReadingPositionEntry(42, 20.0, "2026-11-27T00:00:00Z", ReadingTextPosition("epubcfi(/6/2!/4/2:20)"))
        var audio = AudiobookPlaybackState("asset-a", 100_000, 10.0, "2026-11-27T00:00:00Z", 1)
        var offline = false
        var writes = 0
        var hook: (suspend () -> Unit)? = null
        init {
            coEvery { textCache.get(any()) } returns null
            coEvery { audioCache.get(any()) } returns null
            coEvery { api.readingPositions(any(), any()) } coAnswers {
                if (offline) throw IOException("synthetic offline")
                ReadingPositionList(listOf(server.copy(fileId = firstArg())))
            }
            coEvery { api.writeReadingPositions(any(), any()) } coAnswers {
                writes++
                hook?.also { hook = null }?.invoke()
                val request = firstArg<ReadingPositionWrites>().writes.single()
                val superseded = server.updatedAt!! > request.editedAt
                if (!superseded) {
                    server = if (request.kind == "text") server.copy(percentage = request.percentage, text = request.text!!)
                    else server.copy(narration = request.narration!!.copy(percentage = request.percentage))
                }
                ReadingPositionResults(listOf(ReadingPositionResult(request.fileId, if (superseded) "superseded" else "saved", server.copy(fileId = request.fileId))))
            }
            coEvery { assets.forBook(any(), any()) } returns AudiobookAssetResolver.Sources.Assets(mapOf(100 to "asset-a"), "manifest-1")
            coEvery { api.getPlaybackState(any(), any()) } answers { Json.encodeToString(AudiobookPlaybackState.serializer(), audio).toResponseBody() }
            coEvery { api.putPlaybackState(any(), any(), any()) } coAnswers {
                val request = secondArg<PutAudiobookPlaybackState>()
                assertEquals(audio.revision, request.baseRevision)
                audio = audio.copy(positionMs = request.positionMs, capturedAt = request.capturedAt, revision = audio.revision + 1)
                audio
            }
        }
        suspend fun open() = sync.open("text", 7, 42, "Synthetic book", ProgressPoint(42))
        fun point(number: Int) = ProgressPoint(42, number.toDouble(), cfi = "epubcfi(/6/2!/4/2:$number)")
        fun moveServer(number: Int) { server = server.copy(percentage = number.toDouble(), text = ReadingTextPosition("epubcfi(/6/2!/4/2:$number)"), updatedAt = "2026-11-27T00:00:01Z") }
    }

    @Test fun `opening and restoring a position never writes it back`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        assertEquals(f.server.text.cfi, initial.point.cfi)
        f.sync.record(initial.lease, initial.point); f.sync.drain(); f.sync.flushPending()
        assertEquals(0, f.writes); assertFalse(f.dao.rows.values.single().dirty)
    }

    @Test fun `local actor keeps saving while a network preflight is stuck`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(21)); f.sync.drain()
        val waiting = CompletableDeferred<Unit>()
        coEvery { f.api.readingPositions(any(), any()) } coAnswers { waiting.complete(Unit); awaitCancellation() }
        val upload = launch(Dispatchers.Default) { f.sync.flushPending() }
        withTimeout(3000) { waiting.await() }
        f.sync.record(initial.lease, f.point(22)); withTimeout(3000) { f.sync.drain() }
        assertEquals(22.0, f.sync.decode(f.dao.rows.values.single().local).percentage, 0.0)
        assertTrue(f.dao.rows.values.single().dirty)
        upload.cancelAndJoin()
    }

    @Test fun `offline progress uploads only after a fresh unchanged server read`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.offline = true; f.sync.record(initial.lease, f.point(25)); f.sync.drain()
        assertTrue(f.sync.flushPending()); assertEquals(0, f.writes)
        f.offline = false; f.sync.flushPending()
        assertEquals(1, f.writes); assertEquals(f.point(25).cfi, f.server.text.cfi)
        assertFalse(f.dao.rows.values.single().dirty)
    }

    @Test fun `parallel edits stay local without overwriting either side`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(25)); f.sync.drain(); f.moveServer(30)
        f.sync.flushPending()
        val conflict = f.dao.rows.values.single()
        assertTrue(conflict.conflict); assertEquals(25.0, f.sync.decode(conflict.local).percentage, 0.0)
        assertEquals(30.0, f.sync.decode(conflict.remote!!).percentage, 0.0); assertEquals(0, f.writes)
    }

    @Test fun `explicit phone choice can move backwards and is asked only once`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(10)); f.sync.drain(); f.moveServer(30); f.sync.flushPending()
        assertTrue(f.sync.choose(f.dao.rows.values.toList(), true))
        f.sync.flushPending(); f.sync.flushPending()
        assertEquals(f.point(10).cfi, f.server.text.cfi); assertEquals(1, f.writes)
        assertFalse(f.dao.rows.values.single().conflict)
    }

    @Test fun `server choice fences every old writer and waits for actual application`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(25)); f.sync.drain(); f.moveServer(30); f.sync.flushPending()
        val event = async(start = CoroutineStart.UNDISPATCHED) { f.sync.applied.first() }
        assertTrue(f.sync.choose(f.dao.rows.values.toList(), false))
        val accepted = withTimeout(3000) { event.await() }
        f.sync.record(initial.lease, f.point(99)); f.sync.record(accepted.lease, f.point(98)); f.sync.drain()
        assertEquals(f.point(30).cfi, f.sync.decode(f.dao.rows.values.single().local).cfi)
        f.sync.acknowledge(accepted.lease)
        f.sync.record(initial.lease, f.point(97)); f.sync.record(accepted.lease, f.point(31)); f.sync.drain()
        assertEquals(f.point(31).cfi, f.sync.decode(f.dao.rows.values.single().local).cfi)
        assertEquals(0, f.writes)
    }

    @Test fun `a server change while the dialog is open requires a new choice`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(25)); f.sync.drain(); f.moveServer(30); f.sync.flushPending()
        val shown = f.dao.rows.values.toList()
        f.moveServer(40)
        assertFalse(f.sync.choose(shown, true)); assertTrue(f.dao.rows.values.single().conflict)
        assertEquals(40.0, f.sync.decode(f.dao.rows.values.single().remote!!).percentage, 0.0)
        assertEquals(0, f.writes)
    }

    @Test fun `a superseded conditional write does not overwrite a race winner`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(25)); f.sync.drain()
        f.hook = { f.moveServer(40) }
        f.sync.flushPending()
        assertEquals(f.point(40).cfi, f.server.text.cfi); assertTrue(f.dao.rows.values.single().conflict)
        assertEquals(1, f.writes)
    }

    @Test fun `a newer local checkpoint survives an in flight acknowledgement`() = runBlocking {
        val f = Fixture(); val initial = f.open()
        f.sync.record(initial.lease, f.point(25)); f.sync.drain()
        f.hook = { f.sync.record(initial.lease, f.point(26)); f.sync.drain() }
        f.sync.flushPending()
        assertTrue(f.dao.rows.values.single().dirty)
        assertEquals(f.point(26).cfi, f.sync.decode(f.dao.rows.values.single().local).cfi)
        f.sync.flushPending(); assertFalse(f.dao.rows.values.single().dirty); assertEquals(2, f.writes)
    }

    @Test fun `one book decision resolves text narration and audiobook conflicts together`() = runBlocking {
        val f = Fixture(); val text = f.open()
        f.server = f.server.copy(narration = NarrationPosition(10.0, "chapter#one", 0, 10.0))
        val narration = f.sync.open("narration", 7, 42, "Synthetic book", ProgressPoint(42))
        val audio = f.sync.open("audio", 7, 0, "Synthetic book", ProgressPoint(100, seconds = 0.0))
        f.sync.record(text.lease, f.point(25))
        f.sync.record(narration.lease, ProgressPoint(42, 11.0, seconds = 11.0, fragment = "chapter#one", section = 0))
        f.sync.record(audio.lease, ProgressPoint(100, 11.0, seconds = 101.0)); f.sync.drain()
        f.moveServer(30); f.server = f.server.copy(narration = NarrationPosition(12.0, "chapter#one", 0, 12.0))
        f.audio = f.audio.copy(positionMs = 102_000, revision = 2)
        f.sync.flushPending()
        val conflicts = f.dao.rows.values.filter { it.conflict }
        assertEquals(3, conflicts.size); assertEquals(1, conflicts.groupBy { it.bookId }.size)
        assertTrue(f.sync.choose(conflicts, true)); f.sync.flushPending()
        assertTrue(f.dao.rows.values.none { it.conflict || it.dirty })
    }

    @Test fun `unknown baseline after offline upgrade never authorizes a blind overwrite`() = runBlocking {
        val f = Fixture(); f.offline = true
        coEvery { f.textCache.get(42) } returns ReaderProgressEntity(42, f.point(20).cfi, 20.0, 1, true)
        val initial = f.open(); assertEquals(f.point(20).cfi, initial.point.cfi)
        f.offline = false; f.moveServer(30); f.sync.flushPending()
        assertTrue(f.dao.rows.values.single().conflict); assertEquals(0, f.writes)
    }

    @Test fun `logout invalidates active position writers and prevents delivery`() = runBlocking {
        val f = Fixture(); val initial = f.open(); f.session.signOut()
        f.sync.record(initial.lease, f.point(90)); f.sync.drain(); f.sync.flushPending()
        assertFalse(f.dao.rows.values.single().dirty); assertEquals(0, f.writes)
    }

    @Test fun `another account cannot inherit a known owners mirrored cache`() = runBlocking {
        val f = Fixture(); f.open()
        coEvery { f.textCache.get(42) } returns ReaderProgressEntity(42, f.point(80).cfi, 80.0, 1, true)
        f.session.signIn("other", AuthUser(2, "other")); f.offline = true
        val next = f.open()
        assertNull(next.point.cfi); assertEquals(0.0, next.point.percentage, 0.0)
        assertEquals(2, f.dao.rows.size)
    }
}
