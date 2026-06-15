package com.example.pulsebeatlogger.data

import androidx.room.*

@Dao
interface LearningItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<LearningItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LearningItem)

    @Query("SELECT * FROM learning_items WHERE skillName = :skill ORDER BY nextReviewDate ASC")
    suspend fun getAllForSkill(skill: String): List<LearningItem>

    // Items due for review today (nextReviewDate <= now)
    @Query("""
        SELECT * FROM learning_items
        WHERE skillName = :skill AND nextReviewDate <= :nowMs
        ORDER BY nextReviewDate ASC
        LIMIT :limit
    """)
    suspend fun getDueItems(skill: String, nowMs: Long, limit: Int = 10): List<LearningItem>

    // Items with the lowest recent accuracy — surface weak areas even if not yet due
    @Query("""
        SELECT * FROM learning_items
        WHERE skillName = :skill
        ORDER BY lastAccuracy ASC, nextReviewDate ASC
        LIMIT :limit
    """)
    suspend fun getWeakItems(skill: String, limit: Int = 5): List<LearningItem>

    @Query("SELECT COUNT(*) FROM learning_items WHERE skillName = :skill")
    suspend fun countForSkill(skill: String): Int

    @Query("DELETE FROM learning_items WHERE skillName = :skill")
    suspend fun deleteAllForSkill(skill: String)

    /** Count all due items across every skill — used by the SRS reminder notification. */
    @Query("SELECT COUNT(*) FROM learning_items WHERE nextReviewDate <= :nowMs")
    suspend fun countAllDue(nowMs: Long): Int

    /** All due items across every skill, grouped by skill name — for notification detail. */
    @Query("""
        SELECT skillName, COUNT(*) as cnt FROM learning_items
        WHERE nextReviewDate <= :nowMs
        GROUP BY skillName
    """)
    suspend fun countDueBySkill(nowMs: Long): List<SkillDueCount>

    /** Increment consecutiveFails; called on rating = 1 (Again). */
    @Query("UPDATE learning_items SET consecutiveFails = consecutiveFails + 1 WHERE id = :id")
    suspend fun incrementFails(id: String)

    /** Reset consecutiveFails on any good rating (>= 3). */
    @Query("UPDATE learning_items SET consecutiveFails = 0 WHERE id = :id")
    suspend fun resetFails(id: String)

    /** Update content fields after adaptive regeneration. */
    @Query("UPDATE learning_items SET content = :content, contentJson = :contentJson, regenerationCount = regenerationCount + 1, consecutiveFails = 0 WHERE id = :id")
    suspend fun updateContent(id: String, content: String, contentJson: String)

    /** Items that have failed 3+ times in a row and haven't been regenerated more than twice. */
    @Query("SELECT * FROM learning_items WHERE consecutiveFails >= 3 AND regenerationCount < 2 LIMIT 5")
    suspend fun getItemsNeedingRegeneration(): List<LearningItem>
}

data class SkillDueCount(val skillName: String, val cnt: Long)
