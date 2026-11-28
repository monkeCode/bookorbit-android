package com.bookorbit.feature.bookdetail

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.bookorbit.R
import com.bookorbit.feature.reader.NarratedEdition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioEditionDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mixedBookWaitsForExplicitChoiceAndCanSelectReadalongInsteadOfMp3() {
        var mp3 = 0
        var epub: Int? = null
        compose.setContent { MaterialTheme {
            AudioEditionDialog(true, listOf(NarratedEdition(84, "stream-v2:fixture", 10.0)), false, false, false,
                onAudiobook = { mp3++ }, onNarration = { epub = it.fileId }, onDismiss = {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.narration_editions)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_audio)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, mp3); assertNull(epub) }
        compose.onNodeWithText(compose.activity.getString(R.string.narration_edition, 84)).performClick()
        compose.runOnIdle { assertEquals(84, epub); assertEquals(0, mp3) }
    }
}
