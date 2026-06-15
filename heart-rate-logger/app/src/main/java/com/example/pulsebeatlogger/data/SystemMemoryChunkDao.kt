package com.example.pulsebeatlogger.data

import androidx.room.*

@Dao
interface SystemMemoryChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chunk: SystemMemoryChunk)

    @Query("SELECT COUNT(*) FROM system_memory_chunks")
    suspend fun count(): Int

    @Query("SELECT * FROM system_memory_chunks ORDER BY createdAt DESC")
    suspend fun getAllNewestFirst(): List<SystemMemoryChunk>

    @Query("SELECT * FROM system_memory_chunks ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SystemMemoryChunk>

    @Query("DELETE FROM system_memory_chunks")
    suspend fun clearAll()
}
