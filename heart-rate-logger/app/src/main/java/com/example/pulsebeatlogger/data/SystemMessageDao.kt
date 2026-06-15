package com.example.pulsebeatlogger.data

import androidx.room.*

@Dao
interface SystemMessageDao {
    @Insert
    suspend fun insert(msg: SystemMessage): Long

    @Query("SELECT * FROM system_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<SystemMessage>

    @Query("SELECT * FROM system_messages ORDER BY timestamp ASC")
    suspend fun getAllOrdered(): List<SystemMessage>

    @Query("SELECT COUNT(*) FROM system_messages")
    suspend fun count(): Int

    @Query("DELETE FROM system_messages")
    suspend fun clearAll()
}
