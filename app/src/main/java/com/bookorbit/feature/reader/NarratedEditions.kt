package com.bookorbit.feature.reader

import android.content.Context
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.auth.SessionOwner
import com.bookorbit.core.model.BookDetail
import com.bookorbit.core.model.BookFileRef
import com.bookorbit.core.storage.openInputStream
import com.bookorbit.core.sync.ProgressSync
import com.bookorbit.feature.player.PlayerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class NarratedEdition(val fileId: Int, val fingerprint: String, val duration: Double?)

/** Discovers actual SMIL playlists, not audio editions guessed from the EPUB extension. */
@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NarratedEditions @Inject constructor(private val source: ReaderSource, private val local: LocalNativeNarration,
    private val streaming: NativeNarrationStreaming, private val positions: ReadAlongPositionStore,
    private val shared: NativeNarrationSession, private val player: PlayerManager, private val session: SessionManager,
    private val progress: ProgressSync, @ApplicationContext private val context: Context) {

    private suspend fun open(bookId: Int, file: BookFileRef, account: SessionOwner): Pair<ReadAlongOwner, NativeNarrationSource> {
        check(session.owns(account))
        val available = source.availableFile(bookId, file)
        val fingerprint = if (available != null) withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            available.ref.openInputStream(context).use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) { coroutineContext.ensureActive(); val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } else "stream-v1:${checkNotNull(file.contentVersion?.takeIf { it.isNotBlank() })}:${file.fileHash.orEmpty()}:${file.sizeBytes}"
        val owner = ReadAlongOwner(account.serverUrl, account.userId, file.id, fingerprint)
        return owner to if (available != null) local.open(available.ref, bookId, owner, account) else streaming.open(bookId, file.id, account)
    }

    suspend fun discover(book: BookDetail): List<NarratedEdition> {
        val account = checkNotNull(session.owner())
        return book.files.filter { it.format.equals("epub", true) }.mapNotNull { file ->
            try {
                val (owner, plan) = open(book.id, file, account)
                try { NarratedEdition(file.id, owner.fingerprint, plan.timeline.durationSeconds) } finally { plan.close() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    suspend fun play(book: BookDetail, edition: NarratedEdition) = withContext(Dispatchers.Main.immediate) {
        val account = checkNotNull(session.owner())
        shared.state.value?.takeIf { it.bookId == book.id && it.owner.fileId == edition.fileId && it.owner.fingerprint == edition.fingerprint && it.event.error != "position" }?.let {
            shared.command(it.owner, "resume"); return@withContext
        }
        val ticket = shared.ticket()
        val (owner, plan) = open(book.id, book.files.single { it.id == edition.fileId }, account)
        var transferred = false
        try {
            check(owner.fingerprint == edition.fingerprint && session.owns(account))
            val (saved, lease) = progress.openNarration(plan, book.title.orEmpty(), positions.load(owner))
            if (!player.prepareForNativeNarration { shared.isCurrent(ticket) && session.owns(account) && !player.state.value.isCasting }) return@withContext
            val queue = NativeNarrationQueue(plan, book.title.orEmpty(), progressLease = lease)
            transferred = shared.start(ticket, queue, owner, saved, 0)
            check(transferred)
        } finally { if (!transferred) plan.close() }
    }
}
