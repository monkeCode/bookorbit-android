package com.bookorbit.core.model

import kotlinx.serialization.Serializable

@Serializable data class ReadingTextPosition(val cfi: String? = null, val page: Int? = null, val updatedAt: String? = null)
@Serializable data class NarrationPosition(val seconds: Double? = null, val fragment: String? = null,
    val sectionIndex: Int? = null, val percentage: Double? = null, val updatedAt: String? = null)
@Serializable data class ReadingPositionEntry(val fileId: Int, val percentage: Double = 0.0, val updatedAt: String? = null,
    val text: ReadingTextPosition = ReadingTextPosition(), val narration: NarrationPosition = NarrationPosition())
@Serializable data class ReadingPositionList(val items: List<ReadingPositionEntry>)
@Serializable data class ReadingPositionWrite(val fileId: Int, val kind: String, val percentage: Double,
    val editedAt: String, val text: ReadingTextPosition? = null, val narration: NarrationPosition? = null)
@Serializable data class ReadingPositionWrites(val writes: List<ReadingPositionWrite>)
@Serializable data class ReadingPositionResult(val fileId: Int, val status: String, val progress: ReadingPositionEntry)
@Serializable data class ReadingPositionResults(val results: List<ReadingPositionResult>)
