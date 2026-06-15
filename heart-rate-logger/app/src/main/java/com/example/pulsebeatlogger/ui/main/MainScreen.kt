package com.example.pulsebeatlogger.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.pulsebeatlogger.HeartRateService
import com.example.pulsebeatlogger.HeartRateState
import com.example.pulsebeatlogger.SkillToolkitRegistry
import com.example.pulsebeatlogger.SkillType
import com.example.pulsebeatlogger.getSkillType
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.pulsebeatlogger.AppUpdateChecker
import com.example.pulsebeatlogger.UpdateChannel
import com.example.pulsebeatlogger.DriveSyncService
import com.example.pulsebeatlogger.DebugSimulator
import com.example.pulsebeatlogger.GeminiService
import com.example.pulsebeatlogger.GoogleAuthHelper
import com.example.pulsebeatlogger.SystemService
import com.example.pulsebeatlogger.JapaneseCurriculum
import com.example.pulsebeatlogger.PassiveTagger
import com.example.pulsebeatlogger.SRSEngine
import com.example.pulsebeatlogger.applyReview
import com.example.pulsebeatlogger.data.AppDatabase
import com.example.pulsebeatlogger.data.LearningItem
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: Any? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE) }
    
    // Load Settings Form Inputs
    var sheetUrlInput by remember { mutableStateOf(prefs.getString("googleSheetUrl", "") ?: "") }
    var geminiKeyInput by remember { mutableStateOf(prefs.getString("geminiApiKey", "") ?: "") }
    var ageInput by remember { mutableStateOf(prefs.getInt("age", 30).toString()) }
    var weightInput by remember { mutableStateOf(prefs.getFloat("weight", 70f).toString()) }
    var genderInput by remember { mutableStateOf(prefs.getString("gender", "male") ?: "male") }
    var debugServerIpInput by remember { mutableStateOf(prefs.getString("debugServerIp", "") ?: "") }
    var targetZoneInput by remember { mutableStateOf(prefs.getString("targetZone", "none") ?: "none") }
    var targetWeightInput by remember { mutableStateOf(prefs.getFloat("targetWeight", 65f).toString()) }
    var targetDateInput by remember { mutableStateOf(prefs.getString("targetDate", "2026-08-01") ?: "2026-08-01") }
    var desiredLifestyleInput by remember { mutableStateOf(prefs.getString("desiredLifestyle", "sedentary") ?: "sedentary") }

    val actualWorkouts = remember(HeartRateState.unsyncedSessionCount) { prefs.getInt("stats_total_workouts", 0) }
    val actualDistance = remember(HeartRateState.unsyncedSessionCount) { prefs.getFloat("stats_total_distance", 0f) }
    val actualProfile = if (actualWorkouts >= 2 && actualDistance >= 1000f) "Active Striver" else "Sedentary / Desk Worker"
    
    // Helper to calculate days remaining
    val daysRemaining = remember(targetDateInput) {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val targetDate = sdf.parse(targetDateInput.trim())
            val today = java.util.Calendar.getInstance().time
            val diff = (targetDate?.time ?: 0L) - today.time
            val days = (diff / (1000 * 60 * 60 * 24)).toInt()
            if (days > 0) days else 45
        } catch (e: Exception) {
            45
        }
    }
    
    val currentWeight = weightInput.toFloatOrNull() ?: 70f
    val targetWeight = targetWeightInput.toFloatOrNull() ?: 65f
    val weightDelta = currentWeight - targetWeight
    val totalDeficitNeeded = weightDelta * 7700f
    val dailyDeficitRequired = if (daysRemaining > 0) totalDeficitNeeded / daysRemaining else 800f
    
    // Coroutine scope for Gemini calls and DB access from the composable
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    // Advice popup state
    var hardwareAdvice by remember { mutableStateOf<String?>(null) }
    var adviceDetails by remember { mutableStateOf<String?>(null) }
    var showAdviceBanner by remember { mutableStateOf(false) }

    // Screen Nav triggers
    LaunchedEffect(HeartRateState.activeScreen) {
        if (HeartRateState.activeScreen == "splash") {
            HeartRateState.log("UI: Splash screen — waiting 1.5s then navigating to onboarding")
            delay(1500)
            HeartRateState.activeScreen = "onboarding"
            HeartRateState.log("UI: → onboarding")
        }
    }

    // Onboarding state
    var showOnboarding by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }

    // Load initial states and masteries
    LaunchedEffect(Unit) {
        val queueString = prefs.getString("offline_queue", "[]") ?: "[]"
        try {
            val queue = JSONArray(queueString)
            HeartRateState.unsyncedSessionCount = queue.length()
        } catch (e: Exception) {
            HeartRateState.unsyncedSessionCount = 0
        }
        HeartRateState.debugServerIp = prefs.getString("debugServerIp", "") ?: ""
        HeartRateState.activeSkillName = prefs.getString("activeSkillName", "none") ?: "none"
        val savedMode = prefs.getString("trackingMode", "workout") ?: "workout"
        HeartRateState.trackingMode = savedMode
        HeartRateState.geminiApiKey = prefs.getString("geminiApiKey", "") ?: ""
        HeartRateState.googleSheetUrl = prefs.getString("googleSheetUrl", "") ?: ""
        HeartRateState.googleDriveFolderId = prefs.getString(DriveSyncService.PREF_FOLDER_ID, "") ?: ""
        HeartRateState.googleAccountEmail = GoogleAuthHelper.accountEmail(context) ?: ""
        HeartRateState.calendarSandboxMode = prefs.getBoolean("calendarSandboxMode", false)
        HeartRateState.debugMode = prefs.getBoolean("debugMode", false)

        // Load gamification state
        com.example.pulsebeatlogger.GamificationHelper.loadFromPrefs(prefs)

        // Show onboarding on first launch
        if (!prefs.getBoolean("onboarding_complete", false)) {
            showOnboarding = true
        }
        // Restore saved custom skills list (comma-separated)
        val customSkillsCsv = prefs.getString("customSkills", "") ?: ""
        HeartRateState.savedCustomSkills.clear()
        if (customSkillsCsv.isNotBlank()) {
            HeartRateState.savedCustomSkills.addAll(customSkillsCsv.split(",").filter { it.isNotBlank() })
        }
        // Migrate: if activeSkillName was created before "My Skills" existed, add it now
        val presetKeys = setOf("ukulele", "japanese", "pushup", "running", "stress", "none", "workout")
        val activeName = HeartRateState.activeSkillName
        if (activeName.isNotBlank() && activeName != "none" &&
            !presetKeys.contains(activeName.lowercase()) &&
            !HeartRateState.savedCustomSkills.contains(activeName)) {
            HeartRateState.savedCustomSkills.add(0, activeName)
            val csv = HeartRateState.savedCustomSkills.joinToString(",")
            prefs.edit().putString("customSkills", csv).apply()
        }
        if (HeartRateState.debugMode) {
            HeartRateState.log("🔬 Debug mode restored from prefs — sandbox active on launch")
        }

        // Initial chat welcome
        if (HeartRateState.chatLog.isEmpty()) {
            HeartRateState.chatLog.add(Pair("AI Coach", "Hi! Tell me what you want to learn today, or describe how you feel about this session."))
        }

        // Initialize mastery variables
        loadSkillsMastery(prefs)

        // Seed Japanese curriculum if not yet in DB
        val itemDao = db.learningItemDao()
        if (savedMode == "japanese" || HeartRateState.activeSkillName.contains("Japanese", ignoreCase = true)) {
            val count = itemDao.countForSkill("japanese")
            if (count == 0) {
                itemDao.insertAll(JapaneseCurriculum.items)
                HeartRateState.log("Seeded Japanese curriculum (${JapaneseCurriculum.items.size} items)")
            }
        }

        // Load due items — use DB key (presets store under trackingMode, custom under activeSkillName)
        val skillKey = HeartRateState.dbSkillKey()
        val dueItems = itemDao.getDueItems(skillKey, System.currentTimeMillis(), limit = 10)
        HeartRateState.learningItems.clear()
        HeartRateState.learningItems.addAll(dueItems)
        HeartRateState.log("Loaded ${dueItems.size} due items for skill='$skillKey'")

        SystemService.loadFromDb(db)

        delay(800)
        if (HeartRateState.isOnline) {
            val updateResult = UpdateChannel.checkForUpdate(context, prefs)
            when {
                updateResult.localApk != null && updateResult.remoteInfo != null ->
                    HeartRateState.drivePendingUpdate = updateResult.remoteInfo to updateResult.localApk
                updateResult.remoteInfo != null ->
                    HeartRateState.httpUpdatePending = updateResult.remoteInfo
            }
        }

        HeartRateState.driveSyncRequested++
    }

    LaunchedEffect(HeartRateState.driveSyncRequested) {
        if (!HeartRateState.isOnline) return@LaunchedEffect
        if (!GoogleAuthHelper.isGoogleConnected(context)) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        val result = DriveSyncService.sync(context, db, prefs)
        // Drive may still offer update if GitHub did not
        if (HeartRateState.httpUpdatePending == null && HeartRateState.drivePendingUpdate == null) {
            if (result.updateAvailable != null && result.apkFile != null) {
                HeartRateState.drivePendingUpdate = result.updateAvailable to result.apkFile
            } else if (result.updateBlockedReason != null) {
                Toast.makeText(context, result.updateBlockedReason.take(200), Toast.LENGTH_LONG).show()
            }
        }
    }

    HeartRateState.httpUpdatePending?.let { update ->
        AlertDialog(
            onDismissRequest = { HeartRateState.httpUpdatePending = null },
            title = { Text("Update available (${update.versionName})") },
            text = {
                Text(
                    "${update.notes}\n\nDownloads from GitHub. Tap Install — your data stays on the phone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (updateDownloading) return@TextButton
                        coroutineScope.launch {
                            updateDownloading = true
                            try {
                                if (UpdateChannel.downloadAndInstall(context, update)) {
                                    HeartRateState.httpUpdatePending = null
                                } else {
                                    Toast.makeText(context, "Download failed — check internet", Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                updateDownloading = false
                            }
                        }
                    }
                ) { Text(if (updateDownloading) "Downloading…" else "Install") }
            },
            dismissButton = {
                TextButton(onClick = { HeartRateState.httpUpdatePending = null }) { Text("Later") }
            }
        )
    }

    HeartRateState.drivePendingUpdate?.let { (update, apkFile) ->
        AlertDialog(
            onDismissRequest = { HeartRateState.drivePendingUpdate = null },
            title = { Text("Update available (${update.versionName})") },
            text = { Text("${update.notes}\n\nTap Install — your goals and memories stay on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    AppUpdateChecker.launchInstall(context, apkFile)
                    HeartRateState.drivePendingUpdate = null
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { HeartRateState.drivePendingUpdate = null }) { Text("Later") }
            }
        )
    }

    // Re-evaluate advice whenever mode or device connections shift
    LaunchedEffect(HeartRateState.trackingMode, HeartRateState.connectionState, HeartRateState.isWatchConnected) {
        val isBle = HeartRateState.connectionState == "Connected"
        val isWatch = HeartRateState.isWatchConnected
        
        val advice = when (HeartRateState.trackingMode) {
            "workout", "running" -> {
                if (!isBle) {
                    Pair(
                        "🏋️ Workout: BLE Strap Disconnected",
                        "Recommended: Phone + ECG Chest Strap. Connect your chest strap to get accurate heart rate!"
                    )
                } else {
                    Pair(
                        "🏋️ Workout Setup: Optimal",
                        "Connected: BLE Strap. The app will log EKG-accurate heart rate."
                    )
                }
            }
            "sleep" -> {
                if (isWatch && isBle) {
                    Pair(
                        "🌙 Sleep Setup: Over-Connected",
                        "Connected: Watch + BLE Strap. Note: You only need the Phone + Watch for sleep. Turn strap off to save battery!"
                    )
                } else if (!isWatch) {
                    Pair(
                        "🌙 Sleep: Phone Only Active",
                        "Tracking: Bed motion + decibels only. Recommended: Connect Watch for heart rate/HRV stages."
                    )
                } else {
                    Pair(
                        "🌙 Sleep Setup: Optimal",
                        "Connected: Watch. Tracking sleep stage recovery details."
                    )
                }
            }
            "ukulele" -> {
                if (!isWatch) {
                    Pair(
                        "🎸 Ukulele: Watch Disconnected",
                        "Recommended: Smartwatch + Phone. Watch is required to check left-wrist fretting angles."
                    )
                } else {
                    Pair(
                        "🎸 Ukulele Setup: Optimal",
                        "Connected: Watch + Phone. Wrist Euler angle and acoustic pitch tracking active."
                    )
                }
            }
            "japanese" -> {
                Pair(
                    "🗣️ Japanese Setup: Optimal",
                    "Requires Phone Microphone only. Speak naturally and clearly."
                )
            }
            else -> null
        }

        if (advice != null) {
            hardwareAdvice = advice.first
            adviceDetails = advice.second
            showAdviceBanner = true
            delay(6000)
            showAdviceBanner = false
        } else {
            showAdviceBanner = false
        }
    }

    Scaffold(
        bottomBar = {
            if (HeartRateState.activeScreen != "splash" && !showOnboarding) {
                BottomAppBar(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { HeartRateState.log("UI: nav → onboarding"); HeartRateState.activeScreen = "onboarding" },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (HeartRateState.activeScreen == "onboarding") MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (HeartRateState.activeScreen == "onboarding") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("🔍 Find", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Button(
                            onClick = { HeartRateState.log("UI: nav → practice"); HeartRateState.activeScreen = "practice" },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (HeartRateState.activeScreen == "practice") MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (HeartRateState.activeScreen == "practice") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("🏋️ Train", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Button(
                            onClick = { HeartRateState.log("UI: nav → system"); HeartRateState.activeScreen = "system" },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (HeartRateState.activeScreen == "system") Color(0xFF0F172A) else Color.Transparent,
                                contentColor = if (HeartRateState.activeScreen == "system") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("◈ System", fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Button(
                            onClick = { HeartRateState.log("UI: nav → mastery"); HeartRateState.activeScreen = "mastery" },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (HeartRateState.activeScreen == "mastery") MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (HeartRateState.activeScreen == "mastery") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("🏆 Mastery", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                HeartRateState.log("UI: nav → toolkit")
                                HeartRateState.activeScreen = "toolkit"
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (HeartRateState.activeScreen == "toolkit") MaterialTheme.colorScheme.secondary else Color.Transparent,
                                contentColor = if (HeartRateState.activeScreen == "toolkit") MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                SkillToolkitRegistry.bottomBarLabel(HeartRateState.trackingMode, HeartRateState.activeSkillName),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        Button(
                            onClick = { HeartRateState.log("UI: nav → debug lab"); HeartRateState.activeScreen = "debug" },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (HeartRateState.activeScreen == "debug") Color(0xFF7C3AED) else Color.Transparent,
                                contentColor = if (HeartRateState.activeScreen == "debug") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("🔬 Lab", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->

        // ── Full-screen overlays (onboarding) ──────────────────────────────
        if (showOnboarding) {
            OnboardingOverlay(prefs = prefs, onComplete = { showOnboarding = false })
            return@Scaffold
        }

        when (HeartRateState.activeScreen) {
            "splash" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("💓", fontSize = 80.sp)
                        Text(
                            text = "PulseBeat Learner",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Custom Skill Practice & Telemetry",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            
            "onboarding" -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "What do you want to learn today?",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    var searchQuery by remember { mutableStateOf("") }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Enter skill to learn (e.g. Ukulele, Japanese...)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Passive voice insight — show when voice data exists and skill is MUSIC/LANGUAGE
                    val voiceProfile = HeartRateState.passiveVoiceProfile
                    val querySkillType = if (searchQuery.length >= 3) getSkillType(searchQuery) else null
                    if (voiceProfile != null && (querySkillType == SkillType.MUSIC || querySkillType == SkillType.LANGUAGE)) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎙️", fontSize = 20.sp)
                            Column {
                                Text("Voice data ready", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                                Text("${voiceProfile.sampleCount} samples collected • Speaking range ${voiceProfile.rangeLabel} (${voiceProfile.register.replace("_"," ")}). Your AI curriculum will be personalized using this data.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                            }
                        }
                    }
                    
                    Button(
                        onClick = {
                            val query = searchQuery.trim().lowercase(java.util.Locale.US)
                            if (query.isNotEmpty()) {
                                val matched = matchAndLoadPreset(context, prefs, query)
                                if (matched) {
                                    val mode = HeartRateState.trackingMode
                                    // Seed Japanese curriculum if freshly loaded
                                    if (mode == "japanese") {
                                        coroutineScope.launch {
                                            val dao = db.learningItemDao()
                                            if (dao.countForSkill("japanese") == 0) dao.insertAll(JapaneseCurriculum.items)
                                            val due = dao.getDueItems("japanese", System.currentTimeMillis(), 10)
                                            HeartRateState.learningItems.clear()
                                            HeartRateState.learningItems.addAll(due)
                                        }
                                    }
                                    HeartRateState.activeScreen = "practice"
                                } else {
                                    val skillName = searchQuery.trim()
                                    HeartRateState.activeSkillName = skillName
                                    HeartRateState.trackingMode = "workout"
                                    prefs.edit().putString("trackingMode", "workout").putString("activeSkillName", skillName).apply()

                                    // Generate Gemini profile for custom skill
                                    val apiKey = HeartRateState.geminiApiKey
                                    if (apiKey.isNotEmpty()) {
                                        HeartRateState.isGeminiLoading = true
                                        HeartRateState.chatLog.clear()
                                        HeartRateState.chatLog.add(Pair("AI Coach", "Generating your personalised $skillName curriculum..."))
                                        coroutineScope.launch {
                                            val voiceCtx = HeartRateState.passiveVoiceProfile?.let {
                                                "User voice data: speaking F0=${it.medianF0.toInt()} Hz, range=${it.rangeLabel}, register=${it.register}."
                                            } ?: ""
                                            val profileJson = GeminiService.generateProfile(apiKey, skillName, voiceCtx)
                                            try {
                                                val parsed = org.json.JSONObject(profileJson)
                                                val welcome = parsed.optString("welcome", "Welcome to $skillName!")
                                                val itemsArr = parsed.optJSONArray("items")
                                                if (itemsArr != null) {
                                                    val newItems = (0 until itemsArr.length()).map { i ->
                                                        val obj = itemsArr.getJSONObject(i)
                                                        com.example.pulsebeatlogger.data.LearningItem(
                                                            id = "gemini_${skillName}_$i",
                                                            skillName = skillName,
                                                            category = obj.optString("category", "custom"),
                                                            content = obj.optString("content", "Item $i"),
                                                            contentJson = obj.optString("contentJson", "{}"),
                                                            tags = obj.optJSONArray("tags")?.toString() ?: "[]",
                                                            nextReviewDate = System.currentTimeMillis()
                                                        )
                                                    }
                                                    db.learningItemDao().insertAll(newItems)
                                                    HeartRateState.learningItems.clear()
                                                    HeartRateState.learningItems.addAll(newItems)
                                                    // Save skill to the "My Skills" list if not already there
                                                    if (!HeartRateState.savedCustomSkills.contains(skillName)) {
                                                        HeartRateState.savedCustomSkills.add(0, skillName)
                                                        val csv = HeartRateState.savedCustomSkills.joinToString(",")
                                                        prefs.edit().putString("customSkills", csv).apply()
                                                        HeartRateState.log("Saved '$skillName' to My Skills (${HeartRateState.savedCustomSkills.size} total)")
                                                    }
                                                }
                                                GeminiService.clearHistory()
                                                HeartRateState.chatLog.clear()
                                                HeartRateState.chatLog.add(Pair("AI Coach", welcome))
                                            } catch (e: Exception) {
                                                HeartRateState.chatLog.clear()
                                                HeartRateState.chatLog.add(Pair("AI Coach", "Welcome to $skillName! Let's get started."))
                                            }
                                            HeartRateState.isGeminiLoading = false
                                        }
                                    } else {
                                        Toast.makeText(context, "Created skill: $skillName. Add a Gemini API key for AI curriculum.", Toast.LENGTH_LONG).show()
                                    }
                                    HeartRateState.activeScreen = "practice"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !HeartRateState.isGeminiLoading
                    ) {
                        if (HeartRateState.isGeminiLoading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Generating AI Curriculum...", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text("Open Practice Arena", fontWeight = FontWeight.Bold)
                        }
                    }

                    // ── Offline banner ────────────────────────────────────────────
                    if (!HeartRateState.isOnline) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFFF59E0B).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📵", fontSize = 18.sp)
                            Column {
                                Text("You're offline", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFFB45309))
                                Text("Curriculum generation and AI feedback are paused. Practice your existing items normally.", fontSize = 11.sp, color = Color(0xFFB45309))
                            }
                        }
                    }

                    // ── Streak & XP bar ───────────────────────────────────────────
                    if (HeartRateState.streakDays > 0 || HeartRateState.totalXp > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🔥 ${HeartRateState.streakDays}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEA580C))
                                Text("day streak", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⭐ ${HeartRateState.totalXp}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED))
                                Text("total XP", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📖 ${HeartRateState.totalItemsReviewed}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("reviewed", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // ── First-launch API key prompt ────────────────────────────────
                    if (HeartRateState.geminiApiKey.isBlank()) {
                        var showKeyInput by remember { mutableStateOf(false) }
                        var keyDraft by remember { mutableStateOf("") }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("🤖 Set up AI Coach", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                Text("Add your Gemini API key to unlock AI-generated curricula for any skill.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!showKeyInput) {
                                    Button(onClick = { showKeyInput = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                                        Text("Enter API Key")
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = keyDraft, onValueChange = { keyDraft = it },
                                        label = { Text("Gemini API Key") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        trailingIcon = {
                                            if (keyDraft.isNotBlank()) {
                                                androidx.compose.material3.IconButton(onClick = {
                                                    val key = keyDraft.trim()
                                                    prefs.edit().putString("geminiApiKey", key).apply()
                                                    HeartRateState.geminiApiKey = key
                                                    showKeyInput = false
                                                    HeartRateState.log("API key saved from onboarding banner")
                                                    Toast.makeText(context, "API key saved! AI Coach is ready.", Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Text("Save", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    )
                                    Text("Get a free key at aistudio.google.com", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Display last practiced skill if available
                    if (HeartRateState.activeSkillName != "none") {
                        Card(
                            onClick = { HeartRateState.activeScreen = "practice" },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Last practiced skill:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(HeartRateState.activeSkillName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    
                    // My Skills — AI-generated curricula the user has already created
                    if (HeartRateState.savedCustomSkills.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("My Skills:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            HeartRateState.savedCustomSkills.forEach { skillName ->
                                Card(
                                    onClick = {
                                        // Reload this skill's existing items from DB without re-generating
                                        coroutineScope.launch {
                                            HeartRateState.activeSkillName = skillName
                                            HeartRateState.trackingMode = "workout"
                                            prefs.edit()
                                                .putString("activeSkillName", skillName)
                                                .putString("trackingMode", "workout")
                                                .apply()
                                            val dbKey = HeartRateState.dbSkillKey(skillName)
                                            val existing = db.learningItemDao().getDueItems(dbKey, System.currentTimeMillis(), 10)
                                            HeartRateState.learningItems.clear()
                                            HeartRateState.learningItems.addAll(existing)
                                            val all = db.learningItemDao().getAllForSkill(dbKey)
                                            HeartRateState.skillsMastery[skillName] = SRSEngine.masteryScore(all)
                                            HeartRateState.chatLog.clear()
                                            HeartRateState.chatLog.add(Pair("AI Coach", "Welcome back to $skillName! You have ${existing.size} items due today."))
                                            HeartRateState.log("Resumed '$skillName': ${existing.size} due, ${all.size} total items")
                                        }
                                        HeartRateState.activeScreen = "practice"
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🧠", fontSize = 16.sp)
                                            Text(skillName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        }
                                        val mastery = HeartRateState.skillsMastery[skillName] ?: 0
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("$mastery%", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                                            Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Quick Preset templates
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Pre-made Templates:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val suggestions = listOf(
                            Pair("🎸 Ukulele Strum Posture", "ukulele"),
                            Pair("🗣️ Japanese Accent Coach", "japanese"),
                            Pair("🏋️ Push-Up Form Trainer", "pushup"),
                            Pair("🏃 Running Gait Sync", "running"),
                            Pair("🧘 Panic Stress Prevention", "stress")
                        )
                        suggestions.forEach { (label, key) ->
                            Card(
                                onClick = {
                                    loadPresetByName(context, prefs, key)
                                    HeartRateState.activeScreen = "practice"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            
            "practice" -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(innerPadding)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Skill Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Practice Arena",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = HeartRateState.activeSkillName,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { HeartRateState.activeScreen = "toolkit" },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Toolkit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                            Button(
                                onClick = { HeartRateState.activeScreen = "onboarding" },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Switch Skill", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }

                    // Floating advice banner
                    if (showAdviceBanner && hardwareAdvice != null && adviceDetails != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(hardwareAdvice!!, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(adviceDetails!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 1. Sensor Readiness Card
                    var sensorWarningDismissed by remember(HeartRateState.activeSkillName) { mutableStateOf(false) }
                    SensorReadinessCard(
                        mode = HeartRateState.trackingMode,
                        skillName = HeartRateState.activeSkillName,
                        isMicActive = HeartRateState.isServiceRunning,
                        isWatchActive = HeartRateState.isWatchConnected,
                        isStrapActive = HeartRateState.connectionState == "Connected",
                        warningDismissed = sensorWarningDismissed,
                        onDismissWarning = { sensorWarningDismissed = true }
                    )

                    // 2. Background Service Toggle Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Tracking Service",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(
                                                if (HeartRateState.isServiceRunning) Color(0xFF10B981) else Color(0xFFEF4444),
                                                shape = RoundedCornerShape(5.dp)
                                            )
                                    )
                                    Text(
                                        text = if (HeartRateState.isServiceRunning) "ACTIVE (Tracking)" else "STOPPED",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            
                            Switch(
                                checked = HeartRateState.isServiceRunning,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                                        val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                                        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                        
                                        if (!hasScan || !hasConnect || !hasLocation) {
                                            HeartRateState.log("UI: ⚠️ Service start blocked — missing permissions: " +
                                                "BT_SCAN=$hasScan BT_CONNECT=$hasConnect LOCATION=$hasLocation (debugMode=${HeartRateState.debugMode})")
                                            Toast.makeText(context, "Bluetooth & Location permissions missing!", Toast.LENGTH_LONG).show()
                                        } else {
                                            HeartRateState.log("UI: ▶ Starting HeartRateService — mode=${HeartRateState.trackingMode} skill=${HeartRateState.activeSkillName} debugMode=${HeartRateState.debugMode}")
                                            val intent = Intent(context, HeartRateService::class.java)
                                            context.startService(intent)
                                        }
                                    } else {
                                        HeartRateState.log("UI: ⏹ Stopping HeartRateService — was recording=${ HeartRateState.currentSessionId.isNotEmpty() }")
                                        val intent = Intent(context, HeartRateService::class.java)
                                        context.stopService(intent)
                                    }
                                }
                            )
                        }
                    }

                    // Live Telemetry Readout Card
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Live Telemetry Metrics",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = "Sensor: ${HeartRateState.deviceName}", fontSize = 13.sp)
                                    Text(
                                        text = "BLE: ${HeartRateState.connectionState}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (HeartRateState.connectionState == "Connected") Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (HeartRateState.currentBpm > 0) HeartRateState.currentBpm.toString() else "--",
                                        fontSize = 40.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (HeartRateState.currentBpm > 0) Color(0xFFFF3366) else Color.Gray
                                    )
                                    Text(text = "BPM", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Box(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)))
                            
                            val mode = HeartRateState.trackingMode
                            if (mode == "running" || mode == "workout") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text(text = "${HeartRateState.gpsSpeed}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(text = "Speed (km/h)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text(text = "${HeartRateState.gpsDistance}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(text = "Distance (m)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text(text = "${HeartRateState.sleepSoundDb}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(text = "Sound Level (dB)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text(text = "${HeartRateState.sleepMotionMag}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(text = "Wrist Motion (G)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (HeartRateState.trackingMode == "ukulele" || getSkillType(HeartRateState.activeSkillName) == SkillType.MUSIC) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (HeartRateState.livePitchHz > 0f)
                                                "🎵 ${HeartRateState.livePitchNote} · ${HeartRateState.livePitchHz.toInt()} Hz"
                                            else if (HeartRateState.isServiceRunning)
                                                "🎙️ Mic on — pluck a string (or open 🎸 Kit → Tune)"
                                            else
                                                "🎙️ Turn ON Auto-Tracking for live pitch, or use 🎸 Kit → Tune",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (HeartRateState.livePitchHz > 0f) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Scorecard and Verdict
                    val scoreMetrics = calculateLiveScore()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("🎯 Practice Scorecard", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary)
                                Text(
                                    text = "${scoreMetrics.first} / 100",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    color = if (scoreMetrics.first >= 80) Color(0xFF10B981) else if (scoreMetrics.first >= 60) Color(0xFFF59E0B) else Color(0xFFEF4444)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Verdict:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(scoreMetrics.second, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(scoreMetrics.third, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // SRS Learning Task List
                    if (HeartRateState.learningItems.isNotEmpty()) {
                        SrsTaskList(
                            items = HeartRateState.learningItems,
                            coroutineScope = coroutineScope,
                            context = context,
                            onReview = { item, rating ->
                                coroutineScope.launch {
                                    HeartRateState.log("UI: SRS rate — item='${item.content.take(30)}' rating=$rating skill=${HeartRateState.trackingMode}")
                                    db.learningItemDao().applyReview(item, rating)

                                    // Track fails / resets for adaptive difficulty
                                    if (rating == 1) {
                                        db.learningItemDao().incrementFails(item.id)
                                        // Check if this item now needs regeneration (3 consecutive fails)
                                        val refreshed = db.learningItemDao().getAllForSkill(HeartRateState.dbSkillKey())
                                            .find { it.id == item.id }
                                        if (refreshed != null && refreshed.consecutiveFails >= 3 && refreshed.regenerationCount < 2 && HeartRateState.isOnline) {
                                            HeartRateState.log("UI: Adaptive — regenerating simplified item '${item.content.take(30)}'")
                                            val simplified = GeminiService.simplifyItem(HeartRateState.geminiApiKey, HeartRateState.activeSkillName, item)
                                            if (simplified != null) {
                                                db.learningItemDao().updateContent(item.id, simplified.first, simplified.second)
                                                HeartRateState.log("UI: Adaptive ✓ item simplified to '${simplified.first.take(40)}'")
                                            }
                                        }
                                    } else if (rating >= 3) {
                                        db.learningItemDao().resetFails(item.id)
                                    }

                                    // Award XP and update streak
                                    com.example.pulsebeatlogger.GamificationHelper.onItemReviewed(prefs, rating)

                                    // Refresh due items — preset skills use trackingMode as DB key
                                    val skillKey = HeartRateState.dbSkillKey()
                                    val updated = db.learningItemDao().getDueItems(
                                        skillKey, System.currentTimeMillis(), 10
                                    )
                                    HeartRateState.learningItems.clear()
                                    HeartRateState.learningItems.addAll(updated)
                                    HeartRateState.log("UI: SRS refresh — ${updated.size} items still due (skill=$skillKey)")
                                    // Update mastery score
                                    val all = db.learningItemDao().getAllForSkill(skillKey)
                                    val score = SRSEngine.masteryScore(all)
                                    HeartRateState.skillsMastery[HeartRateState.trackingMode] = score
                                    HeartRateState.log("UI: Mastery updated → $score/100 (${all.size} items total)")
                                }
                            }
                        )
                    }

                    // AI Coach Dialogue box
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "💬 AI Coach Dialogue & Feedback Log",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            val chatScrollState = rememberScrollState()
                            val chatLogSize = HeartRateState.chatLog.size
                            LaunchedEffect(chatLogSize) {
                                chatScrollState.animateScrollTo(chatScrollState.maxValue)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(chatScrollState),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    HeartRateState.chatLog.forEach { message ->
                                        val isAi = message.first == "AI Coach"
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (isAi) MaterialTheme.colorScheme.secondaryContainer 
                                                        else MaterialTheme.colorScheme.primaryContainer,
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    text = "${message.first}: ${message.second}",
                                                    fontSize = 12.sp,
                                                    color = if (isAi) MaterialTheme.colorScheme.onSecondaryContainer 
                                                            else MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            var inputMsg by remember { mutableStateOf("") }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = inputMsg,
                                    onValueChange = { inputMsg = it },
                                    placeholder = { Text("Too easy? Wrist hurts? Tell coach...", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        val userText = inputMsg.trim()
                                        if (userText.isNotEmpty() && !HeartRateState.isGeminiLoading) {
                                            HeartRateState.chatLog.add(Pair("User", userText))
                                            inputMsg = ""

                                            // Always apply local threshold adjustments for immediacy
                                            val q = userText.lowercase(java.util.Locale.US)
                                            when {
                                                q.contains("too easy") || q.contains("harder") -> {
                                                    HeartRateState.wristLimit = 30f
                                                    HeartRateState.dailyDeficitModifier = 1.25f
                                                }
                                                q.contains("wrist hurts") || q.contains("pain") || q.contains("injured") -> {
                                                    HeartRateState.wristLimit = 60f
                                                    HeartRateState.speedRequired = 2.0f
                                                }
                                                q.contains("tired") || q.contains("fatigued") || q.contains("exhausted") -> {
                                                    HeartRateState.dailyDeficitModifier = 0.75f
                                                }
                                            }

                                            val apiKey = HeartRateState.geminiApiKey
                                            HeartRateState.log("UI: Chat send — msg='${userText.take(50)}' apiKey=${if (apiKey.isEmpty()) "NOT SET" else if (apiKey == "debug") "debug(mock)" else "set(${apiKey.length} chars)"}")
                                            if (apiKey.isNotEmpty()) {
                                                HeartRateState.isGeminiLoading = true
                                                coroutineScope.launch {
                                                    val sessionCtx = "HR: ${HeartRateState.currentBpm} BPM, mode: ${HeartRateState.trackingMode}, score: ${calculateLiveScore().first}/100"
                                                    HeartRateState.log("UI: Chat → Gemini | skill=${HeartRateState.activeSkillName} ctx='$sessionCtx'")
                                                    val reply = GeminiService.chat(apiKey, userText, HeartRateState.activeSkillName, sessionCtx)
                                                    HeartRateState.log("UI: Chat ← Gemini reply '${reply.take(60)}'")
                                                    HeartRateState.chatLog.add(Pair("AI Coach", reply))
                                                    HeartRateState.isGeminiLoading = false
                                                }
                                            } else {
                                                HeartRateState.log("UI: Chat fallback (no API key) — msg='${userText.take(30)}'")
                                                // Fallback without API key
                                                val fallback = when {
                                                    q.contains("too easy") || q.contains("harder") -> "Difficulty targets increased. Wrist angle limit tightened to 30°. Keep pushing!"
                                                    q.contains("wrist hurts") || q.contains("pain") -> "Injury mode active. Wrist limit relaxed to 60°. Take it easy and rest if needed."
                                                    q.contains("tired") || q.contains("fatigued") -> "Fatigue mode active. Deficit target reduced. Rest is as important as training."
                                                    else -> "Got it: \"$userText\". (Add a Gemini API key in settings for smarter coaching.)"
                                                }
                                                HeartRateState.chatLog.add(Pair("AI Coach", fallback))
                                            }
                                        }
                                    },
                                    enabled = !HeartRateState.isGeminiLoading
                                ) {
                                    if (HeartRateState.isGeminiLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Send")
                                    }
                                }
                            }
                        }
                    }

                    // 4. Saved Context Slots
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "📂 Saved Context Slots (Max 10)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            var slotNameInput by remember { mutableStateOf("") }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = slotNameInput,
                                    onValueChange = { slotNameInput = it },
                                    placeholder = { Text("Slot Name...", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        if (slotNameInput.trim().isNotEmpty()) {
                                            saveSlot(context, prefs, slotNameInput)
                                            slotNameInput = ""
                                        }
                                    }
                                ) {
                                    Text("💾 Save")
                                }
                            }
                            
                            // Display Slots list
                            val slotsStr = prefs.getString("pulsebeat_custom_slots", "[]") ?: "[]"
                            val slotsArray = remember(slotsStr) {
                                try { JSONArray(slotsStr) } catch(e: Exception) { JSONArray() }
                            }
                            
                            if (slotsArray.length() == 0) {
                                Text(
                                    text = "No saved custom slots yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (i in 0 until slotsArray.length()) {
                                        val slotObj = slotsArray.getJSONObject(i)
                                        val name = slotObj.getString("name")
                                        val mode = slotObj.getString("mode")
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).clickable { loadSlot(context, prefs, i) }) {
                                                Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Text("Mode: $mode", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(
                                                onClick = { deleteSlot(context, prefs, i) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("🗑️", fontSize = 14.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Configuration form collapsible details
                    var showConfigs by remember { mutableStateOf(false) }
                    Button(
                        onClick = { showConfigs = !showConfigs },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(if (showConfigs) "🙈 Hide Target Configurations" else "⚙️ Show Target Configurations", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }

                    if (showConfigs) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Sync & Settings", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                OutlinedTextField(value = geminiKeyInput, onValueChange = { geminiKeyInput = it }, label = { Text("Gemini API Key (for AI Coach)") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = sheetUrlInput, onValueChange = { sheetUrlInput = it }, label = { Text("GSheets URL") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = debugServerIpInput, onValueChange = { debugServerIpInput = it }, label = { Text("PC Debug IP") }, modifier = Modifier.fillMaxWidth())
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = ageInput, onValueChange = { ageInput = it }, label = { Text("Age") }, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = weightInput, onValueChange = { weightInput = it }, label = { Text("Weight (kg)") }, modifier = Modifier.weight(1f))
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = targetWeightInput, onValueChange = { targetWeightInput = it }, label = { Text("Target Weight") }, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = targetDateInput, onValueChange = { targetDateInput = it }, label = { Text("Target Date") }, modifier = Modifier.weight(1f))
                                }
                                Button(
                                    onClick = {
                                        prefs.edit().apply {
                                            putString("googleSheetUrl", sheetUrlInput.trim())
                                            putString("geminiApiKey", geminiKeyInput.trim())
                                            putInt("age", ageInput.toIntOrNull() ?: 30)
                                            putFloat("weight", weightInput.toFloatOrNull() ?: 70f)
                                            putFloat("targetWeight", targetWeightInput.toFloatOrNull() ?: 65f)
                                            putString("targetDate", targetDateInput.trim())
                                            putString("debugServerIp", debugServerIpInput.trim())
                                            apply()
                                        }
                                        HeartRateState.debugServerIp = debugServerIpInput.trim()
                                        HeartRateState.geminiApiKey = geminiKeyInput.trim()
                                        HeartRateState.googleSheetUrl = sheetUrlInput.trim()
                                        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("💾 Save Configuration")
                                }
                            }
                        }
                    }

                    // 5. Diagnostics Logs
                    Text("Active System Diagnostics Log:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val logScrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .background(Color.Black, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(logScrollState), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HeartRateState.debugLogs.forEach { logLine ->
                                Text(
                                    text = logLine,
                                    color = when {
                                        logLine.contains("ERROR") -> Color(0xFFEF4444)
                                        logLine.contains("Warning") -> Color(0xFFF59E0B)
                                        logLine.contains("GSheets") -> Color(0xFF10B981)
                                        else -> Color.LightGray
                                    },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            "toolkit" -> {
                SkillToolkitScreen(
                    db = db,
                    innerPadding = innerPadding,
                    onOpenTrain = { HeartRateState.activeScreen = "practice" },
                    onSwitchSkill = { HeartRateState.activeScreen = "onboarding" },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding()
                )
            }

            "system", "assistant" -> {
                TheSystemScreen(
                    db = db,
                    context = context,
                    prefs = prefs,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(innerPadding)
                )
            }

            "mastery" -> {
                // Load session history and passive profile on screen open
                var recentSessions by remember { mutableStateOf<List<com.example.pulsebeatlogger.data.SkillSession>>(emptyList()) }
                var weeklyReviewState by remember { mutableStateOf(HeartRateState.weeklyReviewText) }
                LaunchedEffect(HeartRateState.activeScreen) {
                    if (HeartRateState.activeScreen == "mastery") {
                        recentSessions = db.skillSessionDao().getRecentSessions(30)
                        // Load weekly review if not cached
                        if (HeartRateState.weeklyReviewText.isEmpty() && HeartRateState.geminiApiKey.isNotEmpty()) {
                            val skill = HeartRateState.activeSkillName
                            val last7 = db.skillSessionDao().getRecentSessions(7)
                            if (last7.isNotEmpty()) {
                                val avg = last7.map { it.accuracyPct }.average().toFloat()
                                val allItems = db.learningItemDao().getAllForSkill(HeartRateState.trackingMode)
                                val weakItems = db.learningItemDao().getWeakItems(HeartRateState.trackingMode, 3)
                                val topWeak = weakItems.firstOrNull()?.category ?: "pronunciation"
                                val review = GeminiService.generateWeeklyReview(HeartRateState.geminiApiKey, skill, last7.size, avg, topWeak)
                                HeartRateState.weeklyReviewText = review
                                weeklyReviewState = review
                            }
                        }
                    }
                }

                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(innerPadding)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🏆 Skills Mastery & Progress",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Passive Voice Profile Card
                    val voiceProfile = HeartRateState.passiveVoiceProfile
                    if (voiceProfile != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("🎙 Passive Voice Profile", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.tertiary)
                                Text("Natural speaking F0: ${voiceProfile.medianF0.toInt()} Hz (${voiceProfile.register.replace("_", " / ").replaceFirstChar { it.uppercase() }})",
                                    fontSize = 13.sp)
                                Text("Speaking range: ${voiceProfile.rangeLabel}  •  Samples: ${voiceProfile.sampleCount}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (voiceProfile.register == "tenor_mezzo") {
                                    Text("💡 Insight: Your speaking range suggests strong potential for singing (Tenor/Mezzo range). Future singing sessions will use this as a baseline.",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            }
                        }
                    }

                    // Accuracy trend chart (last 7 sessions)
                    if (recentSessions.isNotEmpty()) {
                        val skillSessions = recentSessions.filter {
                            it.skillName == HeartRateState.trackingMode && it.accuracyPct > 0f
                        }.takeLast(7)
                        if (skillSessions.size >= 2) {
                            AccuracyTrendChart(sessions = skillSessions)
                        }
                    }

                    // Weekly review from Gemini
                    if (weeklyReviewState.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("📅 Weekly Review", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary)
                                Text(weeklyReviewState, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Progress bars for presets
                    val presets = listOf(
                        Pair("🎸 Ukulele Strum Posture", "ukulele"),
                        Pair("🗣️ Japanese Accent Coach", "japanese"),
                        Pair("🏋️ Push-Up Form Trainer", "pushup"),
                        Pair("🏃 Running Gait Sync", "running"),
                        Pair("🧘 Panic Stress Prevention", "stress")
                    )
                    
                    presets.forEach { (label, key) ->
                        val masteryValue = HeartRateState.skillsMastery[key] ?: 10
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("${masteryValue}% Mastered", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(
                                progress = { masteryValue / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).background(Color.LightGray, RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Weak Item Heatmap for current skill
                    val currentSkillItems = remember(HeartRateState.learningItems.toList()) { HeartRateState.learningItems.toList() }
                    if (currentSkillItems.isNotEmpty()) {
                        WeakItemHeatmap(items = currentSkillItems)
                    }
                    
                    // Smart Interleaving Coach Recommendation Card
                    val u = HeartRateState.skillsMastery["ukulele"] ?: 35
                    val j = HeartRateState.skillsMastery["japanese"] ?: 20
                    val p = HeartRateState.skillsMastery["pushup"] ?: 10
                    val r = HeartRateState.skillsMastery["running"] ?: 15
                    val s = HeartRateState.skillsMastery["stress"] ?: 5
                    
                    val lowest = listOf("ukulele" to u, "japanese" to j, "pushup" to p, "running" to r, "stress" to s)
                        .minByOrNull { it.second }
                    
                    val interleavingAdvice = when (lowest?.first) {
                        "pushup" -> "🏋️ Interleaving Recommendation: You've been focusing on cognitive skills. Try Push-ups to activate motor system mechanics!"
                        "japanese" -> "🗣️ Interleaving Recommendation: Japanese score is low (${j}%). Try a 5-minute accent drill to balance your training."
                        "ukulele" -> "🎸 Interleaving Recommendation: Switch to Ukulele. Alternating cognitive + motor skills increases retention by ~40%."
                        "running" -> "🏃 Interleaving Recommendation: Try Running Gait Sync to balance heart rate training zones."
                        else -> "🧘 Interleaving Recommendation: Nervous system recovery is low. Try Panic Stress Prevention breathing."
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🧠 Smart Interleaving Coach", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                            Text(interleavingAdvice, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Interesting Challenges card
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🎯 Active Practice Challenges", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                            ChallengeItem("🎸 Practice Ukulele with >80% scorecard score for 60s")
                            ChallengeItem("🗣️ Speak Japanese for 3 minutes continuously above 45dB")
                            ChallengeItem("🧘 Complete 5 minutes of Stress homeostatic breathing")
                        }
                    }
                }
            }

            "debug" -> {
                DebugScreen(
                    scope = coroutineScope,
                    db = db,
                    context = context,
                    prefs = prefs,
                    innerPadding = innerPadding
                )
            }
        }
    }
}

// ── Sensor Readiness System ───────────────────────────────────────────────────

enum class SensorImportance { REQUIRED, RECOMMENDED, NOT_NEEDED }

data class SensorStatus(
    val icon: String,
    val label: String,
    val importance: SensorImportance,
    val isActive: Boolean,
    val reason: String       // why this sensor matters for this skill
)

/** Derives sensor requirements for ANY skill, preset or custom. */
fun resolveSensors(mode: String, skillName: String, isMicActive: Boolean, isWatchActive: Boolean, isStrapActive: Boolean): List<SensorStatus> {
    val s = "$mode ${skillName.lowercase(java.util.Locale.US)}"

    val isVocalSkill = s.containsAny("japanese", "speak", "language", "sing", "vocal", "accent",
        "pronunciation", "guitar", "ukulele", "music", "song", "choir", "pitch", "french",
        "korean", "mandarin", "spanish", "italian", "german")
    val isPhysicalSkill = s.containsAny("workout", "run", "jog", "cardio", "gym", "exercise",
        "cycling", "swim", "sport", "heart", "pushup", "push up", "fitness", "hiit", "yoga",
        "pilates", "dance", "martial", "boxing", "training")
    val isMotorSkill = s.containsAny("ukulele", "guitar", "piano", "instrument", "posture",
        "wrist", "form", "technique", "draw", "write")
    val isMindSkill = s.containsAny("stress", "panic", "calm", "meditat", "breath", "sleep",
        "relax", "anxiety")

    val micImportance = when {
        isVocalSkill || isMindSkill -> SensorImportance.REQUIRED
        isMotorSkill -> SensorImportance.RECOMMENDED  // passive pitch profiling helps
        isPhysicalSkill -> SensorImportance.RECOMMENDED
        else -> SensorImportance.RECOMMENDED           // always useful for passive voice tagging
    }
    val micReason = when {
        isVocalSkill -> "Needed to analyse your pronunciation and pitch"
        isMindSkill -> "Needed to detect breathing rhythm and sound levels"
        else -> "Optional — captures passive voice profile for future skills"
    }

    val strapImportance = when {
        isPhysicalSkill -> SensorImportance.REQUIRED
        isMindSkill -> SensorImportance.RECOMMENDED   // HR confirms relaxation
        isVocalSkill -> SensorImportance.RECOMMENDED  // arousal/stress affects voice
        else -> SensorImportance.RECOMMENDED
    }
    val strapReason = when {
        isPhysicalSkill -> "Needed to track heart rate zones during exercise"
        isMindSkill -> "Confirms heart rate drop during relaxation"
        else -> "Optional — monitors physical arousal during practice"
    }

    val watchImportance = when {
        isMotorSkill -> SensorImportance.RECOMMENDED  // wrist angle tracking
        isMindSkill || s.contains("sleep") -> SensorImportance.RECOMMENDED
        else -> SensorImportance.NOT_NEEDED
    }
    val watchReason = when {
        isMotorSkill -> "Tracks wrist angle and motion for form correction"
        else -> "Tracks motion and HR for sleep / stress monitoring"
    }

    return listOf(
        SensorStatus("📱", "Phone Microphone", micImportance, isMicActive && micImportance != SensorImportance.NOT_NEEDED, micReason),
        SensorStatus("💓", "BLE Heart Rate Strap", strapImportance, isStrapActive, strapReason),
        SensorStatus("⌚", "Pixel Watch", watchImportance, isWatchActive, watchReason)
    ).filter { it.importance != SensorImportance.NOT_NEEDED }
}

private fun String.containsAny(vararg keywords: String) = keywords.any { this.contains(it) }

@Composable
fun SensorReadinessCard(
    mode: String,
    skillName: String,
    isMicActive: Boolean,
    isWatchActive: Boolean,
    isStrapActive: Boolean,
    warningDismissed: Boolean,
    onDismissWarning: () -> Unit
) {
    val sensors = resolveSensors(mode, skillName, isMicActive, isWatchActive, isStrapActive)
    val blockingMissing = sensors.filter { it.importance == SensorImportance.REQUIRED && !it.isActive }
    val allReady = blockingMissing.isEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Blocking warning banner — shown until dismissed or sensor connects
        if (blockingMissing.isNotEmpty() && !warningDismissed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚠️", fontSize = 18.sp)
                        Text(
                            text = "Missing required sensor${if (blockingMissing.size > 1) "s" else ""}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFFEF4444)
                        )
                    }
                    blockingMissing.forEach { sensor ->
                        Text("${sensor.icon} ${sensor.label}: ${sensor.reason}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismissWarning) {
                            Text("Continue anyway", fontSize = 12.sp, color = Color(0xFFEF4444))
                        }
                    }
                }
            }
        }

        // Sensor checklist card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🔌 Sensors", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    if (allReady) {
                        Text("Ready to go", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    } else if (warningDismissed) {
                        Text("Limited tracking", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                    } else {
                        Text("Action needed", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                    }
                }
                sensors.forEach { sensor ->
                    SensorRow(sensor)
                }
            }
        }
    }
}

@Composable
fun SensorRow(sensor: SensorStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sensor.icon, fontSize = 14.sp)
                Text(sensor.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (sensor.importance == SensorImportance.REQUIRED) {
                    Text("REQUIRED", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                } else {
                    Text("optional", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(sensor.reason, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp))
        }
        val (statusText, statusColor) = when {
            sensor.isActive -> Pair("Connected", Color(0xFF10B981))
            sensor.importance == SensorImportance.REQUIRED -> Pair("Not connected", Color(0xFFEF4444))
            else -> Pair("Not connected", Color(0xFFF59E0B))
        }
        Text(statusText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
    }
}

@Composable
fun ChallengeItem(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("•", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Debug / Sandbox Screen ────────────────────────────────────────────────────

@Composable
fun DebugScreen(
    scope: CoroutineScope,
    db: com.example.pulsebeatlogger.data.AppDatabase,   // reserved for future DB introspection
    context: android.content.Context,                   // reserved for toast/clipboard actions
    prefs: android.content.SharedPreferences,
    innerPadding: PaddingValues = PaddingValues()
) {
    var hrRunning by remember { mutableStateOf(false) }
    var gpsRunning by remember { mutableStateOf(false) }
    var baseHr by remember { mutableStateOf(72f) }
    val logScrollState = rememberScrollState()
    val passiveTaggerRef = remember { PassiveTagger(sampleRate = 8000) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(innerPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("🔬 Debug Lab", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED))
                Text("Sandbox mode — no real hardware needed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = HeartRateState.debugMode,
                onCheckedChange = { on ->
                    HeartRateState.debugMode = on
                    prefs.edit().putBoolean("debugMode", on).apply()
                    if (on) {
                        HeartRateState.log("🔬 Debug mode ENABLED — hardware bypassed")
                    } else {
                        DebugSimulator.resetAll()
                        hrRunning = false
                        gpsRunning = false
                        HeartRateState.log("🔬 Debug mode DISABLED — real hardware active")
                    }
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF7C3AED),
                    checkedTrackColor = Color(0xFF7C3AED).copy(alpha = 0.3f)
                )
            )
        }

        if (!HeartRateState.debugMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7C3AED).copy(alpha = 0.08f))
            ) {
                Text(
                    "Enable the toggle above to activate sandbox mode.\nAll sensors become simulated — safe to run on any device or emulator.",
                    modifier = Modifier.padding(14.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── App updates (no USB / adb) ─────────────────────────────────────────
        var manifestUrlInput by remember {
            mutableStateOf(prefs.getString(AppUpdateChecker.PREF_MANIFEST_URL, "") ?: "")
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📲 App updates (optional fallback)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Normally updates arrive automatically from the release channel after you connect Google. " +
                        "Only use this if you host a custom update.json elsewhere.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Installed: v${AppUpdateChecker.currentVersionCode(context)}", fontSize = 11.sp)
                OutlinedTextField(
                    value = manifestUrlInput,
                    onValueChange = { manifestUrlInput = it },
                    label = { Text("Update manifest URL") },
                    placeholder = { Text("https://…/update.json") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            prefs.edit().putString(AppUpdateChecker.PREF_MANIFEST_URL, manifestUrlInput.trim()).apply()
                            Toast.makeText(context, "Saved — checks on next app open", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val info = AppUpdateChecker.checkForUpdate(context, manifestUrlInput.trim())
                                if (info != null) {
                                    Toast.makeText(context, "Update v${info.versionName} available — reopen app", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Up to date or invalid manifest URL", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Check now") }
                }
            }
        }

        // ── Sensor Simulators (only when debug mode is on) ────────────────────
        if (HeartRateState.debugMode) {

            // Heart Rate Simulator
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("💓 Heart Rate", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                if (hrRunning) "Running — ${HeartRateState.debugSimBpm} bpm" else "Stopped",
                                fontSize = 11.sp,
                                color = if (hrRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = hrRunning,
                            onCheckedChange = { on ->
                                hrRunning = on
                                if (on) DebugSimulator.startFakeHr(scope, baseHr.toInt()) else DebugSimulator.stopFakeHr()
                            }
                        )
                    }
                    Text("Base HR: ${baseHr.toInt()} bpm", fontSize = 12.sp)
                    Slider(
                        value = baseHr,
                        onValueChange = { baseHr = it },
                        valueRange = 50f..160f,
                        steps = 22,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { DebugSimulator.injectHrSpike(scope, 155) }, modifier = Modifier.weight(1f)) {
                            Text("⚡ Spike 155", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { DebugSimulator.injectHrSpike(scope, 45) }, modifier = Modifier.weight(1f)) {
                            Text("❄️ Drop 45", fontSize = 11.sp)
                        }
                    }
                }
            }

            // GPS Simulator
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("📍 GPS / Running", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                if (gpsRunning) "Running — ${HeartRateState.gpsSpeed} km/h · ${HeartRateState.gpsDistance} m" else "Stopped",
                                fontSize = 11.sp,
                                color = if (gpsRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = gpsRunning,
                            onCheckedChange = { on ->
                                gpsRunning = on
                                if (on) DebugSimulator.startFakeGps(scope) else DebugSimulator.stopFakeGps()
                            }
                        )
                    }
                }
            }

            // Watch Simulator
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("⌚ Pixel Watch", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                if (HeartRateState.isWatchConnected) "Connected — ${HeartRateState.sleepSoundDb} dB / ${HeartRateState.sleepMotionMag} motion" else "Disconnected",
                                fontSize = 11.sp,
                                color = if (HeartRateState.isWatchConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = HeartRateState.isWatchConnected,
                            onCheckedChange = { on ->
                                if (on) DebugSimulator.fakeWatchConnect() else DebugSimulator.fakeWatchDisconnect()
                            }
                        )
                    }
                    Button(onClick = { DebugSimulator.fakeWatchSleepTick() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Send Watch Sleep Tick", fontSize = 12.sp)
                    }
                }
            }

            // Quick Actions
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚡ Quick Actions", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { DebugSimulator.simulateFullSession(scope) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                        ) {
                            Text("▶ 30s Session", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                HeartRateState.debugEndSessionTick++
                                HeartRateState.log("🔬 Manual end-session triggered.")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text("⏹ End Session", fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { DebugSimulator.injectFakeVoiceProfile() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🎙 Fake Voice", fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { DebugSimulator.injectPitchSamples(passiveTaggerRef, 150f) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🎵 150 Hz Pitch", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { DebugSimulator.resetAll(); hrRunning = false; gpsRunning = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔄 Reset All", fontSize = 11.sp)
                        }
                    }
                    // Gemini mock note
                    val geminiKey = HeartRateState.geminiApiKey.trim().lowercase()
                    if (geminiKey == "debug") {
                        Text("✅ Gemini key = \"debug\" → offline mock responses active", fontSize = 11.sp, color = Color(0xFF10B981))
                    } else {
                        OutlinedButton(
                            onClick = {
                                HeartRateState.geminiApiKey = "debug"
                                prefs.edit().putString("geminiApiKey", "debug").apply()
                                HeartRateState.log("🔬 Gemini key set to 'debug' — offline mock mode enabled")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🤖 Set Gemini → Offline Mock", fontSize = 12.sp)
                        }
                    }
                    Text("🤖 Assistant Integrations", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                GoogleAuthHelper.enableSandbox()
                                prefs.edit().putBoolean("calendarSandboxMode", true).apply()
                                HeartRateState.log("🔬 Calendar sandbox enabled")
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Calendar sandbox", fontSize = 10.sp) }
                    }
                    OutlinedButton(
                        onClick = { HeartRateState.activeScreen = "system" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("💬 Open Assistant Chat", fontSize = 12.sp) }
                }
            }
        }

        // ── Gemini Model Pool Status ──────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🤖 Gemini Model Pool", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val poolStatus = remember(HeartRateState.debugLogs.size) { GeminiService.poolStatus() }
                poolStatus.lines().forEach { line ->
                    val color = when {
                        line.startsWith("✅") -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        line.startsWith("⏳") -> androidx.compose.ui.graphics.Color(0xFFE65100)
                        line.startsWith("❌") -> androidx.compose.ui.graphics.Color(0xFFC62828)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = color)
                }
            }
        }

        // ── Current State Dump ────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("📊 Live State", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val stateLines = listOf(
                    "screen        = ${HeartRateState.activeScreen}",
                    "skill         = ${HeartRateState.activeSkillName}",
                    "mode          = ${HeartRateState.trackingMode}",
                    "debugMode     = ${HeartRateState.debugMode}",
                    "service       = ${HeartRateState.isServiceRunning}",
                    "ble           = ${HeartRateState.connectionState}",
                    "currentBpm    = ${HeartRateState.currentBpm}",
                    "watch         = ${HeartRateState.isWatchConnected}",
                    "gpsSpeed      = ${HeartRateState.gpsSpeed} km/h",
                    "gpsDist       = ${HeartRateState.gpsDistance} m",
                    "sleepDb       = ${HeartRateState.sleepSoundDb}",
                    "sleepMotion   = ${HeartRateState.sleepMotionMag}",
                    "learningItems = ${HeartRateState.learningItems.size}",
                    "sessionId     = ${HeartRateState.currentSessionId}",
                    "geminiLoading = ${HeartRateState.isGeminiLoading}",
                    "voiceProfile  = ${HeartRateState.passiveVoiceProfile?.register ?: "none"}"
                )
                stateLines.forEach { line ->
                    Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // ── Sensor Event Log summary ──────────────────────────────────────
                var sensorCounts by remember { mutableStateOf<List<com.example.pulsebeatlogger.data.SensorTypeCount>>(emptyList()) }
                var sensorTotal by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(500)
                    sensorTotal = db.sensorEventDao().totalCount()
                    sensorCounts = db.sensorEventDao().countBySensorType()
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("📡 Sensor Events stored: $sensorTotal", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF60A5FA))
                sensorCounts.forEach { tc ->
                    Text("  ${tc.sensorType.padEnd(20)} ${tc.cnt}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            sensorTotal = db.sensorEventDao().totalCount()
                            sensorCounts = db.sensorEventDao().countBySensorType()
                        }
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("↻ Refresh counts", fontSize = 10.sp)
                }
            }
        }

        // ── Log Viewer ────────────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("📋 Log (${HeartRateState.debugLogs.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            TextButton(onClick = { HeartRateState.debugLogs.clear() }) {
                Text("Clear", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .background(Color(0xFF0F0F0F), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(logScrollState),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                HeartRateState.debugLogs.forEach { line ->
                    Text(
                        text = line,
                        color = when {
                            line.contains("🔬") -> Color(0xFFB57BFF)
                            line.contains("ERROR") -> Color(0xFFEF4444)
                            line.contains("Warning") -> Color(0xFFF59E0B)
                            line.contains("✅") || line.contains("GSheets") -> Color(0xFF10B981)
                            line.contains("GATT") || line.contains("BLE") -> Color(0xFF60A5FA)
                            line.contains("Gemini") -> Color(0xFFFF6B9D)
                            else -> Color(0xFFCCCCCC)
                        },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

fun loadPresetByName(context: Context, prefs: SharedPreferences, key: String) {
    val mode = when (key) {
        "pushup" -> "pushup"
        "japanese" -> "japanese"
        "ukulele" -> "ukulele"
        "running" -> "running"
        "stress" -> "stress"
        else -> "workout"
    }
    
    val skillLabel = when (key) {
        "pushup" -> "Push-Up Form Trainer"
        "japanese" -> "Japanese Accent Coach"
        "ukulele" -> "Ukulele Strum Posture"
        "running" -> "Running Gait Sync"
        "stress" -> "Panic Stress Prevention"
        else -> "Custom Skill"
    }
    
    HeartRateState.activeSkillName = skillLabel
    HeartRateState.trackingMode = mode
    prefs.edit().putString("trackingMode", mode).putString("activeSkillName", skillLabel).apply()
    
    // Broadcast service update
    if (HeartRateState.isServiceRunning) {
        val intent = Intent(context, HeartRateService::class.java).apply {
            action = "UPDATE_MODE"
            putExtra("mode", mode)
        }
        context.startService(intent)
    }
    
    // Reset Chat Log with welcome message
    HeartRateState.chatLog.clear()
    val intro = when (key) {
        "pushup" -> "🏋️ **Push-Up Form Preset Loaded!** Focus: Vertical motion count & arm cadence. Watch IMU detects form repetitions. Tell me if it's 'too easy' or you are 'tired'!"
        "japanese" -> "🗣️ **Japanese Accent Preset Loaded!** Focus: Vocal pitch intonation matching (Mic > 45dB). FFT analyzing speech frequency. Let me know how it feels!"
        "ukulele" -> "🎸 **Ukulele Strum Preset Loaded!** Focus: Fretboard wrist roll posture. Alert triggers if wrist tilt > 45°. Let me know if your wrist hurts!"
        "running" -> "🏃 **Running Gait Preset Loaded!** Focus: Cadence vs respiration sync. Target aerobic zone active."
        "stress" -> "🧘 **Panic Stress Preset Loaded!** Focus: Sympathetic nervous system relaxation. Box breathing active."
        else -> "Hi! Ready to practice your custom skill."
    }
    HeartRateState.chatLog.add(Pair("AI Coach", intro))
}

fun matchAndLoadPreset(context: Context, prefs: SharedPreferences, query: String): Boolean {
    val q = query.lowercase(java.util.Locale.US)
    val key = when {
        q.contains("pushup") || q.contains("push up") || q.contains("gym") || q.contains("lift") -> "pushup"
        q.contains("japanese") || q.contains("speak") || q.contains("talk") || q.contains("language") -> "japanese"
        q.contains("ukulele") || q.contains("strum") || q.contains("guitar") || q.contains("music") -> "ukulele"
        q.contains("run") || q.contains("jog") || q.contains("cardio") || q.contains("walk") -> "running"
        q.contains("stress") || q.contains("panic") || q.contains("calm") || q.contains("relax") || q.contains("breathe") -> "stress"
        else -> null
    }
    if (key != null) {
        loadPresetByName(context, prefs, key)
        return true
    }
    return false
}

fun loadSkillsMastery(prefs: SharedPreferences) {
    val skills = listOf("ukulele", "japanese", "pushup", "running", "stress")
    val defaultMasteries = mapOf(
        "ukulele" to 35,
        "japanese" to 20,
        "pushup" to 10,
        "running" to 15,
        "stress" to 5
    )
    skills.forEach { skill ->
        val saved = prefs.getInt("mastery_$skill", -1)
        if (saved == -1) {
            prefs.edit().putInt("mastery_$skill", defaultMasteries[skill] ?: 10).apply()
            HeartRateState.skillsMastery[skill] = defaultMasteries[skill] ?: 10
        } else {
            HeartRateState.skillsMastery[skill] = saved
        }
    }
}

fun saveSlot(context: Context, prefs: SharedPreferences, slotName: String) {
    if (slotName.trim().isEmpty()) return
    try {
        val slotsStr = prefs.getString("pulsebeat_custom_slots", "[]") ?: "[]"
        val slots = JSONArray(slotsStr)
        if (slots.length() >= 10) {
            Toast.makeText(context, "Max 10 slots reached! Delete one first.", Toast.LENGTH_SHORT).show()
            return
        }
        val newSlot = JSONObject().apply {
            put("name", slotName.trim())
            put("mode", HeartRateState.trackingMode)
            put("hr", HeartRateState.currentBpm)
            put("motion", HeartRateState.sleepMotionMag)
            put("db", HeartRateState.sleepSoundDb)
            put("speed", HeartRateState.gpsSpeed)
        }
        slots.put(newSlot)
        prefs.edit().putString("pulsebeat_custom_slots", slots.toString()).apply()
        Toast.makeText(context, "Saved slot '$slotName'", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        HeartRateState.logError("Failed to save custom slot", e)
    }
}

fun loadSlot(context: Context, prefs: SharedPreferences, index: Int) {
    try {
        val slotsStr = prefs.getString("pulsebeat_custom_slots", "[]") ?: "[]"
        val slots = JSONArray(slotsStr)
        if (index < 0 || index >= slots.length()) return
        val slot = slots.getJSONObject(index)
        val mode = slot.getString("mode")
        val skillName = slot.getString("name")
        
        HeartRateState.activeSkillName = skillName
        HeartRateState.trackingMode = mode
        HeartRateState.currentBpm = slot.optInt("hr", 0)
        HeartRateState.sleepMotionMag = slot.optDouble("motion", 0.0)
        HeartRateState.sleepSoundDb = slot.optDouble("db", 0.0)
        HeartRateState.gpsSpeed = slot.optDouble("speed", 0.0)
        
        prefs.edit().putString("trackingMode", mode).putString("activeSkillName", skillName).apply()
        
        if (HeartRateState.isServiceRunning) {
            val intent = Intent(context, HeartRateService::class.java).apply {
                action = "UPDATE_MODE"
                putExtra("mode", mode)
            }
            context.startService(intent)
        }
        Toast.makeText(context, "Loaded slot '$skillName'", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        HeartRateState.logError("Failed to load custom slot", e)
    }
}

fun deleteSlot(context: Context, prefs: SharedPreferences, index: Int) {
    try {
        val slotsStr = prefs.getString("pulsebeat_custom_slots", "[]") ?: "[]"
        val slots = JSONArray(slotsStr)
        val newSlots = JSONArray()
        for (i in 0 until slots.length()) {
            if (i != index) {
                newSlots.put(slots.getJSONObject(i))
            }
        }
        prefs.edit().putString("pulsebeat_custom_slots", newSlots.toString()).apply()
        Toast.makeText(context, "Deleted custom slot", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        HeartRateState.logError("Failed to delete custom slot", e)
    }
}

// ── SRS Task List Composable ──────────────────────────────────────────────────

@Composable
fun SrsTaskList(
    items: List<LearningItem>,
    coroutineScope: CoroutineScope,
    context: android.content.Context,
    onReview: (LearningItem, Int) -> Unit
) {
    val skillType = getSkillType(HeartRateState.activeSkillName)
    var expandedId by remember { mutableStateOf<String?>(null) }
    val userInputs = remember { mutableStateMapOf<String, String>() }
    val aiFeedback = remember { mutableStateMapOf<String, String>() }
    val checkingItem = remember { mutableStateOf<String?>(null) }
    val repCounts = remember { mutableStateMapOf<String, Int>() }

    val headerLabel = when (skillType) {
        SkillType.CODE     -> "💻 Today's Coding Challenges"
        SkillType.LANGUAGE -> "🗣️ Today's Language Practice"
        SkillType.MATH     -> "🔢 Today's Problems"
        SkillType.PHYSICAL -> "🏋️ Today's Exercises"
        SkillType.MUSIC    -> "🎵 Today's Music Practice"
        SkillType.ART      -> "🎨 Today's Drawing Practice"
        SkillType.DEFAULT  -> "📚 Today's Review Items"
    }

    val (typeBadgeLabel, typeBadgeColor) = when (skillType) {
        SkillType.CODE     -> "💻 CODE"     to Color(0xFF1D4ED8)
        SkillType.LANGUAGE -> "🗣️ LANGUAGE" to Color(0xFF059669)
        SkillType.MATH     -> "🔢 MATH"     to Color(0xFF7C3AED)
        SkillType.PHYSICAL -> "🏋️ PHYSICAL" to Color(0xFFDC2626)
        SkillType.MUSIC    -> "🎵 MUSIC"    to Color(0xFFD97706)
        SkillType.ART      -> "🎨 ART"      to Color(0xFFDB2777)
        SkillType.DEFAULT  -> "📚 GENERAL"  to Color(0xFF6B7280)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(headerLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    Text(HeartRateState.activeSkillName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .background(typeBadgeColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(typeBadgeLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = typeBadgeColor)
                    }
                    Text("${items.size} due", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            items.take(5).forEach { item ->
                val isExpanded = expandedId == item.id
                val feedback = aiFeedback[item.id]
                val isChecking = checkingItem.value == item.id
                val hint    = remember(item.contentJson) { runCatching { org.json.JSONObject(item.contentJson).optString("hint","")    }.getOrDefault("") }
                val example = remember(item.contentJson) { runCatching { org.json.JSONObject(item.contentJson).optString("example","") }.getOrDefault("") }
                val tip     = remember(item.contentJson) { runCatching { org.json.JSONObject(item.contentJson).optString("tip","")     }.getOrDefault("") }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { expandedId = if (isExpanded) null else item.id }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Item header row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.content, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(item.category.replace("_"," "), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                    }

                    if (isExpanded) {
                        when (skillType) {

                            // ── CODE: dark editor + Gemini check ─────────────────────────────
                            SkillType.CODE -> {
                                if (hint.isNotEmpty()) Text("Hint: $hint", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (example.isNotEmpty()) {
                                    Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E2E), RoundedCornerShape(6.dp)).padding(10.dp)) {
                                        Text("Example / Reference:", fontSize = 10.sp, color = Color(0xFF888888))
                                        Text(example, fontSize = 12.sp, color = Color(0xFFCDD6F4),
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 18.sp)
                                    }
                                }
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.height(4.dp))
                                Text("Your solution:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                androidx.compose.material3.OutlinedTextField(
                                    value = userInputs[item.id] ?: "", onValueChange = { userInputs[item.id] = it },
                                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFCDD6F4)),
                                    placeholder = { Text("# Write your code here…", fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color(0xFF555577)) },
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color(0xFF1E1E2E), focusedContainerColor = Color(0xFF1E1E2E), focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF444466)),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                AiFeedbackBlock(feedback)
                                WriteCheckRatingRow(
                                    userInput = userInputs[item.id].orEmpty(), isChecking = isChecking,
                                    checkLabel = "Check ✓",
                                    aiPrompt = { code -> "You are a coding tutor. Exercise: \"${item.content}\"\nHint: $hint\nStudent code:\n```\n$code\n```\nIn 2-3 sentences: correct? Start with ✅ or ❌." },
                                    item = item, checkingItem = checkingItem, aiFeedback = aiFeedback,
                                    coroutineScope = coroutineScope, onReview = onReview, expandedId = { expandedId = null }
                                )
                            }

                            // ── LANGUAGE: plain text answer + Gemini grammar/accuracy check ──
                            SkillType.LANGUAGE -> {
                                if (hint.isNotEmpty()) Text("Hint: $hint", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (example.isNotEmpty()) {
                                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(10.dp)) {
                                        Text("Reference:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(example, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.height(4.dp))
                                Text("Your answer:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                androidx.compose.material3.OutlinedTextField(
                                    value = userInputs[item.id] ?: "", onValueChange = { userInputs[item.id] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Type your translation / answer…", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(6.dp), singleLine = false, maxLines = 4
                                )
                                AiFeedbackBlock(feedback)
                                WriteCheckRatingRow(
                                    userInput = userInputs[item.id].orEmpty(), isChecking = isChecking,
                                    checkLabel = "Check ✓",
                                    aiPrompt = { ans -> "You are a language tutor for ${HeartRateState.activeSkillName}. Task: \"${item.content}\"\nHint: $hint\nStudent answer: \"$ans\"\nIn 2-3 sentences: is this correct? Note any errors. Start with ✅ or ❌." },
                                    item = item, checkingItem = checkingItem, aiFeedback = aiFeedback,
                                    coroutineScope = coroutineScope, onReview = onReview, expandedId = { expandedId = null }
                                )
                            }

                            // ── MATH: answer field + Gemini solution check ────────────────────
                            SkillType.MATH -> {
                                if (hint.isNotEmpty()) Text("Hint: $hint", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (example.isNotEmpty()) {
                                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(10.dp)) {
                                        Text("Reference:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(example, fontSize = 13.sp)
                                    }
                                }
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.height(4.dp))
                                Text("Your solution:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                androidx.compose.material3.OutlinedTextField(
                                    value = userInputs[item.id] ?: "", onValueChange = { userInputs[item.id] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Write your working and answer…", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(6.dp), singleLine = false, maxLines = 5
                                )
                                AiFeedbackBlock(feedback)
                                WriteCheckRatingRow(
                                    userInput = userInputs[item.id].orEmpty(), isChecking = isChecking,
                                    checkLabel = "Check ✓",
                                    aiPrompt = { ans -> "You are a math tutor. Problem: \"${item.content}\"\nHint: $hint\nStudent answer: \"$ans\"\nIn 2-3 sentences: is this correct? Show the right approach if wrong. Start with ✅ or ❌." },
                                    item = item, checkingItem = checkingItem, aiFeedback = aiFeedback,
                                    coroutineScope = coroutineScope, onReview = onReview, expandedId = { expandedId = null }
                                )
                            }

                            // ── PHYSICAL: live HR zone + rep counter ──────────────────────────
                            SkillType.PHYSICAL -> {
                                // Live sensor strip
                                val bpm = HeartRateState.currentBpm
                                val hrZone = when {
                                    bpm <= 0  -> "No HR sensor"
                                    bpm < 100 -> "Zone 1 — Warm-up ($bpm bpm)"
                                    bpm < 130 -> "Zone 2 — Fat burn ($bpm bpm)"
                                    bpm < 155 -> "Zone 3 — Aerobic ($bpm bpm)"
                                    bpm < 175 -> "Zone 4 — Threshold ($bpm bpm)"
                                    else      -> "Zone 5 — Max effort ($bpm bpm)"
                                }
                                val zoneColor = when {
                                    bpm <= 0  -> MaterialTheme.colorScheme.surfaceVariant
                                    bpm < 100 -> Color(0xFF3B82F6).copy(alpha = 0.15f)
                                    bpm < 130 -> Color(0xFF10B981).copy(alpha = 0.15f)
                                    bpm < 155 -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                    bpm < 175 -> Color(0xFFEF4444).copy(alpha = 0.15f)
                                    else      -> Color(0xFF7C3AED).copy(alpha = 0.15f)
                                }
                                Row(Modifier.fillMaxWidth().background(zoneColor, RoundedCornerShape(6.dp)).padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("❤️ $hrZone", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    if (bpm <= 0) Text("Connect HR strap", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                }
                                if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (example.isNotEmpty()) Text("How to: $example", fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                // Rep counter + auto-advance timer
                                val reps = repCounts[item.id] ?: 0
                                var restCountdown by remember(item.id) { mutableIntStateOf(-1) }

                                // Auto-advance after Done — countdown 5s hands-free rest timer
                                LaunchedEffect(restCountdown) {
                                    if (restCountdown > 0) {
                                        delay(1000)
                                        restCountdown--
                                    } else if (restCountdown == 0) {
                                        onReview(item, 5)
                                        expandedId = null
                                        repCounts.remove(item.id)
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Reps done:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("$reps", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.weight(1f))
                                    Button(onClick = { repCounts[item.id] = reps + 1 }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("+1 Rep") }
                                    if (reps > 0) {
                                        OutlinedButton(onClick = { repCounts[item.id] = (reps - 1).coerceAtLeast(0) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Undo") }
                                    }
                                }

                                // Rest countdown display
                                if (restCountdown > 0) {
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .background(Color(0xFF10B981).copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("⏱", fontSize = 20.sp)
                                            Text("Next exercise in $restCountdown s…", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF059669))
                                            TextButton(onClick = { restCountdown = -1 }, contentPadding = PaddingValues(4.dp)) { Text("Cancel", fontSize = 10.sp) }
                                        }
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onReview(item, 1); expandedId = null; repCounts.remove(item.id); restCountdown = -1 }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), contentPadding = PaddingValues(4.dp)) { Text("Skip", fontSize = 11.sp) }
                                    Button(onClick = { onReview(item, 3); expandedId = null; repCounts.remove(item.id); restCountdown = -1 }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)), contentPadding = PaddingValues(4.dp)) { Text("Partial", fontSize = 11.sp) }
                                    Button(
                                        onClick = { restCountdown = 5 },
                                        modifier = Modifier.weight(1.2f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                        contentPadding = PaddingValues(4.dp),
                                        enabled = restCountdown < 0
                                    ) { Text(if (restCountdown < 0) "✓ Done ($reps)" else "⏱ $restCountdown s", fontSize = 11.sp) }
                                }
                            }

                            // ── MUSIC: mic hint + practice prompt + AI feedback ───────────────
                            // ── MUSIC: live tuner + feedback ─────────────────────────────────
                            SkillType.MUSIC -> {
                                // What to practice (from Gemini item)
                                if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (example.isNotEmpty()) {
                                    Column(Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .padding(10.dp)) {
                                        Text("Target / Reference:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(example, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)

                                Spacer(Modifier.height(6.dp))

                                // ── Live Tuner ─────────────────────────────────────────────────
                                val tuner = remember(item.id) { com.example.pulsebeatlogger.TunerEngine(context) }
                                DisposableEffect(item.id) {
                                    tuner.start()
                                    onDispose { tuner.stop() }
                                }

                                val tunerBg = when (tuner.tuneState) {
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.IN_TUNE -> Color(0xFF10B981)
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.CLOSE   -> Color(0xFFF59E0B)
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.OFF     -> Color(0xFFEF4444)
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.SILENT  -> Color(0xFF6B7280)
                                }
                                val tunerLabel = when (tuner.tuneState) {
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.IN_TUNE -> "✓ In Tune"
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.CLOSE   -> "~ Almost"
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.OFF     -> "✗ Off"
                                    com.example.pulsebeatlogger.TunerEngine.TuneState.SILENT  ->
                                        if (tuner.statusMessage.isNotBlank()) tuner.statusMessage
                                        else "🎙️ Listening…"
                                }

                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(tunerBg.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                        .border(1.dp, tunerBg.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Big note name
                                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                                        Text(
                                            text = if (tuner.pitchHz > 0f) tuner.noteName else "--",
                                            fontSize = 52.sp, fontWeight = FontWeight.ExtraBold,
                                            color = tunerBg, lineHeight = 52.sp
                                        )
                                        if (tuner.pitchHz > 0f) {
                                            Text(
                                                text = "${tuner.octave}",
                                                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                                color = tunerBg.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                                            )
                                        }
                                    }

                                    // Hz readout
                                    if (tuner.pitchHz > 0f) {
                                        Text("${"%.1f".format(tuner.pitchHz)} Hz", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    // Cents needle bar
                                    val cents = tuner.centOffset
                                    Box(
                                        Modifier.fillMaxWidth().height(20.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                    ) {
                                        // Center marker
                                        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.outline))
                                        // Needle position: cents -50..+50 mapped to 0..width
                                        val fraction = ((cents + 50f) / 100f).coerceIn(0f, 1f)
                                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                                            val needleX = (maxWidth.value * fraction - 8).dp
                                            Box(Modifier.offset(x = needleX.coerceAtLeast(0.dp))
                                                .width(16.dp).fillMaxHeight()
                                                .background(tunerBg, RoundedCornerShape(8.dp)))
                                        }
                                    }

                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("♭ Flat", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = if (cents == 0) tunerLabel else "$tunerLabel (${if (cents > 0) "+$cents" else "$cents"}¢)",
                                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tunerBg
                                        )
                                        Text("Sharp ♯", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                // Self-reflection + AI feedback
                                Spacer(Modifier.height(4.dp))
                                Text("Practice notes (optional):", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                androidx.compose.material3.OutlinedTextField(
                                    value = userInputs[item.id] ?: "", onValueChange = { userInputs[item.id] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("e.g. 'intonation was off on the C#…'", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(6.dp), singleLine = false, maxLines = 3
                                )
                                AiFeedbackBlock(feedback)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val userNote = userInputs[item.id].orEmpty()
                                    if (userNote.isNotBlank()) {
                                        Button(
                                            onClick = {
                                                checkingItem.value = item.id
                                                coroutineScope.launch {
                                                    val prompt = "You are a music coach for ${HeartRateState.activeSkillName}. The student practiced: \"${item.content}\". Their note: \"$userNote\". In 2-3 sentences give specific, encouraging feedback."
                                                    aiFeedback[item.id] = GeminiService.chat(HeartRateState.geminiApiKey, prompt)
                                                    checkingItem.value = null
                                                }
                                            },
                                            modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(4.dp),
                                            enabled = !isChecking
                                        ) {
                                            if (isChecking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                            else Text("Get Feedback", fontSize = 11.sp)
                                        }
                                    }
                                    Button(onClick = { onReview(item, 1); expandedId = null; aiFeedback.remove(item.id) }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), contentPadding = PaddingValues(4.dp)) { Text("Again", fontSize = 11.sp) }
                                    Button(onClick = { onReview(item, 4); expandedId = null; aiFeedback.remove(item.id) }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), contentPadding = PaddingValues(4.dp)) { Text("Done ✓", fontSize = 11.sp) }
                                }
                            }

                            // ── ART: drawing canvas ───────────────────────────────────────────
                            SkillType.ART -> {
                                if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // Reference / subject prompt
                                if (example.isNotEmpty()) {
                                    Column(Modifier.fillMaxWidth()
                                        .background(Color(0xFFDB2777).copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                        .border(1.dp, Color(0xFFDB2777).copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .padding(10.dp)) {
                                        Text("Draw this:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(example, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)

                                Spacer(Modifier.height(6.dp))

                                // ── Touch Drawing Canvas ───────────────────────────────────────
                                val paths = remember(item.id) { mutableStateListOf<androidx.compose.ui.graphics.Path>() }
                                val currentPath = remember(item.id) { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
                                val strokeColor = MaterialTheme.colorScheme.onSurface
                                val canvasBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

                                Text("Canvas — draw with your finger:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .background(canvasBg, RoundedCornerShape(10.dp))
                                        .border(1.dp, Color(0xFFDB2777).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                        .pointerInput(item.id) {
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    val p = androidx.compose.ui.graphics.Path()
                                                    p.moveTo(offset.x, offset.y)
                                                    currentPath.value = p
                                                },
                                                onDrag = { change, _ ->
                                                    currentPath.value?.lineTo(change.position.x, change.position.y)
                                                    // Force recompose by toggling a dummy state
                                                    paths.add(androidx.compose.ui.graphics.Path())
                                                    paths.removeLast()
                                                },
                                                onDragEnd = {
                                                    currentPath.value?.let { paths.add(it) }
                                                    currentPath.value = null
                                                }
                                            )
                                        }
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val brushStroke = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        paths.forEach { path ->
                                            drawPath(path, color = strokeColor, style = brushStroke)
                                        }
                                        currentPath.value?.let { path ->
                                            drawPath(path, color = strokeColor, style = brushStroke)
                                        }
                                    }
                                    if (paths.isEmpty() && currentPath.value == null) {
                                        Text("✏️  Start drawing here", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.align(Alignment.Center))
                                    }
                                }

                                // Canvas controls
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            if (paths.isNotEmpty()) { paths.removeLast() }
                                        },
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)
                                    ) { Text("↩ Undo", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { paths.clear(); currentPath.value = null },
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp),
                                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text("🗑 Clear", fontSize = 11.sp) }
                                    val strokeCount = paths.size
                                    Button(
                                        onClick = {
                                            checkingItem.value = item.id
                                            coroutineScope.launch {
                                                val prompt = "You are a drawing coach for ${HeartRateState.activeSkillName}. The student practiced: \"${item.content}\". They made $strokeCount strokes. Give them 2-3 specific, encouraging technique tips for this type of drawing exercise."
                                                aiFeedback[item.id] = GeminiService.chat(HeartRateState.geminiApiKey, prompt)
                                                checkingItem.value = null
                                            }
                                        },
                                        modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(4.dp),
                                        enabled = !isChecking && paths.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDB2777))
                                    ) {
                                        if (isChecking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                        else Text("AI Tips", fontSize = 11.sp)
                                    }
                                }
                                AiFeedbackBlock(feedback)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onReview(item, 1); expandedId = null; aiFeedback.remove(item.id); paths.clear() }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), contentPadding = PaddingValues(4.dp)) { Text("Again", fontSize = 11.sp) }
                                    Button(onClick = { onReview(item, 4); expandedId = null; aiFeedback.remove(item.id); paths.clear() }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), contentPadding = PaddingValues(4.dp)) { Text("Done ✓", fontSize = 11.sp) }
                                }
                            }

                            // ── DEFAULT: standard flashcard ───────────────────────────────────
                            SkillType.DEFAULT -> {
                                if (hint.isNotEmpty()) Text("Hint: $hint", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (example.isNotEmpty()) Text("Example: $example", fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { onReview(item, 1); expandedId = null }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), contentPadding = PaddingValues(4.dp)) { Text("Again", fontSize = 11.sp) }
                                    Button(onClick = { onReview(item, 3); expandedId = null }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)), contentPadding = PaddingValues(4.dp)) { Text("Hard", fontSize = 11.sp) }
                                    Button(onClick = { onReview(item, 4); expandedId = null }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), contentPadding = PaddingValues(4.dp)) { Text("Good", fontSize = 11.sp) }
                                    Button(onClick = { onReview(item, 5); expandedId = null }, modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(4.dp)) { Text("Easy", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiFeedbackBlock(feedback: String?) {
    if (feedback == null) return
    val isPass = feedback.contains("✅") || feedback.contains("correct", ignoreCase = true)
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(if (isPass) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFF59E0B).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text("🤖 AI Review:", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (isPass) Color(0xFF10B981) else Color(0xFFF59E0B))
        Spacer(Modifier.height(4.dp))
        Text(feedback, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WriteCheckRatingRow(
    userInput: String,
    isChecking: Boolean,
    checkLabel: String,
    aiPrompt: (String) -> String,
    item: LearningItem,
    checkingItem: androidx.compose.runtime.MutableState<String?>,
    aiFeedback: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    coroutineScope: CoroutineScope,
    onReview: (LearningItem, Int) -> Unit,
    expandedId: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = {
                if (userInput.isBlank()) return@Button
                checkingItem.value = item.id
                coroutineScope.launch {
                    val result = GeminiService.chat(HeartRateState.geminiApiKey, aiPrompt(userInput))
                    aiFeedback[item.id] = result
                    checkingItem.value = null
                    HeartRateState.log("AI check '${item.content.take(30)}': ${result.take(60)}")
                }
            },
            modifier = Modifier.weight(1.5f),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(4.dp),
            enabled = userInput.isNotBlank() && !isChecking
        ) {
            if (isChecking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            else Text(checkLabel, fontSize = 11.sp)
        }
        Button(onClick = { onReview(item, 1); expandedId(); aiFeedback.remove(item.id) }, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), contentPadding = PaddingValues(4.dp)) { Text("Again", fontSize = 11.sp) }
        Button(onClick = { onReview(item, 4); expandedId(); aiFeedback.remove(item.id) }, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), contentPadding = PaddingValues(4.dp)) { Text("Got it", fontSize = 11.sp) }
    }
}

// ── Accuracy Trend Chart ──────────────────────────────────────────────────────

@Composable
fun AccuracyTrendChart(sessions: List<com.example.pulsebeatlogger.data.SkillSession>) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📈 Accuracy Trend (last ${sessions.size} sessions)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            val avgAccuracy = sessions.map { it.accuracyPct }.average()
            Text("Average: ${avgAccuracy.toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                val values = sessions.map { it.accuracyPct }
                val maxVal = values.max().coerceAtLeast(100f)
                val w = size.width
                val h = size.height
                val step = if (values.size > 1) w / (values.size - 1) else w

                // Draw guide line at 80%
                val guideY = h - (80f / maxVal) * h
                drawLine(
                    color = surfaceVariant,
                    start = Offset(0f, guideY),
                    end = Offset(w, guideY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )

                // Draw trend line
                for (i in 1 until values.size) {
                    val x1 = step * (i - 1)
                    val y1 = h - (values[i - 1] / maxVal) * h
                    val x2 = step * i
                    val y2 = h - (values[i] / maxVal) * h
                    drawLine(
                        color = primaryColor,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // Draw points
                values.forEachIndexed { i, v ->
                    val cx = step * i
                    val cy = h - (v / maxVal) * h
                    drawCircle(color = primaryColor, radius = 4.dp.toPx(), center = Offset(cx, cy))
                }
            }
        }
    }
}

// ── Weak Item Heatmap ─────────────────────────────────────────────────────────

@Composable
fun WeakItemHeatmap(items: List<LearningItem>) {
    val categories = items.groupBy { it.category }
    if (categories.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔥 Category Strength Map", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            categories.entries.sortedBy { it.value.map { i -> i.lastAccuracy }.average() }.forEach { (cat, catItems) ->
                val avgAcc = catItems.map { it.lastAccuracy }.average().toFloat()
                val label = cat.replace("_", " ").replaceFirstChar { it.uppercase() }
                val barColor = when {
                    avgAcc >= 0.8f -> Color(0xFF10B981)
                    avgAcc >= 0.5f -> Color(0xFFF59E0B)
                    else -> Color(0xFFEF4444)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                    LinearProgressIndicator(
                        progress = { avgAcc.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(10.dp),
                        color = barColor
                    )
                    Text("${(avgAcc * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = barColor)
                }
            }
        }
    }
}

fun calculateLiveScore(): Triple<Int, String, String> {
    val mode = HeartRateState.trackingMode
    val hr = HeartRateState.currentBpm
    val motion = HeartRateState.sleepMotionMag
    val db = HeartRateState.sleepSoundDb
    val speed = HeartRateState.gpsSpeed
    
    var score = 100
    var rating = "Excellent"
    var verdict = "Good form and steady pace."
    
    when (mode) {
        "pushup" -> {
            if (hr in 1..109) {
                score -= Math.min(25, (110 - hr) * 2)
            } else if (hr > 160) {
                score -= Math.min(25, (hr - 160) * 2)
            }
            if (motion < 0.5) {
                score -= 30
            }
            if (score >= 85) {
                rating = "Optimal Sync"
                verdict = "Pushup cadence and cardiovascular load are in balance."
            } else if (score >= 65) {
                rating = "Keep Going"
                verdict = "Form is stable, keep pumping reps!"
            } else {
                rating = "Low Intensity / Idle"
                verdict = "Increase speed of pushups or wait for heart rate build."
            }
        }
        "japanese" -> {
            if (db < 45) {
                score -= 40
            }
            if (score >= 80) {
                rating = "Natural Sounding"
                verdict = "Intonation frequency matches native accent models."
            } else {
                rating = "Muted / Low Audio"
                verdict = "Speak up! Microphone needs to capture >45dB voice level."
            }
        }
        "ukulele" -> {
            val limit = HeartRateState.wristLimit
            if (motion * 100 > limit) {
                score -= Math.min(50, ((motion * 100 - limit) * 2).toInt())
            }
            if (db < 45) {
                score -= 30
            }
            if (score >= 80) {
                rating = "Posture Approved"
                verdict = "Strumming wrist angle is flat (<${limit.toInt()}°), protecting joints."
            } else {
                rating = "Wrist Strain Alert"
                verdict = "Wrist angle is too high! Keep it flat to prevent wrist strain."
            }
        }
        "running" -> {
            val reqSpeed = HeartRateState.speedRequired
            if (speed < reqSpeed) {
                score -= 30
            }
            if (hr in 1..129) {
                score -= 20
            }
            if (score >= 80) {
                rating = "Optimal Pace"
                verdict = "Cadence is synchronized with target speed."
            } else {
                rating = "Speed / HR Mismatch"
                verdict = "Increase pace to hit running gait zones."
            }
        }
        "stress" -> {
            if (hr > 95) {
                score -= Math.min(45, (hr - 95) * 2)
            }
            if (motion > 0.1) {
                score -= 30
            }
            if (score >= 85) {
                rating = "Rest Homeostasis"
                verdict = "Zen. Parasympathetic relaxation active."
            } else if (score >= 60) {
                rating = "Moderate Stress"
                verdict = "Elevated heart rate. Sit still and focus on slow breathing."
            } else {
                rating = "Panic Alert"
                verdict = "Sympathetic arousal detected. Initiate Box Breathing now!"
            }
        }
        else -> {
            score = 0
            rating = "N/A"
            verdict = "Select a preset or customize settings to begin."
        }
    }
    
    score = Math.max(0, Math.min(100, score))
    return Triple(score, rating, verdict)
}
