package com.example.pulsebeatlogger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user goal tracked by The System — persists across app restarts. */
@Entity(tableName = "user_goals")
data class UserGoal(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    /** active | paused | completed | archived */
    val status: String = "active",
    val priority: String = "medium",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val targetDateMs: Long = 0L,
    val reminderHour: Int = -1
)
