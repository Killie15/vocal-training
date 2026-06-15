package com.example.pulsebeatlogger.ui.main

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pulsebeatlogger.*
import com.example.pulsebeatlogger.data.AppDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TheSystemScreen(
    db: AppDatabase,
    context: Context,
    prefs: SharedPreferences,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    var inputText by remember { mutableStateOf("") }
    var showSetup by remember {
        mutableStateOf(!GoogleAuthHelper.isGoogleConnected(context) || HeartRateState.googleSheetUrl.isBlank())
    }
    var sheetUrlInput by remember { mutableStateOf(HeartRateState.googleSheetUrl) }
    var driveFolderInput by remember { mutableStateOf(HeartRateState.googleDriveFolderId) }
    var releaseFolderInput by remember {
        mutableStateOf(DriveSyncService.releaseFolderUrl(prefs).ifBlank {
            prefs.getString(DriveSyncService.PREF_RELEASE_FOLDER_ID, "") ?: ""
        })
    }
    val chatListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        SystemService.loadFromDb(db)
    }

    LaunchedEffect(HeartRateState.systemChatLog.size, HeartRateState.isSystemLoading) {
        val extra = if (HeartRateState.isSystemLoading) 1 else 0
        val lastIndex = HeartRateState.systemChatLog.size - 1 + extra
        if (lastIndex >= 0) {
            chatListState.animateScrollToItem(lastIndex)
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (GoogleAuthHelper.handleSignInResult(context, result.data)) {
            GoogleAuthHelper.SignInStatus.Success -> {
                HeartRateState.googleAccountEmail = GoogleAuthHelper.accountEmail(context) ?: ""
                coroutineScope.launch {
                    ErrorReportService.ensureDiagnosticsSheet(context, prefs)?.let { url ->
                        sheetUrlInput = url
                        HeartRateState.googleSheetUrl = url
                    }
                }
                DriveSyncService.requestSync()
                Toast.makeText(context, "Google connected — syncing in background", Toast.LENGTH_SHORT).show()
            }
            GoogleAuthHelper.SignInStatus.NeedsScope -> {
                activity?.let { GoogleAuthHelper.requestGoogleScopes(it) }
                Toast.makeText(
                    context,
                    "Grant Calendar & Sheets access on the next screen",
                    Toast.LENGTH_LONG
                ).show()
            }
            GoogleAuthHelper.SignInStatus.Failed -> {
                Toast.makeText(
                    context,
                    GoogleAuthHelper.lastSignInError ?: "Google sign-in failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("◈ The System", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "${HeartRateState.systemMemoryBank.size} memories · ${HeartRateState.systemChatLog.size} messages",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (HeartRateState.driveSyncStatus.isNotBlank()) {
                    Text(
                        "Drive: ${HeartRateState.driveSyncStatus}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = { showSetup = !showSetup }) {
                Text(if (showSetup) "Hide" else "Setup", fontSize = 12.sp)
            }
        }

        if (HeartRateState.systemGoals.size >= SystemService.MAX_ACTIVE_GOALS_SOFT) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "You have ${HeartRateState.systemGoals.size} active goals — consider pausing one before adding more.",
                    modifier = Modifier.padding(10.dp),
                    fontSize = 11.sp
                )
            }
        }

        if (HeartRateState.systemGoals.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeartRateState.systemGoals.forEach { goal ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            goal.title,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (showSetup) {
            GoogleSetupCardInternal(
                sheetUrlInput = sheetUrlInput,
                onSheetUrlChange = { sheetUrlInput = it },
                driveFolderInput = driveFolderInput,
                onDriveFolderChange = { driveFolderInput = it },
                onSaveDriveFolder = {
                    val id = GoogleDriveService.parseFolderId(driveFolderInput.trim()) ?: driveFolderInput.trim()
                    prefs.edit().putString(DriveSyncService.PREF_FOLDER_ID, id).apply()
                    HeartRateState.googleDriveFolderId = id
                    Toast.makeText(context, "Drive folder saved", Toast.LENGTH_SHORT).show()
                },
                onSyncNow = {
                    coroutineScope.launch {
                        val result = DriveSyncService.sync(context, db, prefs)
                        Toast.makeText(context, result.message.take(120), Toast.LENGTH_LONG).show()
                        if (result.updateAvailable != null && result.apkFile != null) {
                            HeartRateState.drivePendingUpdate = result.updateAvailable to result.apkFile
                        } else if (result.updateBlockedReason != null) {
                            Toast.makeText(context, result.updateBlockedReason.take(200), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onCheckUpdate = {
                    coroutineScope.launch {
                        val result = UpdateChannel.checkForUpdate(context, prefs)
                        Toast.makeText(context, result.message.take(200), Toast.LENGTH_LONG).show()
                        when {
                            result.localApk != null && result.remoteInfo != null ->
                                HeartRateState.drivePendingUpdate = result.remoteInfo to result.localApk
                            result.remoteInfo != null ->
                                HeartRateState.httpUpdatePending = result.remoteInfo
                        }
                    }
                },
                onSetupAutoUpdates = {
                    coroutineScope.launch {
                        val id = DriveSyncService.ensureReleaseFolder(context, prefs)
                        if (id != null) {
                            releaseFolderInput = DriveSyncService.releaseFolderUrl(prefs)
                            Toast.makeText(
                                context,
                                "Auto-updates ready — I publish here, you tap Install",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(context, "Connect Google first", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSaveReleaseFolder = {
                    val id = GoogleDriveService.parseFolderId(releaseFolderInput.trim())
                        ?: releaseFolderInput.trim()
                    if (id.isBlank()) {
                        Toast.makeText(context, "Paste a Drive folder URL", Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit().putString(DriveSyncService.PREF_RELEASE_FOLDER_ID, id).apply()
                        releaseFolderInput = DriveSyncService.releaseFolderUrl(prefs)
                        Toast.makeText(context, "Update channel saved", Toast.LENGTH_SHORT).show()
                    }
                },
                releaseFolderInput = releaseFolderInput,
                onReleaseFolderChange = { releaseFolderInput = it },
                onSaveSheet = {
                    prefs.edit().putString("googleSheetUrl", sheetUrlInput.trim()).apply()
                    HeartRateState.googleSheetUrl = sheetUrlInput.trim()
                    Toast.makeText(context, "Saved — persists across restarts", Toast.LENGTH_SHORT).show()
                },
                onConnectGoogle = { googleSignInLauncher.launch(GoogleAuthHelper.signInIntent(context)) },
                onTestGoogle = {
                    coroutineScope.launch {
                        val msg = GoogleAuthHelper.testAll(
                            context,
                            sheetUrlInput.trim(),
                            driveFolderInput.trim().ifBlank { HeartRateState.googleDriveFolderId }
                        )
                        Toast.makeText(context, msg.take(240), Toast.LENGTH_LONG).show()
                    }
                },
                onSandbox = {
                    GoogleAuthHelper.enableSandbox()
                    prefs.edit().putBoolean("calendarSandboxMode", true).apply()
                },
                onDisconnect = {
                    coroutineScope.launch { GoogleAuthHelper.signOut(context) }
                },
                googleEmail = HeartRateState.googleAccountEmail,
                googleConnected = GoogleAuthHelper.isGoogleConnected(context)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "I want to learn piano",
                "What are my goals?",
                "Log today to my sheet"
            ).forEach { hint ->
                SuggestionChip(
                    onClick = { inputText = hint },
                    label = { Text(hint, fontSize = 9.sp, maxLines = 2) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (HeartRateState.systemChatLog.isEmpty() && !HeartRateState.isSystemLoading) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Talk to The System — messages appear here oldest at top, newest at bottom.\n\nTry: \"My new goal is to run 3x a week\"",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(HeartRateState.systemChatLog, key = { "${it.timestamp}-${it.role}-${it.text.hashCode()}" }) { line ->
                        SystemChatBubble(line)
                    }
                    if (HeartRateState.isSystemLoading) {
                        item(key = "typing") {
                            SystemTypingBubble()
                        }
                    }
                }
            }
        }

        if (HeartRateState.isSystemLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Talk to The System…") },
                maxLines = 4
            )
            Button(
                onClick = {
                    val text = inputText.trim()
                    if (text.isEmpty() || HeartRateState.isSystemLoading) return@Button
                    inputText = ""
                    coroutineScope.launch {
                        HeartRateState.isSystemLoading = true
                        try {
                            SystemService.sendMessage(context, db, prefs, text)
                        } finally {
                            HeartRateState.isSystemLoading = false
                        }
                    }
                },
                enabled = !HeartRateState.isSystemLoading
            ) { Text("Send") }
        }

        TextButton(
            onClick = {
                coroutineScope.launch { SystemService.clearMemory(db, prefs) }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Clear chat history", fontSize = 11.sp)
        }
    }
}

@Composable
private fun SystemChatBubble(line: SystemChatLine) {
    val isUser = line.role == "user"
    val dateFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            },
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isUser) "You" else "System",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(line.text, fontSize = 14.sp)
                Text(
                    dateFmt.format(Date(line.timestamp)),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun SystemTypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Text(
                "System is thinking…",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GoogleSetupCardInternal(
    sheetUrlInput: String,
    onSheetUrlChange: (String) -> Unit,
    driveFolderInput: String,
    onDriveFolderChange: (String) -> Unit,
    onSaveDriveFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSetupAutoUpdates: () -> Unit,
    releaseFolderInput: String,
    onReleaseFolderChange: (String) -> Unit,
    onSaveReleaseFolder: () -> Unit,
    onSaveSheet: () -> Unit,
    onConnectGoogle: () -> Unit,
    onTestGoogle: () -> Unit,
    onSandbox: () -> Unit,
    onDisconnect: () -> Unit,
    googleEmail: String,
    googleConnected: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google (one-time setup)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (googleEmail.isNotBlank() && !HeartRateState.calendarSandboxMode) {
                Text(
                    "Signed in: $googleEmail — data saves to this account's Drive in the cloud.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "Goals and memories save on your phone first, then sync to Drive when online. " +
                    "Errors auto-log to a PulseBeat Learner Diagnostics sheet (Errors tab) " +
                    "and errors.json in your Drive folder.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectGoogle, modifier = Modifier.weight(1f)) {
                    Text(if (googleConnected) "Reconnect" else "Connect Google")
                }
                Button(onClick = onSyncNow, modifier = Modifier.weight(1f), enabled = googleConnected) {
                    Text("Sync now")
                }
            }
            OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                Text("Check for app update")
            }
            Text(
                "App updates download from GitHub automatically (no USB, no Drive upload). " +
                    "Google Drive is only for syncing your profile and memories.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Advanced: legacy Drive update folder (optional fallback)",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onSetupAutoUpdates, modifier = Modifier.fillMaxWidth(), enabled = googleConnected) {
                Text("Link Drive folder for updates (fallback)")
            }
            OutlinedTextField(
                value = releaseFolderInput,
                onValueChange = onReleaseFolderChange,
                label = { Text("Updates folder (optional — defaults to Sync folder)") },
                placeholder = { Text("https://drive.google.com/drive/folders/…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedButton(onClick = onSaveReleaseFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Save updates folder")
            }
            OutlinedTextField(
                value = sheetUrlInput,
                onValueChange = onSheetUrlChange,
                label = { Text("Diagnostics sheet URL (auto-created if blank)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSaveSheet, modifier = Modifier.fillMaxWidth()) { Text("Save sheet") }
            Text(
                "Advanced: override your data folder (leave blank to auto-create)",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = driveFolderInput,
                onValueChange = onDriveFolderChange,
                label = { Text("Your data folder URL (optional)") },
                placeholder = { Text("https://drive.google.com/drive/folders/…") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onSaveDriveFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Save data folder")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestGoogle, modifier = Modifier.weight(1f), enabled = googleConnected) {
                    Text("Test")
                }
                OutlinedButton(onClick = onSandbox, modifier = Modifier.weight(1f)) { Text("Sandbox") }
            }
            if (googleEmail.isNotBlank() && !HeartRateState.calendarSandboxMode) {
                TextButton(onClick = onDisconnect) { Text("Sign out") }
            }
        }
    }
}
