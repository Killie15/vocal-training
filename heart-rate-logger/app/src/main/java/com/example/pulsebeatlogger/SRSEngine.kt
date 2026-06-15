package com.example.pulsebeatlogger

import com.example.pulsebeatlogger.data.LearningItem
import com.example.pulsebeatlogger.data.LearningItemDao
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SM-2 Spaced Repetition System engine.
 *
 * Rating scale (same as Anki/SuperMemo):
 *   0 = Complete blackout — reset
 *   1 = Wrong but answer remembered on seeing — reset
 *   2 = Wrong but easy to remember — reset
 *   3 = Correct with significant difficulty
 *   4 = Correct after a hesitation
 *   5 = Perfect recall
 *
 * Items with rating < 3 are reset to interval = 1 day.
 * Items with rating >= 3 advance their interval via: next = prev * ease_factor
 */
object SRSEngine {

    /**
     * Update a LearningItem with SM-2 after a review.
     * Returns the updated item — caller is responsible for persisting via the DAO.
     */
    fun review(item: LearningItem, rating: Int): LearningItem {
        val clampedRating = rating.coerceIn(0, 5)
        val accuracy = clampedRating / 5.0f

        HeartRateState.log("SRS review: item='${item.content.take(30)}' rating=$clampedRating " +
            "oldInterval=${item.interval}d oldEase=${"%.2f".format(item.easeFactor)} reps=${item.repetitions}")

        val (newInterval, newRepetitions, newEase) = if (clampedRating < 3) {
            HeartRateState.log("SRS: rating<3 — RESET interval to 1 day")
            Triple(1, 0, max(1.3f, item.easeFactor - 0.2f))
        } else {
            val newEase = max(1.3f, item.easeFactor + 0.1f - (5 - clampedRating) * (0.08f + (5 - clampedRating) * 0.02f))
            val newReps = item.repetitions + 1
            val newIntvl = when (item.repetitions) {
                0 -> 1
                1 -> 6
                else -> (item.interval * item.easeFactor).roundToInt().coerceAtLeast(1)
            }
            Triple(newIntvl, newReps, newEase)
        }

        val nextReview = System.currentTimeMillis() + newInterval.toLong() * 24 * 60 * 60 * 1000L

        HeartRateState.log("SRS result: newInterval=${newInterval}d newEase=${"%.2f".format(newEase)} " +
            "newReps=$newRepetitions accuracy=${"%.0f".format(accuracy * 100)}%")

        return item.copy(
            interval = newInterval,
            repetitions = newRepetitions,
            easeFactor = newEase,
            nextReviewDate = nextReview,
            lastAccuracy = accuracy
        )
    }

    /**
     * Convenience: batch review multiple items at once with the same rating.
     */
    fun reviewBatch(items: List<LearningItem>, ratings: Map<String, Int>): List<LearningItem> {
        HeartRateState.log("SRS reviewBatch: ${items.size} items")
        return items.map { item ->
            val rating = ratings[item.id] ?: 3
            review(item, rating)
        }
    }

    /**
     * Summarise a skill's curriculum state:
     * returns (dueCount, weakCount, avgAccuracy)
     */
    fun summarise(items: List<LearningItem>): Triple<Int, Int, Float> {
        val now = System.currentTimeMillis()
        val dueCount = items.count { it.nextReviewDate <= now }
        val weakCount = items.count { it.lastAccuracy < 0.6f && it.repetitions > 0 }
        val avgAccuracy = if (items.isEmpty()) 0f else items.map { it.lastAccuracy }.average().toFloat()
        HeartRateState.log("SRS summarise: total=${items.size} due=$dueCount weak=$weakCount avgAcc=${"%.0f".format(avgAccuracy * 100)}%")
        return Triple(dueCount, weakCount, avgAccuracy)
    }

    /**
     * Compute a 0-100 mastery score for display from the items list.
     * Weighted toward items that have been reviewed and are well-retained.
     */
    fun masteryScore(items: List<LearningItem>): Int {
        if (items.isEmpty()) return 0
        val total = items.sumOf { item ->
            val retentionWeight = when {
                item.repetitions == 0 -> 0.0
                item.lastAccuracy >= 0.8f -> 1.0
                item.lastAccuracy >= 0.6f -> 0.6
                else -> 0.3
            }
            retentionWeight
        }
        val score = ((total / items.size) * 100).roundToInt().coerceIn(0, 100)
        HeartRateState.log("SRS masteryScore: $score/100 (${items.size} items)")
        return score
    }
}

/**
 * Extension to persist a reviewed item via the DAO directly.
 */
suspend fun LearningItemDao.applyReview(item: LearningItem, rating: Int) {
    HeartRateState.log("SRS applyReview → DAO upsert: item='${item.content.take(30)}' rating=$rating")
    val updated = SRSEngine.review(item, rating)
    upsert(updated)
    HeartRateState.log("SRS applyReview → DAO upsert complete. nextReview in ${updated.interval}d")
}
