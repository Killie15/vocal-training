package com.example.pulsebeatlogger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Real-time pitch detector for ukulele / music toolkit.
 * Releases [HeartRateService] mic before opening its own [AudioRecord].
 */
class TunerEngine(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 22050
        private const val RMS_THRESHOLD = 0.0015f
        private const val MIC_SETTLE_MS = 450L
    }

    var noteName by mutableStateOf("--")
        private set

    var octave by mutableIntStateOf(4)
        private set

    var pitchHz by mutableFloatStateOf(0f)
        private set

    var centOffset by mutableIntStateOf(0)
        private set

    var tuneState by mutableStateOf(TuneState.SILENT)
        private set

    var isListening by mutableStateOf(false)
        private set

    /** Why the tuner isn't hearing you — shown in UI. */
    var statusMessage by mutableStateOf("")
        private set

    enum class TuneState { SILENT, IN_TUNE, CLOSE, OFF }

    private val mainHandler = Handler(Looper.getMainLooper())
    private @Volatile var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            postStatus(TuneState.SILENT, "--", 4, 0f, 0, "Microphone permission denied — allow in Settings")
            return
        }

        running = true
        isListening = true
        statusMessage = "Starting mic…"
        TunerMicCoordinator.requestExclusiveMic(context)

        thread = Thread({
            try {
                Thread.sleep(MIC_SETTLE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!running) return@Thread
            recordLoop()
        }, "TunerEngine").also { it.start() }
    }

    fun stop() {
        running = false
        isListening = false
        thread?.interrupt()
        thread = null
        TunerMicCoordinator.releaseExclusiveMic(context)
        postStatus(TuneState.SILENT, "--", 4, 0f, 0, "")
    }

    private fun recordLoop() {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            4096
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2
            )
        } catch (e: Exception) {
            HeartRateState.logError("TunerEngine: AudioRecord create failed", e)
            running = false
            isListening = false
            postStatus(
                TuneState.SILENT, "--", 4, 0f, 0,
                "Mic unavailable — turn off Auto-Tracking or retry"
            )
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running = false
            isListening = false
            HeartRateState.logError("TunerEngine: AudioRecord not initialized (mic may be in use)")
            postStatus(
                TuneState.SILENT, "--", 4, 0f, 0,
                "Mic busy — turn OFF Auto-Tracking on Train, then open Tune again"
            )
            return
        }

        record.startRecording()
        postStatus(TuneState.SILENT, "--", 4, 0f, 0, "Pluck a string near the phone mic")
        val buffer = ShortArray(bufSize)

        try {
            while (running) {
                val read = record.read(buffer, 0, bufSize)
                if (read <= 0) continue

                val floats = FloatArray(read) { buffer[it] / 32768f }
                var rmsSum = 0f
                for (s in floats) rmsSum += s * s
                val rms = kotlin.math.sqrt(rmsSum / floats.size)
                if (rms < RMS_THRESHOLD) {
                    updateState(0f, "Pluck louder — hold phone near sound hole")
                    continue
                }

                val hz = pitchYin(floats, SAMPLE_RATE)
                updateState(hz, if (hz > 0f) "" else "Could not detect pitch — try one clear note")
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    private fun updateState(hz: Float, msg: String = "") {
        if (hz < 50f || hz > 4200f) {
            postStatus(TuneState.SILENT, "--", 4, 0f, 0, msg.ifBlank { "Listening…" })
            return
        }
        val (note, oct, cents) = hzToNoteInfo(hz)
        val state = when {
            kotlin.math.abs(cents) <= 10 -> TuneState.IN_TUNE
            kotlin.math.abs(cents) <= 25 -> TuneState.CLOSE
            else -> TuneState.OFF
        }
        postStatus(state, note, oct, hz, cents, "")
        HeartRateState.livePitchHz = hz
        HeartRateState.livePitchNote = note
        HeartRateState.livePitchCents = cents
    }

    private fun postStatus(
        state: TuneState,
        note: String,
        oct: Int,
        hz: Float,
        cents: Int,
        message: String
    ) {
        mainHandler.post {
            tuneState = state
            noteName = note
            octave = oct
            pitchHz = hz
            centOffset = cents
            if (message.isNotEmpty()) statusMessage = message
            else if (state != TuneState.SILENT) statusMessage = ""
        }
    }
}
