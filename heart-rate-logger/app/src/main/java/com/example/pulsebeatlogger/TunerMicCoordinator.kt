package com.example.pulsebeatlogger

import android.content.Context
import android.content.Intent

/** Lets [TunerEngine] take the mic away from [HeartRateService] (only one AudioRecord at a time). */
object TunerMicCoordinator {

    fun requestExclusiveMic(context: Context) {
        HeartRateState.tunerMicActive = true
        context.startService(
            Intent(context, HeartRateService::class.java).apply { action = ACTION_HOLD_MIC }
        )
    }

    fun releaseExclusiveMic(context: Context) {
        HeartRateState.tunerMicActive = false
        HeartRateState.livePitchHz = 0f
        HeartRateState.livePitchNote = "--"
        HeartRateState.livePitchCents = 0
        context.startService(
            Intent(context, HeartRateService::class.java).apply { action = ACTION_RELEASE_MIC }
        )
    }

    const val ACTION_HOLD_MIC = "TUNER_HOLD_MIC"
    const val ACTION_RELEASE_MIC = "TUNER_RELEASE_MIC"
}
