package com.bookorbit.feature.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import java.util.UUID

/** A new identity even when the same book and source files are loaded again. */
internal object ProgressQueue {
    const val EXTRA = "com.bookorbit.progressQueue"
    fun id(): String = UUID.randomUUID().toString()
    fun tag(items: List<MediaItem>, id: String): List<MediaItem> = items.map { item ->
        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(
            Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString(EXTRA, id) },
        ).build()).build()
    }
}
