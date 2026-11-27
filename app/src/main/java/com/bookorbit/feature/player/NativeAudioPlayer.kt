package com.bookorbit.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bookorbit.R
import com.bookorbit.feature.reader.NativeNarrationSession
import com.bookorbit.ui.LocalImageUrls

/** Audio-only surface over the exact same service/SMIL queue used by Listen from here. */
@Composable
fun NativeAudioPlayer(state: NativeNarrationSession.State, shared: NativeNarrationSession, onBack: () -> Unit,
    cover: Any? = LocalImageUrls.current.cover(state.bookId)) {
    var scrubbing by remember(state.owner) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.narration_edition, state.owner.fileId), style = MaterialTheme.typography.titleMedium)
        }
        AsyncImage(cover, contentDescription = state.title,
            contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxWidth())
        Text(state.title, style = MaterialTheme.typography.headlineSmall)
        Text(formatTime(state.seconds ?: 0.0) + state.duration?.let { " / ${formatTime(it)}" }.orEmpty())
        state.duration?.takeIf { it > 0 }?.let { duration ->
            Slider(value = scrubbing ?: (state.seconds ?: 0.0).coerceIn(0.0, duration).toFloat(),
                onValueChange = { scrubbing = it }, valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = { scrubbing?.let { shared.seekTo(state.owner, it.toDouble()) }; scrubbing = null })
        }
        if (state.event.state == "loading") LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.event.state == "error") Text(stringResource(if (state.event.error == "position") R.string.sync_apply_failed else R.string.player_err_network), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { shared.seekTo(state.owner, ((state.seconds ?: 0.0) - 15).coerceAtLeast(0.0)) }) { Icon(Icons.Filled.FastRewind, stringResource(R.string.narration_skip_back)) }
            FilledIconButton(enabled = state.event.error != "position", onClick = { shared.command(state.owner, if (state.event.state == "playing") "pause" else "resume") }) {
                Icon(if (state.event.state == "playing") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (state.event.state == "playing") R.string.player_pause else R.string.player_play))
            }
            IconButton(onClick = { shared.seekTo(state.owner, (state.seconds ?: 0.0) + 15) }) { Icon(Icons.Filled.FastForward, stringResource(R.string.narration_skip_forward)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75, 1.0, 1.25, 1.5, 2.0).forEach { rate ->
                FilterChip(selected = kotlin.math.abs(state.event.rate - rate) < 0.01,
                    onClick = { shared.command(state.owner, "rate", rate) }, label = { Text("${rate}×") })
            }
        }
    }
}
