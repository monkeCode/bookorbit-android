package com.bookorbit.core.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Database is server scoped; every checkpoint is additionally account/book/coordinate scoped. */
@Entity(tableName = "progress_sync")
data class ProgressSyncEntry(
    @PrimaryKey val key: String,
    val userId: Int,
    val bookId: Int,
    val fileId: Int,
    val kind: String,
    val title: String,
    val local: String,
    val baseline: String?,
    val remote: String?,
    val dirty: Boolean,
    val conflict: Boolean,
    val epoch: Long,
    val writeId: String,
    val awaitingApply: Boolean = false,
)

@Dao
interface ProgressSyncDao {
    @Query("SELECT DISTINCT userId FROM progress_sync WHERE kind = :kind AND bookId = :bookId AND fileId = :fileId")
    suspend fun knownOwners(kind: String, bookId: Int, fileId: Int): List<Int>
    @Transaction
    suspend fun replaceGroup(expected: List<ProgressSyncEntry>, replacement: List<ProgressSyncEntry>): Boolean {
        if (expected.any { get(it.key) != it }) return false
        replacement.forEach { put(it) }
        return true
    }
    @Query("SELECT * FROM progress_sync WHERE `key` = :key") suspend fun get(key: String): ProgressSyncEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: ProgressSyncEntry)
    @Query("SELECT * FROM progress_sync WHERE userId = :userId AND dirty = 1") suspend fun pending(userId: Int): List<ProgressSyncEntry>
    @Query("SELECT * FROM progress_sync WHERE userId = :userId AND conflict = 1 ORDER BY bookId, kind, fileId")
    fun conflicts(userId: Int): Flow<List<ProgressSyncEntry>>
}
