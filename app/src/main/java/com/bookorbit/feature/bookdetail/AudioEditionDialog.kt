package com.bookorbit.feature.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bookorbit.R
import com.bookorbit.feature.reader.NarratedEdition

/** An existing Listen action opens choices; rendering/discovery never starts MP3 playback. */
@Composable
internal fun AudioEditionDialog(
    hasAudiobook: Boolean, editions: List<NarratedEdition>, loading: Boolean, starting: Boolean,
    failed: Boolean, onAudiobook: () -> Unit, onNarration: (NarratedEdition) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = { if (!starting) onDismiss() },
        title = { Text(stringResource(R.string.narration_editions)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasAudiobook) TextButton(enabled = !starting, onClick = onAudiobook) { Text(stringResource(R.string.sync_audio)) }
            editions.forEach { edition -> TextButton(enabled = !starting && !loading, onClick = { onNarration(edition) }) {
                Text(stringResource(R.string.narration_edition, edition.fileId))
            } }
            if (loading || starting) CircularProgressIndicator()
            else if (editions.isEmpty()) Text(stringResource(R.string.narration_no_editions))
            if (failed) Text(stringResource(R.string.player_err_load), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !starting) { Text(stringResource(R.string.cancel)) } },
    )
}
