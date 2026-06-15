package com.example.pulsebeatlogger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single reviewable unit inside a skill's curriculum.
 * Tracks SM-2 spaced repetition state so the engine knows when to surface it next.
 */
@Entity(tableName = "learning_items")
data class LearningItem(
    @PrimaryKey val id: String,
    val skillName: String,

    // Content classification
    val category: String,     // "pitch_accent" | "vocabulary" | "pronunciation" | "grammar" | "custom"
    val content: String,      // Short display label, e.g. "雨 (rain) — pitch accent: LH"
    val contentJson: String,  // Full JSON with example sentence, audio hint, etc.

    // SM-2 spaced repetition fields
    val easeFactor: Float = 2.5f,
    val interval: Int = 1,          // days until next review
    val repetitions: Int = 0,
    val nextReviewDate: Long = System.currentTimeMillis(),
    val lastAccuracy: Float = 0f,   // 0.0–1.0

    // Metadata
    val tags: String = "[]",        // JSON array, e.g. ["n5","common","pitch_accent"]

    // Adaptive difficulty tracking
    /** How many times in a row the user rated this item "Again" (1). Resets on any good rating. */
    val consecutiveFails: Int = 0,
    /** How many times Gemini has regenerated this item's content to make it simpler. */
    val regenerationCount: Int = 0
)
