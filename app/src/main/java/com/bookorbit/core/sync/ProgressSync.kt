package com.bookorbit.core.sync

import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.auth.SessionOwner
import com.bookorbit.core.db.*
import com.bookorbit.core.model.*
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.network.AudiobookAssetResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ProgressPoint(val fileId: Int, val percentage: Double = 0.0, val cfi: String? = null,
    val page: Int? = null, val seconds: Double? = null, val fragment: String? = null, val section: Int? = null,
    val stamp: String? = null, val revision: Int? = null) {
    fun samePlace(other: ProgressPoint): Boolean = fileId == other.fileId && when {
        seconds != null || other.seconds != null -> seconds != null && other.seconds != null &&
            kotlin.math.abs(seconds - other.seconds) < 0.001 && fragment == other.fragment && section == other.section
        cfi != null || other.cfi != null -> cfi == other.cfi
        page != null || other.page != null -> page == other.page
        else -> kotlin.math.abs(percentage - other.percentage) < 0.000001
    }
}

data class ProgressLease(val key: String, val epoch: Long, val owner: SessionOwner)
data class LoadedPosition(val point: ProgressPoint, val lease: ProgressLease)
data class PositionApplied(val key: String, val kind: String, val bookId: Int, val point: ProgressPoint, val lease: ProgressLease)

