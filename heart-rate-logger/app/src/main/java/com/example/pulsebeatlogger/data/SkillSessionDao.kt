package com.example.pulsebeatlogger.data

import androidx.room.*

@Dao
interface SkillSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SkillSession)

    @Query("SELECT * FROM skill_sessions WHERE skillName = :skill ORDER BY startTime DESC")
    suspend fun getSessionsForSkill(skill: String): List<SkillSession>

    @Query("SELECT * FROM skill_sessions ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int = 30): List<SkillSession>

    @Query("SELECT * FROM skill_sessions WHERE skillName = :skill ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentSessionsForSkill(skill: String, limit: Int = 7): List<SkillSession>

    @Query("SELECT AVG(accuracyPct) FROM skill_sessions WHERE skillName = :skill AND startTime > :sinceMs")
    suspend fun getAverageAccuracy(skill: String, sinceMs: Long): Float?

    @Query("SELECT * FROM skill_sessions WHERE syncedToGoogle = 0")
    suspend fun getUnsynced(): List<SkillSession>

    @Query("UPDATE skill_sessions SET syncedToGoogle = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM skill_sessions WHERE id = :id")
    suspend fun delete(id: String)

    // Returns all unique passive voice tags to build a running voice profile
    @Query("SELECT passiveTags FROM skill_sessions WHERE passiveTags != '{}' ORDER BY startTime DESC LIMIT 20")
    suspend fun getRecentPassiveTags(): List<String>

    /** Single session by ID — for history detail view */
    @Query("SELECT * FROM skill_sessions WHERE id = :id")
    suspend fun getById(id: String): SkillSession?

    /** Total sessions recorded */
    @Query("SELECT COUNT(*) FROM skill_sessions")
    suspend fun totalCount(): Long

    /** Sessions grouped by day (epoch-day) for heatmap / streak calculation */
    @Query("SELECT startTime FROM skill_sessions ORDER BY startTime DESC LIMIT 100")
    suspend fun getSessionTimestamps(): List<Long>

    /** All distinct skill names that have sessions */
    @Query("SELECT DISTINCT skillName FROM skill_sessions ORDER BY skillName ASC")
    suspend fun getDistinctSkillNames(): List<String>
}
