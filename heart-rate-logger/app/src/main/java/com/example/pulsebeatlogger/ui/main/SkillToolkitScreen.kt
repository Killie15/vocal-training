package com.example.pulsebeatlogger.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.pulsebeatlogger.HeartRateState
import com.example.pulsebeatlogger.SkillToolkitRegistry
import com.example.pulsebeatlogger.applyReview
import com.example.pulsebeatlogger.TunerEngine
import com.example.pulsebeatlogger.data.AppDatabase
import com.example.pulsebeatlogger.data.LearningItem
import com.example.pulsebeatlogger.data.SkillSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillToolkitScreen(
    db: AppDatabase,
    innerPadding: PaddingValues,
    onOpenTrain: () -> Unit,
    onSwitchSkill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skillKey = HeartRateState.dbSkillKey()
    val template = remember(HeartRateState.trackingMode, HeartRateState.activeSkillName) {
        SkillToolkitRegistry.templateFor(HeartRateState.trackingMode, HeartRateState.activeSkillName)
    }

    var activeTabId by remember(HeartRateState.trackingMode, HeartRateState.activeSkillName) {
        mutableStateOf(template.tabs.first().id)
    }
    LaunchedEffect(template) {
        if (template.tabs.none { it.id == activeTabId }) {
            activeTabId = template.tabs.first().id
        }
    }

    var allItems by remember(skillKey) { mutableStateOf<List<LearningItem>>(emptyList()) }
    var sessions by remember(skillKey) { mutableStateOf<List<SkillSession>>(emptyList()) }

    LaunchedEffect(skillKey) {
        withContext(Dispatchers.IO) {
            allItems = db.learningItemDao().getDueItems(skillKey, System.currentTimeMillis(), limit = 50)
            sessions = db.skillSessionDao().getRecentSessions(50)
                .filter { it.skillName == skillKey || it.skillName == HeartRateState.trackingMode }
        }
    }

    val filteredItems = remember(allItems, activeTabId) {
        when (activeTabId) {
            "vocab" -> allItems.filter { it.category == "vocabulary" }
            "pitch" -> allItems.filter { it.category == "pitch_accent" || it.category == "pronunciation" }
            "grammar" -> allItems.filter { it.category == "grammar" }
            "listening" -> allItems.filter { it.category == "listening" }
            "technique", "form", "gait", "challenges", "problems" ->
                allItems.filter { it.category !in setOf("vocabulary", "pitch_accent", "grammar", "listening") }
            else -> allItems
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${template.shortName} toolkit", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                Text(
                    HeartRateState.activeSkillName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onSwitchSkill, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Switch", fontSize = 10.sp)
                }
                Button(onClick = onOpenTrain, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Train →", fontSize = 10.sp)
                }
            }
        }

        Text(
            "Skill-specific tools — switch tabs below for tuning, vocab, chords, and more.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        ScrollableTabRow(
            selectedTabIndex = template.tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0),
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            template.tabs.forEach { tab ->
                Tab(
                    selected = activeTabId == tab.id,
                    onClick = { activeTabId = tab.id },
                    text = { Text("${tab.emoji} ${tab.label}", fontSize = 11.sp, maxLines = 1) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (activeTabId) {
                "tune" -> UkuleleTunerPanel(context)
                "chords" -> UkuleleChordsPanel(context)
                "vocab", "pitch", "grammar", "listening", "technique", "form", "gait",
                "challenges", "problems", "canvas" ->
                    CategoryDeckPanel(
                        title = template.tabs.find { it.id == activeTabId }?.label ?: "Practice",
                        items = filteredItems.ifEmpty { allItems },
                        emptyHint = when (activeTabId) {
                            "vocab" -> "No vocab cards yet — Sync now or generate a curriculum on Find."
                            "pitch" -> "No pitch-accent cards yet."
                            else -> "No items in this section yet. Open Train for live practice."
                        }
                    )
                "reps" -> PhysicalRepsPanel()
                "zones", "hr" -> HeartRateZonesPanel()
                "pace" -> RunningPacePanel()
                "breathe" -> BreathingPanel()
                "review" -> {
                    if (allItems.isEmpty()) {
                        EmptyToolkitCard("Nothing due right now. Great job — check back tomorrow or add items on Find.")
                    } else {
                        SrsTaskList(
                            items = allItems,
                            coroutineScope = scope,
                            context = context,
                            onReview = { item, rating ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.learningItemDao().applyReview(item, rating)
                                        allItems = db.learningItemDao()
                                            .getDueItems(skillKey, System.currentTimeMillis(), limit = 50)
                                    }
                                }
                            }
                        )
                    }
                }
                "log" -> SessionLogPanel(sessions, skillKey)
                else -> EmptyToolkitCard("Open Train for live sensors and coaching.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EmptyToolkitCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryDeckPanel(title: String, items: List<LearningItem>, emptyHint: String) {
    if (items.isEmpty()) {
        EmptyToolkitCard(emptyHint)
        return
    }
    var index by remember(items) { mutableIntStateOf(0) }
    val item = items[index.coerceIn(0, items.lastIndex)]
    val hint = remember(item.contentJson) {
        runCatching { JSONObject(item.contentJson).optString("hint", "") }.getOrDefault("")
    }
    val example = remember(item.contentJson) {
        runCatching { JSONObject(item.contentJson).optString("example", "") }.getOrDefault("")
    }
    val tip = remember(item.contentJson) {
        runCatching { JSONObject(item.contentJson).optString("tip", "") }.getOrDefault("")
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${index + 1} / ${items.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotEmpty()) Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (example.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(example, fontSize = 13.sp)
                }
            }
            if (tip.isNotEmpty()) Text("💡 $tip", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { index = (index - 1).coerceAtLeast(0) },
                    modifier = Modifier.weight(1f),
                    enabled = index > 0
                ) { Text("← Prev") }
                OutlinedButton(
                    onClick = { index = (index + 1).coerceAtMost(items.lastIndex) },
                    modifier = Modifier.weight(1f),
                    enabled = index < items.lastIndex
                ) { Text("Next →") }
            }
        }
    }
}

