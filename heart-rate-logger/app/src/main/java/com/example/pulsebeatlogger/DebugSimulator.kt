package com.example.pulsebeatlogger

import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * Drives all sensor pipelines with fake data when [HeartRateState.debugMode] is true.
 *
 * Design rule: every function here only touches [HeartRateState] public fields and
 * the [PassiveTagger] — it never calls into the private internals of HeartRateService.
 * The service's debug path observes [HeartRateState.debugInjectBpmTick] and processes
 * it through the normal [processLiveBpm] code path.
 */
object DebugSimulator {

    private var hrJob: Job? = null
    private var gpsJob: Job? = null

    // ── Heart Rate Simulation ─────────────────────────────────────────────────

    /**
     * Starts a coroutine that ticks a realistic HR waveform every second.
     * The HR follows a simple sine wave around a base value so charts look natural.
     *
     * @param scope coroutine scope (typically the composable's rememberCoroutineScope)
     * @param baseHr resting HR to oscillate around
     * @param amplitudeBpm how much the HR swings ± around baseHr
     */
    fun startFakeHr(scope: CoroutineScope, baseHr: Int = 72, amplitudeBpm: Int = 15) {
        if (hrJob?.isActive == true) return
        HeartRateState.log("🔬 Debug: Starting fake HR simulation (base=${baseHr}, amp=±${amplitudeBpm})")
        HeartRateState.connectionState = "Connected"
        HeartRateState.deviceName = "Debug HR Sensor"

        hrJob = scope.launch(Dispatchers.Default) {
            var tick = 0
            while (isActive && HeartRateState.debugMode) {
                val bpm = (baseHr + amplitudeBpm * sin(tick * 0.15)).toInt().coerceIn(40, 200)
                HeartRateState.debugSimBpm = bpm
                HeartRateState.currentBpm = bpm
                // Bump the tick counter so HeartRateService's observer processes the BPM
                withContext(Dispatchers.Main) {
                    HeartRateState.debugInjectBpmTick++
                }
                HeartRateState.log("🔬 HR tick: $bpm bpm (t=$tick)")
                tick++
                delay(1000)
            }
            HeartRateState.log("🔬 Debug: Fake HR simulation stopped.")
        }
    }

    fun stopFakeHr() {
        hrJob?.cancel()
        hrJob = null
        HeartRateState.connectionState = "Disconnected"
        HeartRateState.deviceName = "None"
        HeartRateState.currentBpm = 0
        HeartRateState.log("🔬 Debug: HR simulation stopped by user.")
    }

    /** One-shot HR spike — jumps to peakBpm for 5 ticks then returns to normal. */
    fun injectHrSpike(scope: CoroutineScope, peakBpm: Int = 155) {
        HeartRateState.log("🔬 Debug: Injecting HR spike → $peakBpm bpm")
        scope.launch(Dispatchers.Default) {
            repeat(5) {
                HeartRateState.currentBpm = peakBpm
                HeartRateState.debugSimBpm = peakBpm
                withContext(Dispatchers.Main) { HeartRateState.debugInjectBpmTick++ }
                delay(1000)
            }
        }
    }

    // ── GPS Simulation ────────────────────────────────────────────────────────

    /** Simulates a slow jog: speed ~9 km/h, incrementing distance each second. */
    fun startFakeGps(scope: CoroutineScope) {
        if (gpsJob?.isActive == true) return
        HeartRateState.log("🔬 Debug: Starting fake GPS simulation (running path)")
        gpsJob = scope.launch(Dispatchers.Default) {
            var distMetres = 0.0
            var tick = 0
            while (isActive && HeartRateState.debugMode) {
                val speed = 9.0 + sin(tick * 0.1) * 1.5   // ~7.5–10.5 km/h
                val distDelta = speed / 3.6                  // km/h → m/s
                distMetres += distDelta
                val altitude = 35.0 + sin(tick * 0.05) * 8  // gentle hill

                withContext(Dispatchers.Main) {
                    HeartRateState.gpsSpeed = (speed * 10.0).roundToInt() / 10.0
                    HeartRateState.gpsDistance = (distMetres * 10.0).roundToInt() / 10.0
                    HeartRateState.gpsAltitude = (altitude * 10.0).roundToInt() / 10.0
                }
                HeartRateState.log("🔬 GPS: speed=${HeartRateState.gpsSpeed} km/h dist=${HeartRateState.gpsDistance} m alt=${HeartRateState.gpsAltitude} m")
                tick++
                delay(1000)
            }
        }
    }

    fun stopFakeGps() {
        gpsJob?.cancel()
        gpsJob = null
        HeartRateState.gpsSpeed = 0.0
        HeartRateState.gpsDistance = 0.0
        HeartRateState.log("🔬 Debug: GPS simulation stopped.")
    }

