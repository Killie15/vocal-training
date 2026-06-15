package com.example.pulsebeatlogger

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages streak, XP, and achievements.
 * Call [onItemReviewed] after every SRS rating and [onSessionCompleted] at session end.
 * State is kept in [HeartRateState] for the UI and persisted to SharedPreferences.
 */
object GamificationHelper {

    // XP awards
    private const val XP_PER_REVIEW   = 5
    private const val XP_PER_SESSION  = 50
    private const val XP_GOOD_RATING  = 3   // bonus for rating >= 4
    private const val XP_STREAK_BONUS = 10  // bonus per day of streak

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** Must be called once on app start to restore persisted state. */
    fun loadFromPrefs(prefs: SharedPreferences) {
        HeartRateState.streakDays         = prefs.getInt("streak_days", 0)
        HeartRateState.totalXp            = prefs.getInt("total_xp", 0)
        HeartRateState.totalItemsReviewed = prefs.getInt("total_reviewed", 0)
        HeartRateState.lastPracticeDate   = prefs.getString("last_practice_date", "") ?: ""
        val achievementsCsv               = prefs.getString("achievements", "") ?: ""
        HeartRateState.unlockedAchievements.clear()
        if (achievementsCsv.isNotEmpty()) {
            HeartRateState.unlockedAchievements.addAll(achievementsCsv.split(","))
        }
    }

    /** Call after each SRS item is rated. rating: 1=Again, 3=Hard, 4=Good, 5=Easy */
    fun onItemReviewed(prefs: SharedPreferences, rating: Int) {
        val today = dateFmt.format(Date())

        // Update streak
        val last = HeartRateState.lastPracticeDate
        if (last != today) {
            val yesterday = dateFmt.format(Date(System.currentTimeMillis() - 86_400_000L))
            HeartRateState.streakDays = if (last == yesterday) HeartRateState.streakDays + 1 else 1
            HeartRateState.lastPracticeDate = today
        }

        // Award XP
        var xpGain = XP_PER_REVIEW
        if (rating >= 4) xpGain += XP_GOOD_RATING
        HeartRateState.totalXp += xpGain
        HeartRateState.totalItemsReviewed++

        checkAchievements(prefs)
        saveToPrefs(prefs)
    }

    /** Call when a full sensor session is saved. */
    fun onSessionCompleted(prefs: SharedPreferences) {
        val sessionCount = prefs.getInt("total_sessions", 0) + 1
        prefs.edit().putInt("total_sessions", sessionCount).apply()

        // Streak bonus: more valuable if user is on a streak
        HeartRateState.totalXp += XP_PER_SESSION + HeartRateState.streakDays * XP_STREAK_BONUS

        // Unlock session-based achievements
        fun unlock(id: String) {
            if (!HeartRateState.unlockedAchievements.contains(id)) {
                HeartRateState.unlockedAchievements.add(id)
                HeartRateState.log("🏆 Achievement unlocked: $id")
            }
        }
        if (sessionCount >= 1)  unlock("first_session")
        if (sessionCount >= 10) unlock("sessions_10")

        checkAchievements(prefs)
        saveToPrefs(prefs)
    }

    // ── Achievements ──────────────────────────────────────────────────────────

    data class Achievement(val id: String, val title: String, val description: String, val emoji: String)

    val ALL_ACHIEVEMENTS = listOf(
        Achievement("first_review",    "First Step",       "Reviewed your first item",                     "🌱"),
        Achievement("reviews_10",      "Getting Started",  "Reviewed 10 items total",                      "📖"),
        Achievement("reviews_100",     "Dedicated Learner","Reviewed 100 items total",                     "🎓"),
        Achievement("reviews_500",     "Scholar",          "Reviewed 500 items total",                     "🏆"),
        Achievement("streak_3",        "3-Day Streak",     "Practiced 3 days in a row",                    "🔥"),
        Achievement("streak_7",        "Week Warrior",     "Practiced 7 days in a row",                    "⚡"),
        Achievement("streak_30",       "Month Master",     "Practiced 30 days in a row",                   "🌟"),
        Achievement("xp_100",          "XP Rookie",        "Earned 100 XP",                                "⭐"),
        Achievement("xp_1000",         "XP Pro",           "Earned 1 000 XP",                              "💫"),
        Achievement("xp_5000",         "XP Legend",        "Earned 5 000 XP",                              "🌠"),
        Achievement("first_session",   "In the Zone",      "Completed your first sensor session",          "💪"),
        Achievement("sessions_10",     "Consistent",       "Completed 10 sessions",                        "📊")
    )

    private fun checkAchievements(prefs: SharedPreferences) {
        val r  = HeartRateState.totalItemsReviewed
        val s  = HeartRateState.streakDays
        val xp = HeartRateState.totalXp

        fun unlock(id: String) {
            if (!HeartRateState.unlockedAchievements.contains(id)) {
                HeartRateState.unlockedAchievements.add(id)
                HeartRateState.log("🏆 Achievement unlocked: $id")
            }
        }

        if (r >= 1)   unlock("first_review")
        if (r >= 10)  unlock("reviews_10")
        if (r >= 100) unlock("reviews_100")
        if (r >= 500) unlock("reviews_500")
        if (s >= 3)   unlock("streak_3")
        if (s >= 7)   unlock("streak_7")
        if (s >= 30)  unlock("streak_30")
        if (xp >= 100)  unlock("xp_100")
        if (xp >= 1000) unlock("xp_1000")
        if (xp >= 5000) unlock("xp_5000")
    }

    private fun saveToPrefs(prefs: SharedPreferences) {
        prefs.edit()
            .putInt("streak_days", HeartRateState.streakDays)
            .putInt("total_xp", HeartRateState.totalXp)
            .putInt("total_reviewed", HeartRateState.totalItemsReviewed)
            .putString("last_practice_date", HeartRateState.lastPracticeDate)
            .putString("achievements", HeartRateState.unlockedAchievements.joinToString(","))
            .apply()
    }
}
