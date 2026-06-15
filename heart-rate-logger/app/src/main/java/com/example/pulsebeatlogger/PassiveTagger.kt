package com.example.pulsebeatlogger

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Passively profiles the user's voice during any microphone session.
 *
 * Usage: call [addFrame] every time a new audio buffer arrives from AudioRecord,
 * then call [buildSnapshot] when the session ends to get a [PassiveVoiceSnapshot].
 *
 * The snapshot is stored as passiveTags JSON on the SkillSession and can be used
 * by future skills to pre-populate voice profile data without re-calibrating.
 */
class PassiveTagger(private val sampleRate: Int = 8000) {

    private val pitchSamples = mutableListOf<Float>()
    private var frameCount = 0
    private var _lastPitchHz = 0f

    // Only run pitch detection every N frames to limit CPU use (~5 second intervals at typical buffer rates)
    private val sampleEveryNFrames = 33

    /** Most recent successfully detected pitch in Hz, or 0 if none yet */
    fun lastPitchHz(): Float = _lastPitchHz

    fun reset() {
        HeartRateState.log("PassiveTagger: reset — cleared ${pitchSamples.size} samples, frameCount=$frameCount")
        pitchSamples.clear()
        frameCount = 0
    }

    /**
     * Process one audio buffer (ShortArray from AudioRecord).
     * Pitch detection runs every [sampleEveryNFrames] calls — ~2ms per detection.
     */
    fun addFrame(buffer: ShortArray, bytesRead: Int) {
        frameCount++
        if (frameCount % sampleEveryNFrames != 0) return
        if (bytesRead < 512) {
            HeartRateState.log("PassiveTagger: frame skipped — bytesRead=$bytesRead < 512 (too short)")
            return
        }

        val floatBuffer = FloatArray(minOf(bytesRead, buffer.size)) { buffer[it] / 32768.0f }
        val hz = detectPitchYin(floatBuffer, sampleRate)
        if (hz > 60f && hz < 1000f) {
            _lastPitchHz = hz
            pitchSamples.add(hz)
            HeartRateState.log("PassiveTagger: pitch detected ${"%.1f".format(hz)} Hz (sample #${pitchSamples.size}, frame=$frameCount)")
        } else if (hz > 0f) {
            HeartRateState.log("PassiveTagger: pitch ${"%.1f".format(hz)} Hz out of vocal range [60–1000 Hz] — discarded")
        } else {
            HeartRateState.log("PassiveTagger: no pitch detected in frame $frameCount (unvoiced/silence)")
        }
    }

    /**
     * Build a [PassiveVoiceSnapshot] from all accumulated pitch samples.
     * Returns null if fewer than 5 valid samples were collected.
     */
    fun buildSnapshot(): PassiveVoiceSnapshot? {
        HeartRateState.log("PassiveTagger: buildSnapshot — ${pitchSamples.size} total pitch samples from $frameCount frames")
        if (pitchSamples.size < 5) {
            HeartRateState.log("PassiveTagger: snapshot skipped — need ≥5 samples, only have ${pitchSamples.size}")
            return null
        }

        val sorted = pitchSamples.sorted()
        val medianF0 = sorted[sorted.size / 2]
        val minHz = sorted.first()
        val maxHz = sorted.last()

        val register = when {
            medianF0 < 160f -> "bass_alto"
            medianF0 < 220f -> "tenor_mezzo"
            else -> "soprano"
        }

        val (minNote, minOct, _) = hzToNoteInfo(minHz)
        val (maxNote, maxOct, _) = hzToNoteInfo(maxHz)
        val rangeLabel = "$minNote$minOct-$maxNote$maxOct"

        HeartRateState.log("PassiveTagger: snapshot built — medianF0=${"%.1f".format(medianF0)} Hz " +
            "min=${"%.1f".format(minHz)} max=${"%.1f".format(maxHz)} register=$register range=$rangeLabel")

        return PassiveVoiceSnapshot(
            medianF0 = medianF0,
            minHz = minHz,
            maxHz = maxHz,
            rangeLabel = rangeLabel,
            register = register,
            sampleCount = pitchSamples.size
        )
    }

    // ── YIN pitch detection algorithm ────────────────────────────────────────
    // Reference: de Cheveigné & Kawahara (2002), "YIN, a fundamental frequency estimator
    // for speech and music." Journal of the Acoustical Society of America.

    private fun detectPitchYin(buffer: FloatArray, sampleRate: Int) = pitchYin(buffer, sampleRate)

}

data class PassiveVoiceSnapshot(
    val medianF0: Float,      // Hz — fundamental frequency during natural speech
    val minHz: Float,
    val maxHz: Float,
    val rangeLabel: String,   // e.g. "A2-G4"
    val register: String,     // "bass_alto" | "tenor_mezzo" | "soprano"
    val sampleCount: Int
) {
    fun toJson(): String = JSONObject().apply {
        put("voiceF0", medianF0.toDouble())
        put("pitchMinHz", minHz.toDouble())
        put("pitchMaxHz", maxHz.toDouble())
        put("speakingRange", rangeLabel)
        put("register", register)
        put("sampleCount", sampleCount)
    }.toString()
}

/**
 * Package-level YIN pitch detection — shared by [PassiveTagger] and [TunerEngine].
 * Returns the detected fundamental frequency in Hz, or 0f if none found.
 */
fun pitchYin(buffer: FloatArray, sampleRate: Int): Float {
    val bufferSize = buffer.size
    val halfSize = bufferSize / 2
    if (halfSize < 2) return 0f
    val yinBuffer = FloatArray(halfSize)
    val threshold = 0.15f

    yinBuffer[0] = 1f
    var runningSum = 0f
    for (tau in 1 until halfSize) {
        var sum = 0f
        for (j in 0 until halfSize) {
            val delta = buffer[j] - buffer[j + tau]
            sum += delta * delta
        }
        runningSum += sum
        yinBuffer[tau] = if (runningSum == 0f) 1f else sum * tau / runningSum
    }

    var tauEstimate = -1
    for (tau in 2 until halfSize - 1) {
        if (yinBuffer[tau] < threshold) {
            tauEstimate = tau
            break
        }
    }
    if (tauEstimate == -1) {
        tauEstimate = (1 until halfSize).minByOrNull { yinBuffer[it] } ?: return 0f
    }

    val betterTau = if (tauEstimate in 1 until halfSize - 1) {
        val s0 = yinBuffer[tauEstimate - 1]
        val s1 = yinBuffer[tauEstimate]
        val s2 = yinBuffer[tauEstimate + 1]
        val denom = 2 * (2 * s1 - s2 - s0)
        if (kotlin.math.abs(denom) < 1e-6f) tauEstimate.toFloat()
        else tauEstimate + (s2 - s0) / denom
    } else tauEstimate.toFloat()

    return if (betterTau <= 0f) 0f else sampleRate / betterTau
}

/** Convert a frequency in Hz to a human-readable note name like "A4" or "C#3". */
fun hzToNoteInfo(hz: Float): Triple<String, Int, Int> {
    // returns (noteName, octave, centOffset)
    if (hz <= 0f) return Triple("--", 0, 0)
    val midi = 12.0 * kotlin.math.log2(hz / 440.0) + 69.0
    val roundedMidi = midi.roundToInt()
    val cents = ((midi - roundedMidi) * 100).toInt().coerceIn(-50, 50)
    val noteNames = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val noteIndex = ((roundedMidi % 12) + 12) % 12
    val octave = (roundedMidi / 12) - 1
    return Triple(noteNames[noteIndex], octave, cents)
}