private data class UkuleleString(val label: String, val note: String, val hz: Float)

private val UKULELE_STRINGS = listOf(
    UkuleleString("4th (top)", "G", 392.0f),
    UkuleleString("3rd", "C", 261.63f),
    UkuleleString("2nd", "E", 329.63f),
    UkuleleString("1st (bottom)", "A", 440.0f)
)

private data class UkuleleChord(val name: String, val frets: String, val notes: List<String>, val tip: String)

private val UKULELE_CHORDS = listOf(
    UkuleleChord("C", "0003", listOf("G", "C", "E", "C"), "Index on 3rd fret of A string"),
    UkuleleChord("G", "0232", listOf("G", "B", "D", "G"), "Common campfire chord"),
    UkuleleChord("Am", "2000", listOf("A", "E", "A", "C"), "Only one finger — 2nd fret G string"),
    UkuleleChord("F", "2010", listOf("A", "C", "F", "A"), "Barre-lite: index on E and A strings")
)

private fun micGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** Starts [TunerEngine] when mic is granted; re-checks after returning from Settings. */
@Composable
private fun rememberUkuleleTuner(context: Context): Triple<TunerEngine, Boolean, () -> Unit> {
    val tuner = remember { TunerEngine(context) }
    var granted by remember { mutableStateOf(micGranted(context)) }
    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LifecycleResumeEffect(Unit) {
        granted = micGranted(context)
        onPauseOrDispose {}
    }

    DisposableEffect(granted) {
        if (granted) {
            tuner.start()
            onDispose { tuner.stop() }
        } else {
            onDispose {}
        }
    }

    return Triple(tuner, granted) { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
}

@Composable
private fun MicPermissionPrompt(onRequest: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Microphone access is needed to hear your strings.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRequest) { Text("Allow microphone") }
    }
}

