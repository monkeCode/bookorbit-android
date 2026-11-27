package com.bookorbit.feature.readinghelp

import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.feature.ai.AiConnection
import com.bookorbit.feature.ai.AiConnectionStore
import com.bookorbit.feature.ai.DirectReadingHelp
import com.bookorbit.feature.reader.ReaderAnnotationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Direct-only: deliberately has no ApiService or attestation dependency. */
@Singleton
class ReaderChatRepository @Inject constructor(
    private val storage: SecureStorage,
    private val session: SessionManager,
    private val connections: AiConnectionStore,
    private val direct: DirectReadingHelp,
    private val annotations: ReaderAnnotationRepository,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val owners get() = session.owners
    val changes get() = connections.changes

    fun connection(identity: ReaderChatIdentity): AiConnection {
        check(session.owns(identity.owner))
        return connections.connection(identity.owner)
    }

    private fun read(identity: ReaderChatIdentity): ReaderConversation? {
        check(session.owns(identity.owner))
        return storage.getString(identity.storageKey())?.let {
            require(it.length <= 1_500_000)
            json.decodeFromString<ReaderConversation>(it).also { value -> value.validate() }
        }
    }

    private fun write(identity: ReaderChatIdentity, value: ReaderConversation?) {
        check(session.owns(identity.owner))
        value?.validate()
        storage.putStringDurably(identity.storageKey(), value?.let { json.encodeToString(ReaderConversation.serializer(), it) })
    }

    suspend fun load(identity: ReaderChatIdentity): ReaderConversation? = withContext(Dispatchers.IO) {
        lock.withLock { read(identity) }.also { check(session.owns(identity.owner)) }
    }

    suspend fun clear(identity: ReaderChatIdentity) = withContext(Dispatchers.IO) {
        lock.withLock { write(identity, null) }
    }

    suspend fun discardUnanswered(identity: ReaderChatIdentity): ReaderConversation = withContext(Dispatchers.IO) {
        lock.withLock {
            val value = checkNotNull(read(identity))
            check(value.interrupted && value.messages.size % 2 == 1)
            value.copy(messages = value.messages.dropLast(1), interrupted = false).also { write(identity, it) }
        }
    }

    /** Confirmation pins configuration, owner lease, conversation id, full history and context. */
    suspend fun ask(identity: ReaderChatIdentity, expected: ReaderConversation?, context: ReaderChatContext,
        question: String, language: String, revision: Long, retry: Boolean = false): ReaderConversation = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = read(identity)
            check(existing == expected)
            val connection = connection(identity)
            check(connection.valid && connection.directEnabled && connection.revision == revision)
            check(connection.helpModel.isNotBlank() && (!connection.provider.requiresToken || connection.hasToken))
            val base = existing ?: ReaderConversation(UUID.randomUUID().toString(), context)
            check(base.context == context)
            val pending = if (retry) {
                check(base.interrupted && base.messages.size % 2 == 1 && base.messages.last().text == question)
                base
            } else {
                check(!base.interrupted && base.messages.size % 2 == 0)
                require(question.isNotBlank() && question.length <= 8000)
                base.copy(messages = base.messages + ReaderChatMessage(UUID.randomUUID().toString(), "user", question), interrupted = true)
            }
            pending.validate()
            require(pending.messages.size <= 39 && pending.context.text.length + pending.messages.sumOf { it.text.length } + 32_768 <= CHAT_REQUEST_LIMIT)
            write(identity, pending)
            val response = direct.chat(pending, language, identity.owner, revision).single()
            check(response.type == "done" && session.owns(identity.owner) && connections.revision == revision)
            pending.copy(messages = pending.messages + ReaderChatMessage(UUID.randomUUID().toString(), "assistant", response.text), interrupted = false)
                .also { write(identity, it) }
        }
    }

    /** Reserve durably first. An interrupted/uncertain save is never blindly CREATEd again. */
    suspend fun saveNote(identity: ReaderChatIdentity, conversationId: String, messageId: String): ReaderConversation = withContext(Dispatchers.IO) {
        lock.withLock {
            val conversation = checkNotNull(read(identity))
            check(conversation.id == conversationId)
            val message = conversation.messages.single { it.id == messageId }
            check(message.role == "assistant")
            if (message.noteState.isNotEmpty()) return@withLock conversation
            val context = conversation.context
            val cfi = checkNotNull(context.noteCfi)
            val text = checkNotNull(context.noteText)
            val reserved = conversation.copy(messages = conversation.messages.map {
                if (it.id == messageId) it.copy(noteState = "requested") else it
            })
            write(identity, reserved)
            // The anchor can be in the preceding chapter when the cutoff is at a chapter
            // start. Do not label it with the current chapter's title by inference.
            annotations.create(identity.bookId, identity.fileId, cfi, text, "yellow", message.text, null, identity.owner)
            reserved.copy(messages = reserved.messages.map {
                if (it.id == messageId) it.copy(noteState = "queued") else it
            }).also { write(identity, it) }
        }
    }
}
