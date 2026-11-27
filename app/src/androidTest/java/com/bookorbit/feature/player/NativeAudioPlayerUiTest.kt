package com.bookorbit.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookorbit.R
import com.bookorbit.feature.reader.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeAudioPlayerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun embeddedAudioControlsUseTheExistingNarrationPortWithoutOpeningAReader() {
        val shared = NativeNarrationSession()
        val owner = ReadAlongOwner("https://synthetic.example", 7, 84, "synthetic-revision")
        val actions = mutableListOf<String>()
        val seeks = mutableListOf<Double>()
        val port = object : NativeNarrationSession.Port {
            override fun start(queue: NativeNarrationQueue, owner: ReadAlongOwner, saved: ReadAlongPosition?, section: Int) { fail("UI must not open a second queue") }
            override fun command(action: String, rate: Double?) { actions += action }
            override fun leave() { }
            override fun seekTo(seconds: Double) { seeks += seconds }
        }
        compose.runOnIdle {
            shared.bind(port)
            shared.publish(NativeNarrationSession.State(owner, 5, ReaderEvent.ReadAlong(true, "playing"), "Synthetic narrated EPUB", 30.0, 60.0))
        }
        compose.setContent { MaterialTheme {
            val state by shared.state.collectAsStateWithLifecycle()
            state?.let { NativeAudioPlayer(it, shared, {}, cover = null) }
        } }
        compose.onNodeWithText("Synthetic narrated EPUB").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_pause)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.narration_skip_back)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.narration_skip_forward)).performClick()
        compose.onNodeWithText("1.25×").performClick()
        compose.runOnIdle {
            assertEquals(listOf("pause", "rate"), actions)
            assertEquals(listOf(15.0, 45.0), seeks)
            shared.unbind(port)
        }
    }
}