@Composable
private fun UkuleleTunerPanel(context: Context) {
    var selectedString by remember { mutableIntStateOf(0) }
    val target = UKULELE_STRINGS[selectedString]
    val (tuner, micOk, requestMic) = rememberUkuleleTuner(context)

    val centsFromTarget = if (tuner.pitchHz > 0f) {
        (1200 * log2(tuner.pitchHz / target.hz)).roundToInt()
    } else null

    val tuneOk = centsFromTarget != null && kotlin.math.abs(centsFromTarget) <= 15

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("String tuner", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Pluck one string at a time. Select the string below.", fontSize = 12.sp)

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UKULELE_STRINGS.forEachIndexed { i, s ->
                    FilterChip(
                        selected = selectedString == i,
                        onClick = { selectedString = i },
                        label = { Text("${s.note} · ${s.label}", fontSize = 11.sp) }
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            tuneOk -> Color(0xFF10B981).copy(alpha = 0.2f)
                            centsFromTarget != null -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Target: ${target.note}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (tuner.pitchHz > 0f) "${tuner.noteName}${tuner.octave}" else "--",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (tuner.pitchHz > 0f) "${tuner.pitchHz.toInt()} Hz" else "Listening…",
                        fontSize = 13.sp
                    )
                    centsFromTarget?.let { c ->
                        Text(
                            when {
                                kotlin.math.abs(c) <= 10 -> "✅ In tune ($c cents)"
                                c > 0 -> "↑ Sharp (+$c cents) — tune down"
                                else -> "↓ Flat ($c cents) — tune up"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (kotlin.math.abs(c) <= 10) Color(0xFF059669) else Color(0xFFD97706)
                        )
                    } ?: if (!micOk) {
                        MicPermissionPrompt(onRequest = requestMic)
                    } else {
                        Text(
                            if (tuner.statusMessage.isNotBlank()) tuner.statusMessage
                            else "Play the ${target.note} string (phone mic near sound hole)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (HeartRateState.isServiceRunning && !HeartRateState.tunerMicActive) {
                        Text(
                            "Tip: Auto-Tracking is ON — tuner takes mic when this tab is open.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UkuleleChordsPanel(context: Context) {
    var selectedChord by remember { mutableIntStateOf(0) }
    var checkString by remember { mutableIntStateOf(0) }
    val chord = UKULELE_CHORDS[selectedChord]
    val expectedNote = chord.notes.getOrElse(checkString) { "?" }

    val (tuner, micOk, requestMic) = rememberUkuleleTuner(context)

    val noteMatch = tuner.noteName.equals(expectedNote, ignoreCase = true) &&
        tuner.tuneState != TunerEngine.TuneState.SILENT

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Chord checker", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Pick a chord, pluck each string — green when the note matches.", fontSize = 12.sp)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UKULELE_CHORDS.forEachIndexed { i, c ->
                    FilterChip(
                        selected = selectedChord == i,
                        onClick = { selectedChord = i; checkString = 0 },
                        label = { Text(c.name, fontSize = 12.sp) }
                    )
                }
            }

            Text("Frets: ${chord.frets}  ·  ${chord.tip}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Check string ${checkString + 1} — expect $expectedNote", fontSize = 13.sp, fontWeight = FontWeight.Medium)

            if (!micOk) {
                MicPermissionPrompt(onRequest = requestMic)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chord.notes.forEachIndexed { i, note ->
                    FilterChip(
                        selected = checkString == i,
                        onClick = { checkString = i },
                        label = { Text("Str ${i + 1}: $note", fontSize = 10.sp) }
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (noteMatch) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            noteMatch -> "✅ $expectedNote detected — string ${checkString + 1} sounds right"
                            tuner.pitchHz > 0f -> "Heard ${tuner.noteName} — want $expectedNote"
                            tuner.statusMessage.isNotBlank() -> tuner.statusMessage
                            else -> "Pluck string ${checkString + 1}…"
                        },
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun PhysicalRepsPanel() {
    var reps by remember { mutableIntStateOf(0) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rep counter", fontWeight = FontWeight.Bold)
            Text("$reps", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { reps++ }) { Text("+1") }
                OutlinedButton(onClick = { reps = 0 }) { Text("Reset") }
            }
            Text("Use Train tab for HR-tracked sets.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HeartRateZonesPanel() {
    val bpm = HeartRateState.currentBpm
    val zone = when {
        bpm <= 0 -> "Connect HR strap on Train tab"
        bpm < 100 -> "Zone 1 — Warm-up"
        bpm < 130 -> "Zone 2 — Fat burn"
        bpm < 155 -> "Zone 3 — Aerobic"
        bpm < 175 -> "Zone 4 — Threshold"
        else -> "Zone 5 — Max"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("❤️ $zone", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(if (bpm > 0) "$bpm BPM" else "--", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun RunningPacePanel() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Live pace", fontWeight = FontWeight.Bold)
            Text("Speed: ${"%.1f".format(HeartRateState.gpsSpeed)} m/s", fontSize = 18.sp)
            Text("Distance: ${"%.0f".format(HeartRateState.gpsDistance)} m", fontSize = 14.sp)
            Text("Start Auto-Tracking on Train for GPS.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BreathingPanel() {
    var phase by remember { mutableStateOf("inhale") }
    var seconds by remember { mutableIntStateOf(4) }
    LaunchedEffect(phase) {
        kotlinx.coroutines.delay(seconds * 1000L)
        phase = if (phase == "inhale") "hold" else if (phase == "hold") "exhale" else "inhale"
        seconds = when (phase) {
            "inhale" -> 4
            "hold" -> 2
            else -> 6
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(phase.replaceFirstChar { it.uppercase() }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${seconds}s", fontSize = 40.sp)
            Text("4-2-6 box breathing", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionLogPanel(sessions: List<SkillSession>, skillKey: String) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    if (sessions.isEmpty()) {
        EmptyToolkitCard("No sessions logged for this skill yet. Start Auto-Tracking on Train.")
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recent sessions", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            sessions.take(12).forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(fmt.format(s.startTime), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("${s.durationSeconds}s · ${s.itemsReviewed} reviews", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${s.accuracyPct.toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
