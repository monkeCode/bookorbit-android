package com.bookorbit.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class ProgressQueueTest {
    @Test fun reloadingIdenticalFilesDoesNotAcceptThePreviousQueueGeneration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            val wrapper = BookAggregatingPlayer(player, MutableStateFlow(AudioSettings()))
            try {
                val source = listOf(MediaItem.Builder().setMediaId("42").setUri("file:///synthetic-not-opened.wav").build())
                val first = ProgressQueue.id()
                val second = ProgressQueue.id()
                assertNotEquals(first, second)
                player.setMediaItems(ProgressQueue.tag(source, first))
                assertEquals(first, wrapper.progressQueueId())
                // During an asynchronous reload, file identity is unchanged but the old clock
                // must not qualify for the new reporting generation or its error cache.
                assertNotEquals(second, wrapper.progressQueueId())
                player.setMediaItems(ProgressQueue.tag(source, second))
                assertEquals(second, wrapper.progressQueueId())
                assertEquals("42", wrapper.currentMediaItem?.mediaId)
            } finally { wrapper.detach(); player.release() }
        }
    }
}
