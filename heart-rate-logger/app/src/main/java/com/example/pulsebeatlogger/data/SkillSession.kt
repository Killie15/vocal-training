package com.example.pulsebeatlogger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skill_sessions")
data class SkillSession(
    @PrimaryKey val id: String,
    val skillName: String,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Int,

    // Heart rate fields
    val avgHr: Float = 0f,
    val maxHr: Float = 0f,
    val minHr: Float = 0f,
    val hrDataPoints: String = "[]",       // JSON [{timeOffset, hr}]

    // Voice / pitch fields (populated by PassiveTagger)
    val avgPitchHz: Float = 0f,
    val pitchMinHz: Float = 0f,
    val pitchMaxHz: Float = 0f,
    val jitterScore: Float = 0f,
    val speakingRegister: String = "",     // "bass", "tenor_mezzo", "soprano"

    // Learning outcome fields
    val accuracyPct: Float = 0f,
    val itemsReviewed: Int = 0,
    val weakItemIds: String = "[]",        // JSON array of LearningItem ids
    val feedbackText: String = "",         // Gemini-generated

    // Passive tags JSON — cross-skill reusable profile data
    // e.g. {"voiceF0":175.0,"speakingRange":"A2-G4","register":"tenor_mezzo"}
    val passiveTags: String = "{}",

    // Fitness
    val calories: Float = 0f,
    val distance: Float = 0f,

    val syncedToGoogle: Boolean = false
)
