package com.licitaia.core.platform.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlatformTenderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tenders: List<PlatformTenderEntity>)

    @Query("SELECT * FROM platform_tender ORDER BY datetime(updatedAt) DESC")
    fun observeAll(): Flow<List<PlatformTenderEntity>>

    @Query("SELECT * FROM platform_tender ORDER BY datetime(updatedAt) DESC")
    suspend fun all(): List<PlatformTenderEntity>

    @Query("SELECT * FROM platform_tender WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PlatformTenderEntity?

    /** Maior `updatedAt` já espelhado: permite parar o pull ao reencontrar o conhecido. */
    @Query("SELECT MAX(updatedAt) FROM platform_tender")
    suspend fun latestUpdatedAt(): String?

    @Query("SELECT COUNT(*) FROM platform_tender")
    suspend fun count(): Int

    @Query("DELETE FROM platform_tender")
    suspend fun clear()
}

@Dao
interface PlatformMutationDao {
    @Insert
    suspend fun enqueue(mutation: PlatformMutationEntity): Long

    @Query("SELECT * FROM platform_mutation ORDER BY createdAt ASC, id ASC")
    suspend fun pending(): List<PlatformMutationEntity>

    @Query("SELECT * FROM platform_mutation ORDER BY createdAt ASC, id ASC")
    fun observePending(): Flow<List<PlatformMutationEntity>>

    @Query("UPDATE platform_mutation SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String?)

    @Delete
    suspend fun remove(mutation: PlatformMutationEntity)

    @Query("DELETE FROM platform_mutation WHERE id = :id")
    suspend fun removeById(id: Long)

    @Query("SELECT COUNT(*) FROM platform_mutation")
    suspend fun count(): Int

    @Query("DELETE FROM platform_mutation")
    suspend fun clear()
}
