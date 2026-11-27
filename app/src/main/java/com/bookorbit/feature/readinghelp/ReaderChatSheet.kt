package com.bookorbit.feature.readinghelp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bookorbit.R
import com.bookorbit.feature.ai.AiConnection
import com.bookorbit.feature.ai.aiEndpointLabel
import com.bookorbit.feature.reader.ReaderController
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChatSheet(identity: ReaderChatIdentity, selectionCfi: String?, repository: ReaderChatRepository,
    controller: ReaderController, onNotesChanged: () -> Unit, onQuickHelp: (() -> Unit)? = null, onDismiss: () -> Unit) {
    var conversation by remember(identity) { mutableStateOf<ReaderConversation?>(null) }
    var context by remember(identity) { mutableStateOf<ReaderChatContext?>(null) }
    var contextScope by remember { mutableStateOf(if (selectionCfi != null) "selection" else "chapter") }
    var connection by remember { mutableStateOf<AiConnection?>(null) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirm by remember { mutableStateOf<ChatConfirmation?>(null) }
    var reset by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(conversation?.messages?.size) { listState.scrollToItem(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
        .takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*")) } ?: "en"

    LaunchedEffect(identity) {
        try {
            conversation = repository.load(identity)
            context = conversation?.context
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { problem = R.string.chat_storage_error }
        finally { loading = false }
    }
    LaunchedEffect(identity, repository) {
        repository.changes.collect {
            val next = runCatching { repository.connection(identity) }.getOrNull()
            if (connection != null && next?.revision != connection?.revision) { job?.cancel(); confirm = null }
            connection = next
        }
    }
    LaunchedEffect(identity.owner) {
        repository.owners.collect { if (it != identity.owner) {
            job?.cancel(); confirm = null; conversation = null; context = null; onDismiss()
        } }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { job?.cancel(); confirm = null } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }

    fun prepare(retry: Boolean) {
        if (busy || loading) return
        val selected = connection ?: return
        val question = if (retry) conversation?.messages?.lastOrNull()?.text ?: return else draft.trim()
        if (question.isBlank()) return
        busy = true
        job = scope.launch {
            problem = null
            try {
                val chosen = context ?: controller.chatContext(contextScope, selectionCfi).also { context = it }
                val oldMessages = conversation?.messages.orEmpty()
                val size = chosen.text.length + oldMessages.sumOf { it.text.length } + (if (retry) 0 else question.length)
                require(size + 32_768 <= CHAT_REQUEST_LIMIT && oldMessages.size + (if (retry) 0 else 1) <= 39)
                confirm = ChatConfirmation(conversation, chosen, question, selected, retry)
            } catch (error: ReaderController.ChatContextFailure) {
                problem = if (error.tooLong) R.string.chat_too_long else R.string.chat_boundary_error
            } catch (_: TimeoutCancellationException) { problem = R.string.chat_boundary_error }
            catch (error: CancellationException) { throw error }
            catch (_: IllegalArgumentException) { problem = R.string.chat_too_long }
            catch (_: Exception) { problem = R.string.chat_boundary_error }
            finally { busy = false }
        }
    }

    fun send(value: ChatConfirmation) {
        if (busy) return
        busy = true
        job = scope.launch {
            problem = null
            try {
                conversation = repository.ask(identity, value.conversation, value.context, value.question,
                    language, value.connection.revision, value.retry)
                context = conversation?.context
                draft = ""
            } catch (error: CancellationException) { problem = R.string.chat_interrupted; throw error }
            catch (_: Exception) { problem = R.string.chat_request_error }
            finally {
                // Recover the durable pending turn after cancellation too, without retrying it.
                withContext(NonCancellable) {
                    runCatching { repository.load(identity) }.onSuccess { conversation = it; context = it?.context ?: context }
                }
                busy = false
            }
        }
    }

    val configured = connection?.let { it.valid && it.directEnabled && it.helpModel.isNotBlank() && (!it.provider.requiresToken || it.hasToken) } == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineSmall)
            if (configured) Text(stringResource(R.string.ai_active_direct, connection!!.provider.displayName, connection!!.helpModel), style = MaterialTheme.typography.bodySmall)
            else Text(stringResource(R.string.chat_direct_required), color = MaterialTheme.colorScheme.error)
            if (onQuickHelp != null) TextButton(onClick = onQuickHelp, enabled = !busy) { Text(stringResource(R.string.chat_quick_help)) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (conversation == null && !loading) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("chapter" to R.string.chat_scope_chapter, "book" to R.string.chat_scope_book,
                        "selection" to R.string.chat_scope_selection).forEach { (value, label) ->
                        if (value != "selection" || selectionCfi != null) FilterChip(selected = contextScope == value,
                            onClick = { contextScope = value; context = null }, enabled = !busy, label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.chat_boundary_notice), style = MaterialTheme.typography.bodySmall)
            }
            context?.let { snapshot ->
                Text(stringResource(R.string.chat_context_size, snapshot.text.length, snapshot.chapter,
                    stringResource(when (snapshot.scope) { "book" -> R.string.chat_scope_book; "chapter" -> R.string.chat_scope_chapter; else -> R.string.chat_scope_selection })), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { preview = true }) { Text(stringResource(R.string.chat_preview)) }
                    if (conversation != null) TextButton(onClick = { reset = true }, enabled = !busy) { Text(stringResource(R.string.chat_new)) }
                }
                if (snapshot.noteCfi == null) Text(stringResource(R.string.chat_note_anchor_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), reverseLayout = true) {
                items(conversation?.messages.orEmpty().asReversed(), key = { it.id }) { message ->
                    Surface(color = if (message.role == "assistant") MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(if (message.role == "assistant") R.string.chat_assistant else R.string.chat_you), style = MaterialTheme.typography.labelMedium)
                            if (message.role == "assistant") ChatMarkdown(message.text) else SelectionContainer { Text(message.text) }
                            if (message.role == "assistant") {
                                when (message.noteState) {
                                    "queued" -> Text(stringResource(R.string.chat_note_queued), style = MaterialTheme.typography.bodySmall)
                                    "requested" -> Text(stringResource(R.string.chat_note_uncertain), style = MaterialTheme.typography.bodySmall)
                                    else -> TextButton(enabled = !busy && context?.noteCfi != null, onClick = {
                                        busy = true
                                        job = scope.launch {
                                            try {
                                                conversation = repository.saveNote(identity, conversation!!.id, message.id)
                                                onNotesChanged()
                                            } catch (error: CancellationException) { throw error }
                                            catch (_: Exception) { problem = R.string.chat_note_error }
                                            finally {
                                                withContext(NonCancellable) { runCatching { repository.load(identity) }.onSuccess { conversation = it } }
                                                busy = false
                                            }
                                        }
                                    }) { Text(stringResource(R.string.chat_save_note)) }
                                }
                            }
                        }
                    }
                }
            }
            problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (conversation?.interrupted == true) {
                Text(stringResource(R.string.chat_interrupted), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { prepare(true) }, enabled = configured && !busy) { Text(stringResource(R.string.chat_retry)) }
                    TextButton(onClick = {
                        busy = true
                        job = scope.launch {
                            try { conversation = repository.discardUnanswered(identity) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { problem = R.string.chat_storage_error }
                            finally { busy = false }
                        }
                    }, enabled = !busy) { Text(stringResource(R.string.chat_discard)) }
                }
            } else {
                OutlinedTextField(draft, onValueChange = { if (it.length <= 8000) draft = it },
                    label = { Text(stringResource(R.string.chat_question)) }, enabled = !busy, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Button(onClick = { prepare(false) }, enabled = configured && !loading && !busy && draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.help_send))
                }
            }
            if (busy) Row {
                CircularProgressIndicator(Modifier.size(24.dp))
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    confirm?.let { value ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(stringResource(R.string.help_confirm_title)) },
            text = { Text(stringResource(R.string.chat_consent, value.connection.provider.displayName, value.connection.helpModel,
                value.context.text.length, value.conversation?.messages?.size ?: 0) + "\n\n" + aiEndpointLabel(value.connection) +
                if (value.connection.baseUrl.startsWith("http:")) "\n" + stringResource(R.string.ai_http_warning) else "") },
            confirmButton = { TextButton(onClick = { confirm = null; send(value) }) { Text(stringResource(R.string.help_send)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(stringResource(R.string.chat_new)) },
        text = { Text(stringResource(R.string.chat_reset_notice)) }, confirmButton = {
            TextButton(onClick = {
                reset = false; busy = true
                job = scope.launch {
                    try { repository.clear(identity); conversation = null; context = null; problem = null }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { problem = R.string.chat_storage_error }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.chat_new)) }
        }, dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.cancel)) } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text(stringResource(R.string.chat_preview)) },
        text = { SelectionContainer { Text(context?.text.orEmpty(), Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { preview = false }) { Text(stringResource(R.string.chat_close)) } })
}

private data class ChatConfirmation(val conversation: ReaderConversation?, val context: ReaderChatContext,
    val question: String, val connection: AiConnection, val retry: Boolean)