    // ── Watch Simulation ──────────────────────────────────────────────────────

    fun fakeWatchConnect() {
        HeartRateState.isWatchConnected = true
        HeartRateState.sleepSoundDb = 32.5
        HeartRateState.sleepMotionMag = 0.12
        HeartRateState.log("🔬 Debug: Fake Pixel Watch connected. Injecting sleep telemetry.")
    }

    fun fakeWatchDisconnect() {
        HeartRateState.isWatchConnected = false
        HeartRateState.log("🔬 Debug: Pixel Watch disconnected (simulated).")
    }

    /** Pushes one realistic sleep-data tick from the watch. */
    fun fakeWatchSleepTick() {
        if (!HeartRateState.isWatchConnected) fakeWatchConnect()
        val soundDb = 28.0 + Random.nextDouble(-4.0, 6.0)
        val motion = Random.nextDouble(0.0, 0.35)
        HeartRateState.sleepSoundDb = (soundDb * 10).roundToInt() / 10.0
        HeartRateState.sleepMotionMag = (motion * 100).roundToInt() / 100.0
        HeartRateState.log("🔬 Watch tick: sound=${HeartRateState.sleepSoundDb} dB motion=${HeartRateState.sleepMotionMag}")
    }

    // ── Pitch / Audio Simulation ──────────────────────────────────────────────

    /**
     * Generates a 150 Hz sine-wave buffer (typical adult male speaking F0)
     * and feeds it to [tagger] as if it came from the microphone.
     */
    fun injectPitchSamples(tagger: PassiveTagger, f0Hz: Float = 150f, sampleRate: Int = 8000, durationMs: Int = 500) {
        HeartRateState.log("🔬 Debug: Injecting ${durationMs}ms of ${f0Hz} Hz sine wave into PassiveTagger")
        val numSamples = (sampleRate * durationMs / 1000)
        val buffer = ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * 0.6f * sin(2.0 * PI * f0Hz * i / sampleRate)).toInt().toShort()
        }
        tagger.addFrame(buffer, numSamples)
    }

    // ── Passive Voice Profile Simulation ─────────────────────────────────────

    /** Directly injects a plausible passive voice snapshot into HeartRateState. */
    fun injectFakeVoiceProfile() {
        HeartRateState.passiveVoiceProfile = PassiveVoiceSnapshot(
            medianF0 = 138f,
            minHz = 105f,
            maxHz = 225f,
            rangeLabel = "A2-C5",
            register = "bass_alto",
            sampleCount = 42
        )
        HeartRateState.log("🔬 Debug: Injected fake voice profile — bass_alto, F0=138 Hz, range A2-C5, 42 samples")
    }

    // ── Convenience: Simulate a Complete Session ──────────────────────────────

    /**
     * Runs a 30-second fake session end-to-end: connects BLE, accumulates HR,
     * then disconnects to trigger [compileAndSaveSession] in the service.
     * Call from the debug screen's "Simulate Full Session" button.
     */
    fun simulateFullSession(scope: CoroutineScope) {
        HeartRateState.log("🔬 Debug: Starting simulated 30s full session...")
        scope.launch(Dispatchers.Default) {
            // Set up fake connection state
            withContext(Dispatchers.Main) {
                HeartRateState.connectionState = "Connected"
                HeartRateState.deviceName = "Debug HR Sensor"
                HeartRateState.currentSessionId = "debug_session_${System.currentTimeMillis()}"
            }

            // Tick HR for 30 seconds
            for (i in 0 until 30) {
                val bpm = (72 + 20 * sin(i * 0.3)).toInt().coerceIn(55, 160)
                withContext(Dispatchers.Main) {
                    HeartRateState.currentBpm = bpm
                    HeartRateState.debugSimBpm = bpm
                    HeartRateState.debugInjectBpmTick++
                }
                delay(1000)
            }

            // Signal the service's debug observer to call compileAndSaveSession
            withContext(Dispatchers.Main) {
                HeartRateState.currentBpm = 0
                HeartRateState.debugEndSessionTick++
            }
            HeartRateState.log("🔬 Debug: 30s session complete. End-session signal sent — service will save to Room.")
        }
    }

    // ── Reset All ─────────────────────────────────────────────────────────────

    fun resetAll() {
        stopFakeHr()
        stopFakeGps()
        fakeWatchDisconnect()
        HeartRateState.currentBpm = 0
        HeartRateState.gpsSpeed = 0.0
        HeartRateState.gpsDistance = 0.0
        HeartRateState.gpsAltitude = 0.0
        HeartRateState.sleepSoundDb = 0.0
        HeartRateState.sleepMotionMag = 0.0
        HeartRateState.connectionState = "Disconnected"
        HeartRateState.deviceName = "None"
        HeartRateState.log("🔬 Debug: All simulations reset.")
    }
}
