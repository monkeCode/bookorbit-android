package com.bookorbit.feature.reader

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.feature.sessions.NarrationHandle
import com.bookorbit.feature.sessions.ReadingSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns narration on the service's existing player; never creates a second audio output. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativeNarrationPlayback(
    private val player: ExoPlayer,
    private val mediaSession: MediaSession,
    private val session: SessionManager,
    private val positions: ReadAlongPositionStore,
    private val activities: ReadingSessionTracker,
    private val shared: NativeNarrationSession,
    private val scope: CoroutineScope,
    private val beforePlay: () -> Unit,
    private val beforeNarration: () -> Unit,
    private val restoreAudiobook: () -> Unit,
    private val progress: com.bookorbit.core.sync.ProgressSync? = null,
) : NativeNarrationSession.Port {
    private var queue: NativeNarrationQueue? = null
    private var owner: ReadAlongOwner? = null
    private var wrapper: NativeNarrationPlayer? = null
    private var activity: NarrationHandle? = null
    private var poll: Job? = null
    private var progressLease: com.bookorbit.core.sync.ProgressLease? = null
    private var lastPosition: ReadAlongPosition? = null
    private var blockedPosition = false
    private val progressObserver = scope.launch {
        progress?.applied?.collect { change ->
            val current = queue ?: return@collect
            if (change.kind == "narration" && change.bookId == current.plan.timeline.bookId && change.point.fileId == current.plan.timeline.fileId) {
                progressLease = null
                wrapper?.pause()
                val exact = current.plan.timeline.fromServer(change.point, wrapper?.playbackParameters?.speed?.toDouble() ?: 1.0)
                if (exact == null) { blockedPosition = true; update("position"); return@collect }
                blockedPosition = false
                if (wrapper?.seekToCheckpoint(exact) == true) {
                    lastPosition = exact; progressLease = change.lease
                    progress?.acknowledge(change.lease); update()
                } else { blockedPosition = true; update("position") }
            }
        }
    }
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { update() }
        override fun onPlaybackStateChanged(playbackState: Int) { update() }
        override fun onPlayerError(error: PlaybackException) { update("audio") }
    }
    private val accountObserver = scope.launch {
        session.state.collect { queue?.let { if (!session.owns(it.plan.owner)) leave() } }
    }

    fun release() { leave(); accountObserver.cancel(); progressObserver.cancel() }

    override fun start(queue: NativeNarrationQueue, owner: ReadAlongOwner, saved: ReadAlongPosition?, section: Int) {
        require(session.owns(queue.plan.owner) && owner.fileId == queue.plan.timeline.fileId &&
            owner.server == queue.plan.owner.serverUrl && owner.userId == queue.plan.owner.userId)
        val location = queue.initialPosition(saved?.takeUnless { it.completed }, section)
            ?: error("No narrated section at this position")
        val factory = queue.sourceFactory()
        val sources = queue.items.map(factory::createMediaSource)
        leave()
        beforeNarration()
        player.pause()
        this.queue = queue
        this.owner = owner
        blockedPosition = false
        progressLease = queue.progressLease
        lastPosition = saved
        activity = activities.beginNarration(owner.fileId, queue.plan.owner)
        player.setMediaSources(sources, location.index, location.clipOffsetMs)
        val native = NativeNarrationPlayer(player, queue, { session.owns(queue.plan.owner) && !blockedPosition }, beforePlay)
        wrapper = native
        mediaSession.setPlayer(native)
        player.addListener(listener)
        player.setPlaybackSpeed((saved?.rate ?: 1.0).toFloat())
        player.prepare()
        native.play()
        update()
        poll = scope.launch {
            while (isActive) {
                if (!session.owns(queue.plan.owner)) { leave(); break }
                update()
                delay(1000)
            }
        }
    }

    override fun listenFrom(position: ReadAlongPosition): Boolean {
        val current = wrapper ?: return false
        val queue = queue ?: return false
        if (!session.owns(queue.plan.owner) || !current.seekToSelection(position)) return false
        if (current.playerError != null) current.prepare()
        current.play()
        update()
        return true
    }

    override fun command(action: String, rate: Double?) {
        val current = wrapper ?: return
        val queue = queue ?: return
        if (!session.owns(queue.plan.owner)) { leave(); return }
        if (blockedPosition && action != "stop") { update("position"); return }
        when (action) {
            "pause" -> current.pause()
            "start", "resume" -> {
                if (current.playerError != null) current.prepare()
                if (current.playbackState == Player.STATE_ENDED) {
                    queue.plan.timeline.clips.getOrNull(current.currentMediaItemIndex)?.let { current.seekToSection(it.sectionIndex) }
                }
                current.play()
            }
            "stop" -> { leave(); return }
            "restart" -> {
                queue.plan.timeline.clips.getOrNull(current.currentMediaItemIndex)?.let { current.seekToSection(it.sectionIndex) }
                current.play()
            }
            "next" -> if (current.currentMediaItemIndex < current.mediaItemCount - 1) current.seekTo(current.currentMediaItemIndex + 1, 0)
            "prev" -> if (current.currentMediaItemIndex > 0) current.seekTo(current.currentMediaItemIndex - 1, 0)
            "rate" -> if (rate != null && rate.isFinite() && rate in 0.5..2.0) current.setPlaybackSpeed(rate.toFloat())
        }
        update()
    }

    override fun seekTo(seconds: Double) {
        val current = queue ?: return
        val target = current.plan.timeline.atSeconds(seconds, wrapper?.playbackParameters?.speed?.toDouble() ?: 1.0) ?: return
        if (wrapper?.seekToCheckpoint(target) == true) update()
    }

    private fun update(error: String? = null) {
        val native = wrapper ?: return
        val queue = queue ?: return
        val owner = owner ?: return
        if (!session.owns(queue.plan.owner)) return
        val position = if (native.playerError == null && native.playbackState != Player.STATE_IDLE) native.checkpoint()?.also { lastPosition = it } else lastPosition
        if (position != null) positions.record(owner, position)
        if (position != null) progressLease?.let { lease ->
            val absolute = queue.plan.timeline.absoluteSeconds(position)
            val duration = queue.plan.timeline.durationSeconds
            val percent = if (absolute != null && duration != null && duration > 0) (absolute / duration * 100).coerceIn(0.0, 100.0) else 0.0
            progress?.record(lease, com.bookorbit.core.sync.ProgressPoint(owner.fileId, percent, seconds = position.audioSeconds,
                fragment = position.fragment, section = position.sectionIndex))
        }
        activity?.let { activities.setNarrationActive(it, native.isPlaying) }
        val state = when {
            blockedPosition || error != null || native.playerError != null -> "error"
            native.playbackState == Player.STATE_ENDED -> "ended"
            native.playbackState == Player.STATE_BUFFERING -> "loading"
            native.isPlaying -> "playing"
            else -> "paused"
        }
        shared.publish(NativeNarrationSession.State(owner, queue.plan.timeline.bookId,
            ReaderEvent.ReadAlong(true, state, native.playbackParameters.speed.toDouble(), if (blockedPosition) "position" else error, position), queue.title,
            position?.let(queue.plan.timeline::absoluteSeconds), queue.plan.timeline.durationSeconds))
    }

    override fun leave() {
        if (wrapper == null && queue == null) return
        update()
        poll?.cancel(); poll = null
        player.removeListener(listener)
        player.pause()
        wrapper?.detach(); wrapper = null
        activity?.let(activities::endNarration); activity = null
        val released = queue
        queue = null; owner = null
        progressLease = null; lastPosition = null
        blockedPosition = false
        player.clearMediaItems()
        released?.plan?.close()
        shared.publish(null)
        restoreAudiobook()
    }
}
