package com.example.pulsebeatlogger.data

import androidx.room.*

@Dao
interface UserGoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: UserGoal)

    @Query("SELECT * FROM user_goals WHERE status = 'active' ORDER BY updatedAt DESC")
    suspend fun getActiveGoals(): List<UserGoal>

    @Query("SELECT * FROM user_goals ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getAllRecent(limit: Int = 30): List<UserGoal>

    @Query("SELECT * FROM user_goals WHERE id = :id")
    suspend fun getById(id: String): UserGoal?

    @Query("SELECT * FROM user_goals WHERE id LIKE :prefix || '%' LIMIT 1")
    suspend fun getByIdPrefix(prefix: String): UserGoal?

    @Query("SELECT COUNT(*) FROM user_goals WHERE status = 'active'")
    suspend fun countActive(): Int

    @Query("UPDATE user_goals SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE user_goals SET title = :title, description = :desc, priority = :priority, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDetails(id: String, title: String, desc: String, priority: String, updatedAt: Long = System.currentTimeMillis())
}
