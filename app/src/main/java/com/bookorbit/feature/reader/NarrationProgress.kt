package com.bookorbit.feature.reader

import com.bookorbit.core.sync.ProgressPoint
import com.bookorbit.core.sync.ProgressSync
import com.bookorbit.core.sync.ProgressLease

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun ProgressSync.openNarration(plan: NativeNarrationSource, title: String, saved: ReadAlongPosition?, explicitStart: Boolean = false): Pair<ReadAlongPosition?, ProgressLease> {
    val timeline = plan.timeline
    val duration = timeline.durationSeconds
    val absolute = saved?.let(timeline::absoluteSeconds)
    val percent = if (duration != null && duration > 0 && absolute != null) (absolute / duration * 100).coerceIn(0.0, 100.0) else 0.0
    val fallback = ProgressPoint(timeline.fileId, percent, seconds = saved?.audioSeconds, fragment = saved?.fragment, section = saved?.sectionIndex)
    val loaded = open("narration", timeline.bookId, timeline.fileId, title, fallback)
    val position = if (loaded.point.fragment == null && loaded.point.seconds == null) null
        else timeline.fromServer(loaded.point, saved?.rate ?: 1.0)
            ?: if (explicitStart) null else error("Narration marker does not resolve in this exact EPUB revision")
    return position to loaded.lease
}
