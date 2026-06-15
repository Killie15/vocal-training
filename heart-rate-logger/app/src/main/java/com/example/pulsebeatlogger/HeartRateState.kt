package com.example.pulsebeatlogger

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.pulsebeatlogger.data.LearningItem
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object HeartRateState {
    private const val TAG = "PulseState"

    // Core application states (backed by Compose reactive delegates)
    var isServiceRunning by mutableStateOf(false)
    var connectionState by mutableStateOf("Disconnected") // "Scanning", "Connecting", "Connected", "Disconnected"
    var currentBpm by mutableStateOf(0)
    var deviceBattery by mutableStateOf(-1)
    var deviceName by mutableStateOf("None")
    var unsyncedSessionCount by mutableStateOf(0)
    var lastSyncStatus by mutableStateOf("Idle")
    var isWatchConnected by mutableStateOf(false)

    // Active Tracking Mode ("workout" vs "sleep")
    var trackingMode by mutableStateOf("workout")

    // Navigation and Preset Skill states
    var activeScreen by mutableStateOf("splash")
    var activeSkillName by mutableStateOf("none")
    val chatLog = androidx.compose.runtime.mutableStateListOf<Pair<String, String>>()
    val skillsMastery = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    // AI Coach adaptive threshold parameters
    var wristLimit by mutableStateOf(45f)
    var dailyDeficitModifier by mutableStateOf(1.0f)
    var speedRequired by mutableStateOf(5.0f)

    // Live GPS Workout telemetry
    var gpsSpeed by mutableStateOf(0.0)
    var gpsDistance by mutableStateOf(0.0)
    var gpsAltitude by mutableStateOf(0.0)

    // Live Sleep telemetry
    var sleepSoundDb by mutableStateOf(0.0)
    var sleepMotionMag by mutableStateOf(0.0)

    /** When true, HeartRateService releases the mic so [TunerEngine] can listen. */
    var tunerMicActive by mutableStateOf(false)
    /** Live pitch from service mic (ukulele/japanese) or tuner — for UI readout. */
    var livePitchHz by mutableStateOf(0f)
    var livePitchNote by mutableStateOf("--")
    var livePitchCents by mutableIntStateOf(0)

    // ── Adaptive Learning (SRS + Gemini) ─────────────────────────────────────
    /** Skills the user has generated via Gemini — persisted to SharedPreferences. */
    val savedCustomSkills = androidx.compose.runtime.mutableStateListOf<String>()

    val learningItems = androidx.compose.runtime.mutableStateListOf<LearningItem>()
    val weeklyReviewItems = androidx.compose.runtime.mutableStateListOf<LearningItem>()
    var passiveVoiceProfile by mutableStateOf<PassiveVoiceSnapshot?>(null)
    var geminiApiKey by mutableStateOf("")
    var weeklyReviewText by mutableStateOf("")
    var sessionFeedbackText by mutableStateOf("")
    var isGeminiLoading by mutableStateOf(false)
    var currentSessionId by mutableStateOf("")   // set when a session starts

    // ── Connectivity ──────────────────────────────────────────────────────────
    /** True when the device has an active internet connection. Updated by NetworkCallback in MainActivity. */
    var isOnline by mutableStateOf(true)

    // ── Gamification / Streak ─────────────────────────────────────────────────
    /** How many consecutive calendar days the user has practiced at least one item. */
    var streakDays by mutableStateOf(0)
    /** Cumulative experience points earned across all reviews and sessions. */
    var totalXp by mutableStateOf(0)
    /** Total individual SRS items reviewed across all time. */
    var totalItemsReviewed by mutableStateOf(0)
    /** ISO date string of the last day the user completed a review, e.g. "2026-06-14". */
    var lastPracticeDate by mutableStateOf("")
    /** Set of achievement IDs the user has unlocked — persisted to SharedPreferences. */
    val unlockedAchievements = androidx.compose.runtime.mutableStateListOf<String>()

    private val PRESET_SKILL_MODES = setOf("ukulele", "japanese", "pushup", "running", "stress")

    /** Room DB key for SRS queries — presets use [trackingMode], custom skills use [activeSkillName]. */
    fun dbSkillKey(displayName: String = activeSkillName): String {
        if (trackingMode in PRESET_SKILL_MODES) return trackingMode
        val lower = displayName.lowercase()
        if (lower in PRESET_SKILL_MODES) return lower
        return displayName.takeIf { it != "none" && it.isNotBlank() } ?: trackingMode
    }

    // ── Google assistant (Calendar + Sheets, one sign-in) ─────────────────────
    var googleSheetUrl by mutableStateOf("")
    var googleAccountEmail by mutableStateOf("")
    /** Incremented when Google sign-in or scope grant completes — UI can observe for toasts. */
    var googleAuthEvent by mutableIntStateOf(0)
    var googleDriveFolderId by mutableStateOf("")
    var driveSyncStatus by mutableStateOf("")
    var driveLastSyncMs by mutableLongStateOf(0L)
    var driveSyncRequested by mutableIntStateOf(0)
    /** When set, MainScreen shows install dialog for Drive-pulled APK. */
    var drivePendingUpdate by mutableStateOf<Pair<AppUpdateChecker.UpdateInfo, java.io.File>?>(null)
    /** When set, MainScreen shows install dialog (download from apkUrl first). */
    var httpUpdatePending by mutableStateOf<AppUpdateChecker.UpdateInfo?>(null)
    /** When true, Google Calendar + Sheets API calls use mock responses. */
    var calendarSandboxMode by mutableStateOf(false)
    var isAssistantLoading by mutableStateOf(false)
    val assistantChatLog = androidx.compose.runtime.mutableStateListOf<AssistantChatMessage>()

    // ── The System (persistent goals + chat) ──────────────────────────────────
    val systemGoals = androidx.compose.runtime.mutableStateListOf<com.example.pulsebeatlogger.data.UserGoal>()
    val systemMemoryBank = androidx.compose.runtime.mutableStateListOf<SystemMemoryItem>()
    val systemChatLog = androidx.compose.runtime.mutableStateListOf<SystemChatLine>()
    /** In-memory error queue flushed on next Drive sync. */
    val pendingErrorEnqueue = androidx.compose.runtime.mutableStateListOf<org.json.JSONObject>()
    var systemTotalChatCount by mutableIntStateOf(0)
    var isSystemLoading by mutableStateOf(false)

    // ── Debug / Sandbox Mode ──────────────────────────────────────────────────
    /**
     * When true: HeartRateService skips all real hardware (BLE, GPS, mic) and
     * DebugSimulator drives sensor state instead. GeminiService returns canned
     * offline responses. Safe to run on an emulator with zero physical sensors.
     */
    var debugMode by mutableStateOf(false)

    /** Set by DebugSimulator so the Debug screen can show the live fake BPM. */
    var debugSimBpm by mutableStateOf(72)

    /** Signals the service to inject one BPM tick from the simulator. */
    var debugInjectBpmTick by mutableStateOf(0)

    /**
     * Bumped by DebugSimulator when a simulated session should end and be saved.
     * The service's BPM observer calls compileAndSaveSession() when this advances.
     */
    var debugEndSessionTick by mutableStateOf(0)

    /** Dynamic configuration for streaming logs to PC */
    var debugServerIp = ""

    // Thread pool executor for network logging
    private val logExecutor = Executors.newSingleThreadExecutor()

    // In-memory debug logs shown in the UI for instant inspection
    val debugLogs = androidx.compose.runtime.mutableStateListOf<String>()

    /**
     * Shoves a debug statement into both standard Android Logcat and our in-app visual debug panel.
     * Optionally streams the log line to the PC debug server if IP is set.
     */
    fun log(message: String) {
        Log.d(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formattedLog = "[$time] $message"
        
        // Update list on UI thread (in case called from background thread)
        try {
            debugLogs.add(0, formattedLog)
            if (debugLogs.size > 200) {
                debugLogs.removeAt(debugLogs.size - 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update visual debug log: ${e.message}", e)
        }

        // Stream to PC debug server if configured
        val ip = debugServerIp
        if (ip.isNotEmpty()) {
            logExecutor.execute {
                sendRemoteLog(ip, message, time)
            }
        }
    }

    private fun sendRemoteLog(ip: String, msg: String, timestamp: String) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://$ip:3050/api/diagnostics/log")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val json = JSONObject().apply {
                put("message", msg)
                put("timestamp", timestamp)
            }.toString()

            connection.outputStream.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode // Triggers the network call
        } catch (e: Exception) {
            // Write strictly to Logcat here to avoid infinite log loops
            Log.w(TAG, "Failed to stream log to PC ($ip): ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
    
    fun logError(message: String, throwable: Throwable? = null, source: String = "app") {
        if (throwable != null) {
            Log.e(TAG, "$message - Error: ${throwable.message}", throwable)
            log("❌ ERROR: $message - ${throwable.message}")
        } else {
            Log.e(TAG, "❌ ERROR: $message")
            log("❌ ERROR: $message")
        }
        ErrorReportService.report(source, message, throwable)
    }
}