/** Local writes never await HTTP. Only this coordinator may upload production positions. */
@Singleton
class ProgressSync @Inject constructor(
    private val dao: ProgressSyncDao,
    private val textCache: ReaderProgressDao,
    private val audioCache: AudioProgressDao,
    private val api: ApiService,
    private val assets: AudiobookAssetResolver,
    private val session: SessionManager,
    private val scheduler: SyncScheduler,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val network = Mutex()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableApplied = MutableSharedFlow<PositionApplied>(extraBufferCapacity = 32)
    val applied = mutableApplied.asSharedFlow()
    private val failedStorage = kotlinx.coroutines.flow.MutableStateFlow(false)
    val storageFailed = failedStorage.asStateFlow()
    val owners get() = session.owners
    fun conflicts(user: Int) = dao.conflicts(user)
    fun decode(value: String): ProgressPoint = json.decodeFromString(value)
    private fun encode(value: ProgressPoint) = json.encodeToString(ProgressPoint.serializer(), value)
    private fun current(owner: SessionOwner) = session.owns(owner) && !session.dataScopeChanged()
    fun canDisplay(owner: SessionOwner) = current(owner)

    init { scope.launch {
        for (write in writes) while (isActive) {
            try { write(); failedStorage.value = false; break }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failedStorage.value = true; delay(1000) }
        }
    } }

    suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }; done.await()
    }

    /** Resolve before enabling a new writer. Unknown/missing baselines never authorize an upload. */
    suspend fun open(kind: String, bookId: Int, fileId: Int, title: String, fallback: ProgressPoint): LoadedPosition {
        val owner = checkNotNull(session.owner()); check(current(owner))
        require(kind in setOf("text", "audio", "narration") && bookId > 0)
        drain()
        val key = "${owner.userId}:$kind:${if (kind == "audio") bookId else fileId}"
        lock.withLock {
            if (dao.get(key) == null) {
                val mayImport = dao.knownOwners(kind, bookId, fileId).isEmpty()
                val oldText = if (kind == "text" && mayImport) textCache.get(fileId) else null
                val oldAudio = if (kind == "audio" && mayImport) audioCache.get(bookId) else null
                val local = oldText?.let { ProgressPoint(fileId, it.percentage, cfi = it.cfi, page = it.pageNumber) }
                    ?: oldAudio?.let { ProgressPoint(it.currentFileId, it.percentage, seconds = it.positionSeconds) } ?: fallback
                dao.put(ProgressSyncEntry(key, owner.userId, bookId, fileId, kind, title, encode(local), null, null,
                    oldText?.dirty == true || oldAudio?.dirty == true || (kind == "narration" && fallback.fragment != null), false, 0, UUID.randomUUID().toString()))
            } else dao.get(key)?.let { dao.put(it.copy(title = title)) }
        }
        val before = checkNotNull(dao.get(key))
        val remote = try { withTimeout(4000) { fetch(before, owner) } }
            catch (e: TimeoutCancellationException) { null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        return lock.withLock {
            check(current(owner))
            var row = checkNotNull(dao.get(key))
            if (remote != null) row = reconcile(row, remote)
            row = row.copy(awaitingApply = false)
            store(row)
            LoadedPosition(decode(row.local), ProgressLease(key, row.epoch, owner))
        }.also { scheduler.schedule() }
    }

    /** App-owned serial writer survives ViewModel disposal and never waits on a network mutex. */
    fun record(lease: ProgressLease, point: ProgressPoint) {
        require(point.fileId > 0 && point.percentage.isFinite() && point.percentage in 0.0..100.0)
        require(point.seconds == null || point.seconds.isFinite() && point.seconds >= 0)
        writes.trySend {
            lock.withLock {
                if (!current(lease.owner)) return@withLock
                val row = dao.get(lease.key) ?: return@withLock
                if (row.epoch != lease.epoch || row.awaitingApply || decode(row.local).samePlace(point)) return@withLock
                store(row.copy(local = encode(point.copy(stamp = Instant.now().toString())), dirty = true, writeId = UUID.randomUUID().toString()))
                scheduler.schedule()
            }
        }
    }

    private suspend fun store(row: ProgressSyncEntry) {
        dao.put(row)
        val value = decode(row.local)
        val time = value.stamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
        if (row.kind == "text") textCache.upsert(ReaderProgressEntity(row.fileId, value.cfi, value.percentage, time, row.dirty, value.page, row.writeId))
        if (row.kind == "audio") audioCache.upsert(AudioProgressEntity(row.bookId, value.fileId, value.seconds ?: 0.0, value.percentage, time, row.dirty, row.writeId))
    }

    private fun reconcile(row: ProgressSyncEntry, remote: ProgressPoint): ProgressSyncEntry {
        val local = decode(row.local)
        val same = local.samePlace(remote)
        if (!row.dirty || same) return row.copy(local = encode(remote), baseline = encode(remote), remote = encode(remote), dirty = false, conflict = false,
            epoch = row.epoch + if (!same) 1 else 0)
        val base = row.baseline?.let(::decode)
        val changedElsewhere = base == null || !base.samePlace(remote)
        return row.copy(remote = encode(remote), conflict = changedElsewhere)
    }

    /** One foreground decision covers every conflicting coordinate of this book, at pinned snapshots. */
    suspend fun choose(rows: List<ProgressSyncEntry>, useLocal: Boolean): Boolean {
        val owner = session.owner() ?: return false
        require(rows.isNotEmpty() && rows.map { it.bookId }.distinct().size == 1 && rows.all { it.userId == owner.userId })
        drain()
        // Re-fetch every coordinate before committing a choice; never authorize a stale dialog.
        val remote = rows.associate { it.key to fetch(it, owner) }
        val events = lock.withLock {
            if (!current(owner)) return@withLock null
            val currentRows = rows.map { checkNotNull(dao.get(it.key)) }
            val conflictKeys = dao.pending(owner.userId).filter { it.bookId == rows.first().bookId && it.conflict }.map { it.key }.toSet()
            if (conflictKeys != rows.map { it.key }.toSet() || rows.zip(currentRows).any { (old, now) -> now.epoch != old.epoch || old.remote == null || !decode(old.remote).samePlace(remote.getValue(old.key)) }) {
                rows.forEach { old -> dao.get(old.key)?.let { store(reconcile(it, remote.getValue(old.key))) } }
                return@withLock null
            }
            // Database lock serializes record and choices. Persist all decisions before publishing.
            val chosen = currentRows.map { old ->
                val point = remote.getValue(old.key)
                old.copy(local = if (useLocal) encode(decode(old.local).copy(stamp = Instant.now().toString())) else encode(point), baseline = encode(point), remote = encode(point),
                    dirty = useLocal, conflict = false, epoch = old.epoch + if (useLocal) 0 else 1,
                    writeId = UUID.randomUUID().toString(), awaitingApply = !useLocal)
            }
            if (!dao.replaceGroup(currentRows, chosen)) return@withLock null
            chosen.forEach { store(it) }
            if (useLocal) emptyList() else chosen.map { row -> PositionApplied(row.key, row.kind, row.bookId, decode(row.local), ProgressLease(row.key, row.epoch, owner)) }
        } ?: return false
        events.forEach { mutableApplied.emit(it) }
        scheduler.schedule()
        return true
    }

    suspend fun acknowledge(lease: ProgressLease) = lock.withLock {
        val row = dao.get(lease.key) ?: return@withLock
        if (current(lease.owner) && row.epoch == lease.epoch) dao.put(row.copy(awaitingApply = false))
    }

    suspend fun isPending(lease: ProgressLease): Boolean = dao.get(lease.key)?.dirty == true

    suspend fun local(kind: String, id: Int): LoadedPosition? {
        val owner = session.owner()?.takeIf(::current) ?: return null
        val key = "${owner.userId}:$kind:$id"
        val row = dao.get(key) ?: return null
        if (!current(owner)) return null
        return LoadedPosition(decode(row.local), ProgressLease(key, row.epoch, owner))
    }

    suspend fun flushPending(): Boolean = network.withLock {
        drain()
        val owner = session.owner()?.takeIf(::current) ?: return@withLock false
        var retry = false
        val inspectedBooks = mutableSetOf<Int>()
        // Compare a complete book before publishing its conflicts. Network latency between
        // EPUB/audio/narration requests must not produce three successive dialogs.
        for ((bookId, group) in dao.pending(owner.userId).groupBy { it.bookId }) {
            try {
                val remotes = group.associate { it.key to fetch(it, owner) }
                val inspected = lock.withLock {
                    if (!current(owner)) return@withLock false
                    val now = dao.pending(owner.userId).filter { it.bookId == bookId }
                    if (now.map { it.key }.toSet() != group.map { it.key }.toSet()) return@withLock false
                    if (now.any { value -> group.single { it.key == value.key }.let { it.epoch != value.epoch || it.baseline != value.baseline } }) return@withLock false
                    val checked = now.map { reconcile(it, remotes.getValue(it.key)) }
                    if (!dao.replaceGroup(now, checked)) return@withLock false
                    checked.forEach { store(it) }
                    true
                }
                if (inspected) inspectedBooks += bookId else retry = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
        }
        for (candidate in dao.pending(owner.userId)) {
            if (!current(owner)) break
            if (candidate.bookId !in inspectedBooks) continue
            if (candidate.conflict || candidate.awaitingApply) continue
            try {
                val remote = fetch(candidate, owner)
                val ready = lock.withLock {
                    if (!current(owner)) return@withLock null
                    val row = dao.get(candidate.key) ?: return@withLock null
                    if (row.epoch != candidate.epoch || row.baseline != candidate.baseline) { retry = true; return@withLock null }
                    val checked = reconcile(row, remote)
                    store(checked)
                    checked.takeIf { it.dirty && !it.conflict && !it.awaitingApply }
                } ?: continue
                val saved = send(ready, remote, owner)
                lock.withLock {
                    if (!current(owner)) return@withLock
                    val now = dao.get(ready.key) ?: return@withLock
                    if (now.epoch != ready.epoch || now.baseline != ready.baseline) { retry = true; return@withLock }
                    if (saved.samePlace(decode(ready.local))) {
                        store(now.copy(baseline = encode(saved), remote = encode(saved), dirty = now.writeId != ready.writeId, conflict = false))
                    } else {
                        // Superseded writes/CAS rejection do not authorize a retry over newer state.
                        store(reconcile(now, saved)); retry = retry || !checkNotNull(dao.get(now.key)).conflict
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
        }
        retry || (current(owner) && dao.pending(owner.userId).any { !it.conflict && !it.awaitingApply })
    }

    private suspend fun fetch(row: ProgressSyncEntry, owner: SessionOwner): ProgressPoint {
        check(current(owner))
        val point = if (row.kind == "audio") {
            when (val sources = assets.forBook(row.bookId)) {
                AudiobookAssetResolver.Sources.Legacy -> {
                    val value = api.getAudioProgress(row.bookId, owner)
                    value?.let { ProgressPoint(it.currentFileId, it.percentage, seconds = it.positionSeconds, stamp = it.updatedAt) }
                        ?: ProgressPoint(decode(row.local).fileId, seconds = 0.0)
                }
                is AudiobookAssetResolver.Sources.Assets -> {
                    val raw = api.getPlaybackState(row.bookId, owner).use { it.string() }.trim()
                    val value = raw.takeIf { it.isNotEmpty() && it != "null" }?.let { json.decodeFromString<AudiobookPlaybackState>(it) }
                    if (value == null) ProgressPoint(decode(row.local).fileId, seconds = 0.0, revision = 0)
                    else ProgressPoint(checkNotNull(sources.fileIdFor(value.assetId)), value.percentage, seconds = value.positionMs / 1000.0, stamp = value.capturedAt, revision = value.revision)
                }
            }
        } else {
            val value = api.readingPositions(row.fileId, owner).items.single { it.fileId == row.fileId }
            if (row.kind == "text") ProgressPoint(row.fileId, value.percentage, value.text.cfi, value.text.page, stamp = value.updatedAt)
            else ProgressPoint(row.fileId, value.narration.percentage ?: 0.0, seconds = value.narration.seconds,
                fragment = value.narration.fragment, section = value.narration.sectionIndex, stamp = value.updatedAt)
        }
        check(current(owner)); return point
    }

    private suspend fun send(row: ProgressSyncEntry, remote: ProgressPoint, owner: SessionOwner): ProgressPoint {
        check(current(owner))
        val point = decode(row.local)
        if (row.kind == "audio") {
            val sources = assets.forBook(row.bookId)
            if (sources !is AudiobookAssetResolver.Sources.Assets) error("Legacy audio API has no conditional write; local progress retained")
            val asset = checkNotNull(sources.assetIds[point.fileId])
            val result = try { api.putPlaybackState(row.bookId, PutAudiobookPlaybackState(asset,
                ((point.seconds ?: 0.0) * 1000).toLong(), point.stamp ?: Instant.now().toString(), row.writeId,
                remote.revision ?: 0, sources.manifestRevision), owner) }
            catch (e: retrofit2.HttpException) {
                if (e.code() == 409) return fetch(row, owner)
                if (e.code() == 412) assets.forBook(row.bookId, refresh = true)
                throw e
            }
            check(current(owner))
            return point.copy(revision = result.revision)
        }
        val write = ReadingPositionWrite(row.fileId, row.kind, point.percentage, remote.stamp ?: Instant.EPOCH.toString(),
            text = ReadingTextPosition(point.cfi, point.page).takeIf { row.kind == "text" },
            narration = NarrationPosition(point.seconds, point.fragment, point.section).takeIf { row.kind == "narration" })
        val result = api.writeReadingPositions(ReadingPositionWrites(listOf(write)), owner).results.single { it.fileId == row.fileId }
        check(result.status in setOf("saved", "superseded") && current(owner))
        val value = result.progress
        return if (row.kind == "text") ProgressPoint(row.fileId, value.percentage, value.text.cfi, value.text.page, stamp = value.updatedAt)
        else ProgressPoint(row.fileId, value.narration.percentage ?: 0.0, seconds = value.narration.seconds,
            fragment = value.narration.fragment, section = value.narration.sectionIndex, stamp = value.updatedAt)
    }
}
