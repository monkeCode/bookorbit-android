package com.bookorbit.feature.reader

import com.bookorbit.core.model.BookFileRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Metadata identity is not a claim that a server's resource bytes are immutable. */
internal fun NativeNarrationTimeline.streamingFingerprint(file: BookFileRef): String {
    require(file.id == fileId)
    file.contentVersion?.takeIf { it.isNotBlank() }?.let {
        return "stream-v1:$it:${file.fileHash.orEmpty()}:${file.sizeBytes}"
    }
    val snapshot = NarrationStreamIdentity(bookId, fileId, file.fileHash, file.sizeBytes, clips, resources, sections)
    val bytes = Json.encodeToString(NarrationStreamIdentity.serializer(), snapshot).toByteArray(Charsets.UTF_8)
    return "stream-v2:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@Serializable
private data class NarrationStreamIdentity(
    val bookId: Int, val fileId: Int, val fileHash: String?, val sizeBytes: Long?,
    val clips: List<NativeNarrationClip>, val resources: List<NativeNarrationResource>,
    val sections: List<NativeNarrationSection>,
)
