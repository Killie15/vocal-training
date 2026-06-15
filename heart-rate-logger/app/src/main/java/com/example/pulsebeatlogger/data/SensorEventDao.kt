package com.example.pulsebeatlogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SensorEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SensorEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<SensorEvent>)

    /** All events for a specific session, ordered chronologically */
    @Query("SELECT * FROM sensor_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSession(sessionId: String): List<SensorEvent>

    /** All events for a skill across all sessions */
    @Query("SELECT * FROM sensor_events WHERE skillName = :skillName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getForSkill(skillName: String, limit: Int = 500): List<SensorEvent>

    /** Events by type within a time range */
    @Query("SELECT * FROM sensor_events WHERE sensorType = :type AND timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getByTypeInRange(type: String, from: Long, to: Long): List<SensorEvent>

    /** Total event count — useful for confirming data is accumulating */
    @Query("SELECT COUNT(*) FROM sensor_events")
    suspend fun totalCount(): Long

    /** Count per sensor type — quick health check */
    @Query("SELECT sensorType, COUNT(*) as cnt FROM sensor_events GROUP BY sensorType")
    suspend fun countBySensorType(): List<SensorTypeCount>

    /** Recent N events across all types (for the debug log) */
    @Query("SELECT * FROM sensor_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<SensorEvent>

    @Query("DELETE FROM sensor_events WHERE timestamp < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
}

data class SensorTypeCount(val sensorType: String, val cnt: Long)
