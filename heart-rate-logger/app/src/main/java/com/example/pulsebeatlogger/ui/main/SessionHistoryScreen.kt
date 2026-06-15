package com.example.pulsebeatlogger.ui.main

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.pulsebeatlogger.HeartRateState
import com.example.pulsebeatlogger.data.AppDatabase
import com.example.pulsebeatlogger.data.SkillSession
import com.example.pulsebeatlogger.data.SensorEvent as SensorEventData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Session History List ──────────────────────────────────────────────────────

@Composable
fun SessionHistoryScreen(
    db: AppDatabase,
    context: Context,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SkillSession>>(emptyList()) }
    var filterSkill by remember { mutableStateOf("All Skills") }
    var allSkills by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSession by remember { mutableStateOf<SkillSession?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sessions = db.skillSessionDao().getRecentSessions(100)
            allSkills = db.skillSessionDao().getDistinctSkillNames()
        }
    }

    // Drill-down to session detail
    selectedSession?.let { session ->
        SessionDetailScreen(
            session = session,
            db = db,
            context = context,
            onBack = { selectedSession = null },
            modifier = modifier
        )
        return
    }

    val fmt = remember { SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()) }
    val displayedSessions = if (filterSkill == "All Skills") sessions
                            else sessions.filter { it.skillName == filterSkill }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top bar — extra top padding keeps back button below status bar / notch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("← Back", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                "Session History",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )

            // Export all button
            TextButton(onClick = {
                scope.launch { exportAllSessions(context, db) }
            }) { Text("Export", fontSize = 12.sp) }
        }

        // Skill filter chips
        if (allSkills.isNotEmpty()) {
            val chipList = listOf("All Skills") + allSkills
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chipList.forEach { skill ->
                    val selected = skill == filterSkill
                    Surface(
                        modifier = Modifier.clickable { filterSkill = skill },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            skill.take(16),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 11.sp,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (displayedSessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📭", fontSize = 48.sp)
                    Text("No sessions yet", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Complete a practice session to see it here", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedSessions) { session ->
                    SessionRowCard(session = session, fmt = fmt, onClick = { selectedSession = session })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SessionRowCard(session: SkillSession, fmt: SimpleDateFormat, onClick: () -> Unit) {
    val dur = session.durationSeconds
    val durStr = if (dur >= 3600) "${dur/3600}h ${(dur%3600)/60}m" else "${dur/60}m ${dur%60}s"

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // HR badge
            if (session.avgHr > 0f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFFEF4444).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text("${session.avgHr.toInt()}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                    Text("bpm", fontSize = 9.sp, color = Color(0xFFEF4444))
                }
            } else {
                Box(
                    Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("📚", fontSize = 20.sp) }
            }

            // Info
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.skillName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                Text(fmt.format(Date(session.startTime)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dur > 0) Text("⏱ $durStr", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.itemsReviewed > 0) Text("📖 ${session.itemsReviewed} items", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.accuracyPct > 0f) Text("✓ ${session.accuracyPct.toInt()}%", fontSize = 11.sp, color = Color(0xFF10B981))
                }
            }

            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Session Detail ────────────────────────────────────────────────────────────

@Composable
fun SessionDetailScreen(
    session: SkillSession,
    db: AppDatabase,
    context: Context,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var sensorEvents by remember { mutableStateOf<List<SensorEventData>>(emptyList()) }
    val scrollState = rememberScrollState()

    LaunchedEffect(session.id) {
        withContext(Dispatchers.IO) {
            sensorEvents = db.sensorEventDao().getForSession(session.id)
        }
    }

    val fmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("← Back", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(session.skillName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(fmt.format(Date(session.startTime)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { scope.launch { exportSession(context, session, sensorEvents) } }) {
                Text("Export", fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stats row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dur = session.durationSeconds
                val durStr = if (dur >= 3600) "${dur/3600}h ${(dur%3600)/60}m" else "${dur/60}m ${dur%60}s"
                StatChip("⏱", "Duration", durStr, Modifier.weight(1f))
                if (session.avgHr > 0f) StatChip("❤️", "Avg HR", "${session.avgHr.toInt()} bpm", Modifier.weight(1f))
                if (session.itemsReviewed > 0) StatChip("📖", "Items", "${session.itemsReviewed}", Modifier.weight(1f))
            }
            if (session.maxHr > 0f) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("⬆️", "Max HR",  "${session.maxHr.toInt()} bpm", Modifier.weight(1f))
                    StatChip("⬇️", "Min HR",  "${session.minHr.toInt()} bpm", Modifier.weight(1f))
                    if (session.calories > 0f) StatChip("🔥", "Calories", "${session.calories.toInt()} kcal", Modifier.weight(1f))
                }
            }

            // HR chart
            val hrPoints = remember(session.hrDataPoints) {
                runCatching {
                    val arr = JSONArray(session.hrDataPoints)
                    (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        Pair(obj.optLong("timeOffset", (i * 5000).toLong()), obj.optInt("hr", 0))
                    }.filter { it.second > 0 }
                }.getOrDefault(emptyList())
            }

            if (hrPoints.size >= 3) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("❤️ Heart Rate Over Session", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        HrMiniChart(points = hrPoints, modifier = Modifier.fillMaxWidth().height(100.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Start", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("End", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Voice profile if present
            if (session.avgPitchHz > 0f) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🎤 Passive Voice Profile", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Speaking range: ${session.speakingRegister}", fontSize = 12.sp)
                        Text("Avg pitch: ${"%.1f".format(session.avgPitchHz)} Hz  |  Range: ${"%.0f".format(session.pitchMinHz)}–${"%.0f".format(session.pitchMaxHz)} Hz", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Sensor event timeline
            if (sensorEvents.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("📡 Sensor Timeline (${sensorEvents.size} events)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                        // Group by type, show count
                        val grouped = sensorEvents.groupBy { it.sensorType }
                        grouped.entries.sortedBy { it.key }.forEach { (type, events) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(sensorTypeLabel(type), fontSize = 11.sp)
                                Text("${events.size} readings", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        // First 10 events for inspection
                        Text("Recent events:", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        sensorEvents.takeLast(8).reversed().forEach { event ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${sensorTypeLabel(event.sensorType)}: ${event.valueJson.take(60)}",
                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    timeFmt.format(Date(event.timestamp)),
                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // AI feedback if present
            if (session.feedbackText.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🤖 AI Coach Feedback", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(session.feedbackText, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatChip(emoji: String, label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$emoji $label", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HrMiniChart(points: List<Pair<Long, Int>>, modifier: Modifier) {
    val lineColor = Color(0xFFEF4444)
    Canvas(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(4.dp)) {
        if (points.size < 2) return@Canvas
        val minHr = points.minOf { it.second }.toFloat()
        val maxHr = points.maxOf { it.second }.toFloat()
        val hrRange = (maxHr - minHr).coerceAtLeast(1f)
        val minT = points.first().first.toFloat()
        val maxT = points.last().first.toFloat()
        val timeRange = (maxT - minT).coerceAtLeast(1f)

        val path = Path()
        points.forEachIndexed { i, (t, hr) ->
            val x = (t - minT) / timeRange * size.width
            val y = size.height - ((hr - minHr) / hrRange * size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.5f))

        // Zone reference line at 70% of max
        val zone70y = size.height - (0.7f * size.height)
        drawLine(Color(0xFFF59E0B).copy(alpha = 0.4f), Offset(0f, zone70y), Offset(size.width, zone70y), strokeWidth = 1f)
    }
}

private fun sensorTypeLabel(type: String) = when (type) {
    "BLE_HR"           -> "💓 HR"
    "GPS"              -> "📍 GPS"
    "ACCELEROMETER"    -> "📐 Motion"
    "MICROPHONE"       -> "🎙️ Mic"
    "WATCH_HR"         -> "⌚ Watch"
    "DEVICE_CONNECT"   -> "🔗 Connect"
    "DEVICE_DISCONNECT"-> "🔌 Disconnect"
    "SESSION_START"    -> "▶ Start"
    "SESSION_END"      -> "⏹ End"
    else               -> type
}

// ── Export helpers ────────────────────────────────────────────────────────────

suspend fun exportAllSessions(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
    val sessions = db.skillSessionDao().getRecentSessions(500)
    val json = org.json.JSONArray()
    sessions.forEach { s ->
        val events = db.sensorEventDao().getForSession(s.id)
        json.put(sessionToJson(s, events))
    }
    shareJson(context, json.toString(2), "all_sessions_${System.currentTimeMillis()}.json")
}

suspend fun exportSession(context: Context, session: SkillSession, events: List<SensorEventData>) = withContext(Dispatchers.IO) {
    val json = sessionToJson(session, events)
    shareJson(context, json.toString(2), "session_${session.id}.json")
}

private fun sessionToJson(s: SkillSession, events: List<SensorEventData>): org.json.JSONObject {
    val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return org.json.JSONObject().apply {
        put("id", s.id)
        put("skillName", s.skillName)
        put("startTime", timeFmt.format(Date(s.startTime)))
        put("endTime", timeFmt.format(Date(s.endTime)))
        put("durationSeconds", s.durationSeconds)
        put("avgHr", s.avgHr)
        put("maxHr", s.maxHr)
        put("minHr", s.minHr)
        put("calories", s.calories)
        put("distanceM", s.distance)
        put("itemsReviewed", s.itemsReviewed)
        put("accuracyPct", s.accuracyPct)
        put("speakingRegister", s.speakingRegister)
        put("avgPitchHz", s.avgPitchHz)
        put("feedbackText", s.feedbackText)
        put("hrDataPoints", runCatching { JSONArray(s.hrDataPoints) }.getOrDefault(JSONArray()))
        val eventsArr = org.json.JSONArray()
        events.forEach { e ->
            eventsArr.put(org.json.JSONObject().apply {
                put("timestamp", timeFmt.format(Date(e.timestamp)))
                put("type", e.sensorType)
                put("data", runCatching { org.json.JSONObject(e.valueJson) }.getOrElse { org.json.JSONObject().put("raw", e.valueJson) })
            })
        }
        put("sensorEvents", eventsArr)
    }
}

private fun shareJson(context: Context, content: String, filename: String) {
    try {
        val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = File(dir, filename)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export session data").also {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        HeartRateState.log("Export failed: ${e.message}")
    }
}
