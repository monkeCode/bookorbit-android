package com.bookorbit.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookorbit.R
import com.bookorbit.core.db.ProgressSyncEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PositionConflictsViewModel @Inject constructor(val positions: ProgressSync,
    @ApplicationContext context: Context) : ViewModel() {
    private val dismissed = MutableStateFlow<Set<Int>>(emptySet())
    private val all = positions.owners.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else positions.conflicts(owner.userId)
    }.debounce(500).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val shown = combine(all, dismissed) { rows, hidden -> rows.filter { it.bookId !in hidden }.groupBy { it.bookId }.values.firstOrNull().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        private val validated = mutableSetOf<Network>()
        override fun onAvailable(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) {
            if (capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                if (validated.add(network)) refresh()
            } else validated.remove(network)
        }
        override fun onLost(network: Network) { validated.remove(network) }
    }
    init { connectivity.registerDefaultNetworkCallback(callback) }
    fun refresh() {
        dismissed.value = emptySet()
        viewModelScope.launch {
            try { withTimeout(20_000) { positions.flushPending() } }
            catch (e: TimeoutCancellationException) { /* Local checkpoints remain queued. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Network constraints/backoff will try later. */ }
        }
    }
    fun later(rows: List<ProgressSyncEntry>) { dismissed.value += rows.map { it.bookId }; failed.value = false }
    fun choose(rows: List<ProgressSyncEntry>, local: Boolean) {
        if (busy.value) return
        busy.value = true; failed.value = false
        viewModelScope.launch {
            try { failed.value = !positions.choose(rows, local) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed.value = true }
            finally { busy.value = false }
            if (!failed.value) refresh()
        }
    }
    override fun onCleared() { connectivity.unregisterNetworkCallback(callback); super.onCleared() }
}

@Composable
fun PositionConflicts(vm: PositionConflictsViewModel = hiltViewModel()) {
    val owner by vm.positions.owners.collectAsStateWithLifecycle()
    val storageFailed by vm.positions.storageFailed.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(storageFailed) { if (storageFailed) android.widget.Toast.makeText(context, R.string.sync_storage_failed, android.widget.Toast.LENGTH_LONG).show() }
    val rows by vm.shown.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    if (rows.isEmpty() || owner == null || !vm.positions.canDisplay(owner!!) || rows.any { it.userId != owner!!.userId }) return
    AlertDialog(onDismissRequest = { if (!busy) vm.later(rows) },
        title = { Text(rows.first().title.ifBlank { stringResource(R.string.sync_book, rows.first().bookId) }) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sync_explanation))
            rows.forEach { row ->
                Text(stringResource(when (row.kind) { "audio" -> R.string.sync_audio; "narration" -> R.string.sync_narration; else -> R.string.sync_text }), style = MaterialTheme.typography.titleSmall)
                val local = vm.positions.decode(row.local)
                val remote = row.remote?.let(vm.positions::decode)
                Text(stringResource(R.string.sync_phone) + "\n" + positionLabel(local) + "\n" + local.stamp.orEmpty())
                if (remote != null) Text(stringResource(R.string.sync_server) + "\n" + positionLabel(remote) + "\n" + remote.stamp.orEmpty())
            }
            if (failed) Text(stringResource(R.string.sync_changed), color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { vm.choose(rows, true) }) { Text(stringResource(R.string.sync_choose_phone)) } },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { vm.choose(rows, false) }) { Text(stringResource(R.string.sync_choose_server)) }
            TextButton(enabled = !busy, onClick = { vm.later(rows) }) { Text(stringResource(R.string.sync_later)) }
        } })
}

@Composable
private fun positionLabel(point: ProgressPoint): String = when {
    point.seconds != null -> stringResource(R.string.sync_audio_position, point.fileId, com.bookorbit.feature.player.formatTime(point.seconds)) +
        point.fragment?.let { "\n$it" }.orEmpty()
    point.page != null -> stringResource(R.string.sync_page, point.page)
    else -> stringResource(R.string.sync_percentage, point.percentage) + point.cfi?.let { "\n$it" }.orEmpty()
}
