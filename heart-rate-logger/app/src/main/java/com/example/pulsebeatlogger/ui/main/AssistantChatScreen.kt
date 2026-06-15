package com.example.pulsebeatlogger.ui.main

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pulsebeatlogger.*
import com.example.pulsebeatlogger.data.AppDatabase
import kotlinx.coroutines.launch

@Composable
fun AssistantChatScreen(
    db: AppDatabase,
    context: Context,
    prefs: SharedPreferences,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember {
        mutableStateOf(!GoogleAuthHelper.isGoogleConnected(context) || HeartRateState.googleSheetUrl.isBlank())
    }
    var sheetUrlInput by remember { mutableStateOf(HeartRateState.googleSheetUrl) }
    val listState = rememberLazyListState()

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (GoogleAuthHelper.handleSignInResult(context, result.data)) {
            GoogleAuthHelper.SignInStatus.Success -> {
                HeartRateState.googleAccountEmail = GoogleAuthHelper.accountEmail(context) ?: ""
                Toast.makeText(context, "Google connected (Calendar + Sheets)", Toast.LENGTH_SHORT).show()
            }
            GoogleAuthHelper.SignInStatus.NeedsScope -> {
                Toast.makeText(context, "Grant Google access on the next screen", Toast.LENGTH_SHORT).show()
                activity?.let { GoogleAuthHelper.requestGoogleScopes(it) }
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

    LaunchedEffect(HeartRateState.assistantChatLog.size) {
        if (HeartRateState.assistantChatLog.isNotEmpty()) {
            listState.animateScrollToItem(HeartRateState.assistantChatLog.size - 1)
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
            Text("🤖 Assistant", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Hide setup" else "Google setup", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectionChip(
                label = "Gemini",
                ok = HeartRateState.geminiApiKey.isNotBlank(),
                detail = if (HeartRateState.geminiApiKey == "debug") "sandbox" else if (HeartRateState.geminiApiKey.isBlank()) "missing" else "ok"
            )
            ConnectionChip(
                label = "Google",
                ok = GoogleAuthHelper.isGoogleConnected(context),
                detail = when {
                    HeartRateState.calendarSandboxMode -> "sandbox"
                    GoogleAuthHelper.isGoogleConnected(context) -> "ok"
                    else -> "sign in"
                }
            )
            ConnectionChip(
                label = "Sheet",
                ok = GoogleSheetsService.parseSpreadsheetId(HeartRateState.googleSheetUrl) != null,
                detail = if (HeartRateState.googleSheetUrl.isBlank()) "paste URL" else "ok"
            )
        }

        if (showSettings) {
            GoogleSetupCard(
                googleEmail = HeartRateState.googleAccountEmail,
                googleConnected = GoogleAuthHelper.isGoogleConnected(context),
                sheetUrlInput = sheetUrlInput,
                onSheetUrlChange = { sheetUrlInput = it },
                onSaveSheet = {
                    prefs.edit().putString("googleSheetUrl", sheetUrlInput.trim()).apply()
                    HeartRateState.googleSheetUrl = sheetUrlInput.trim()
                    Toast.makeText(context, "Sheet URL saved", Toast.LENGTH_SHORT).show()
                },
                onConnectGoogle = {
                    googleSignInLauncher.launch(GoogleAuthHelper.signInIntent(context))
                },
                onTestGoogle = {
                    coroutineScope.launch {
                        val msg = GoogleAuthHelper.testAll(context, sheetUrlInput.trim())
                        Toast.makeText(context, msg.take(200), Toast.LENGTH_LONG).show()
                    }
                },
                onSandbox = {
                    GoogleAuthHelper.enableSandbox()
                    prefs.edit().putBoolean("calendarSandboxMode", true).apply()
                    Toast.makeText(context, "Google sandbox enabled", Toast.LENGTH_SHORT).show()
                },
                onDisconnect = {
                    coroutineScope.launch {
                        GoogleAuthHelper.signOut(context)
                        prefs.edit().putBoolean("calendarSandboxMode", false).apply()
                        Toast.makeText(context, "Signed out of Google", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Log today to my sheet", "What's on my calendar?", "Show my stats").forEach { hint ->
                SuggestionChip(
                    onClick = { inputText = hint },
                    label = { Text(hint, fontSize = 10.sp, maxLines = 2) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (HeartRateState.assistantChatLog.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "One Google sign-in powers Calendar (schedule) and Sheets (journal/log).\n\n1) Create a Google Sheet\n2) Paste URL in setup\n3) Connect Google or use Sandbox",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(HeartRateState.assistantChatLog) { msg ->
                        ChatBubble(msg)
                    }
                }
            }
        }

        if (HeartRateState.isAssistantLoading) {
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
                placeholder = { Text("Message assistant…") },
                maxLines = 3
            )
            Button(
                onClick = {
                    val text = inputText.trim()
                    if (text.isEmpty() || HeartRateState.isAssistantLoading) return@Button
                    inputText = ""
                    coroutineScope.launch {
                        HeartRateState.isAssistantLoading = true
                        try {
                            AssistantService.sendMessage(context, db, prefs, text)
                        } finally {
                            HeartRateState.isAssistantLoading = false
                        }
                    }
                },
                enabled = !HeartRateState.isAssistantLoading
            ) {
                Text("Send")
            }
        }

        TextButton(
            onClick = { AssistantService.clearHistory() },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Clear chat", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConnectionChip(label: String, ok: Boolean, detail: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (ok) Color(0xFF166534).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            "$label · $detail",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ChatBubble(msg: AssistantChatMessage) {
    val isUser = msg.role == "user"
    val isAction = msg.role == "action"
    val bg = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isAction -> Color(0xFF7C3AED).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val label = when (msg.role) {
        "user" -> "You"
        "action" -> "⚡ Action"
        else -> "Assistant"
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(msg.text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GoogleSetupCard(
    googleEmail: String,
    googleConnected: Boolean,
    sheetUrlInput: String,
    onSheetUrlChange: (String) -> Unit,
    onSaveSheet: () -> Unit,
    onConnectGoogle: () -> Unit,
    onTestGoogle: () -> Unit,
    onSandbox: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google (Calendar + Sheets)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "One sign-in for scheduling and your data log. Create a blank Google Sheet, paste the URL below, share it with your Google account (default if you created it).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = sheetUrlInput,
                onValueChange = onSheetUrlChange,
                label = { Text("Google Sheet URL") },
                placeholder = { Text("https://docs.google.com/spreadsheets/d/…") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSaveSheet, modifier = Modifier.fillMaxWidth()) {
                Text("Save Sheet URL")
            }
            if (googleEmail.isNotBlank()) {
                Text("Signed in: $googleEmail", fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectGoogle, modifier = Modifier.weight(1f)) {
                    Text("Connect Google")
                }
                OutlinedButton(onClick = onTestGoogle, modifier = Modifier.weight(1f), enabled = googleConnected) {
                    Text("Test")
                }
            }
            OutlinedButton(onClick = onSandbox, modifier = Modifier.fillMaxWidth()) {
                Text("Sandbox (no Google account)")
            }
            if (googleEmail.isNotBlank() && !HeartRateState.calendarSandboxMode) {
                TextButton(onClick = onDisconnect) { Text("Sign out") }
            }
            Text(
                "Enable Calendar API + Google Sheets API in Google Cloud Console. Same Android OAuth client as before.",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
