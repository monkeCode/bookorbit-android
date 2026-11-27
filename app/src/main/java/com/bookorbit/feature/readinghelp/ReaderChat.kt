package com.bookorbit.feature.readinghelp

import com.bookorbit.core.auth.SessionOwner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

const val CHAT_CONTEXT_LIMIT = 64_000
const val CHAT_REQUEST_LIMIT = 128_000

/** Exact edition bytes/revision, never a book id or progress percentage alone. */
data class ReaderChatIdentity(val owner: SessionOwner, val bookId: Int, val fileId: Int, val revision: String) {
    fun storageKey(): String {
        require(bookId > 0 && fileId > 0 && revision.isNotBlank())
        val value = listOf(owner.serverUrl, owner.userId.toString(), bookId.toString(), fileId.toString(), revision)
        return "reader_chat_" + MessageDigest.getInstance("SHA-256")
            .digest(kotlinx.serialization.json.Json.encodeToString(value).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class ReaderChatContext(
    val scope: String,
    val boundaryCfi: String,
    val chapter: String = "",
    val text: String,
    val noteCfi: String? = null,
    val noteText: String? = null,
) {
    fun validate() {
        require(scope in setOf("book", "chapter", "selection"))
        require(boundaryCfi.startsWith("epubcfi(") && boundaryCfi.length <= 8192)
        require(text.isNotBlank() && text.length <= CHAT_CONTEXT_LIMIT)
        require(chapter.length <= 1024)
        require((noteCfi == null) == (noteText == null))
        if (noteCfi != null) require(noteCfi.startsWith("epubcfi(") && noteCfi.length <= 2000 && !noteText.isNullOrBlank() && noteText.length <= 512)
    }
}

@Serializable
data class ReaderChatMessage(val id: String, val role: String, val text: String, val noteState: String = "")

@Serializable
data class ReaderConversation(
    val id: String,
    val context: ReaderChatContext,
    val messages: List<ReaderChatMessage> = emptyList(),
    // Persist before submitting: process death never silently replays a paid request.
    val interrupted: Boolean = false,
) {
    fun validate() {
        context.validate()
        require(id.isNotBlank() && messages.size <= 40)
        require(messages.map { it.id }.distinct().size == messages.size)
        messages.forEachIndexed { index, message ->
            require(message.id.isNotBlank() && message.role == if (index % 2 == 0) "user" else "assistant")
            require(message.text.isNotBlank() && message.text.length <= 32_768)
            require(message.noteState in setOf("", "requested", "queued"))
        }
        require(context.text.length + messages.sumOf { it.text.length } <= CHAT_REQUEST_LIMIT)
    }
}
