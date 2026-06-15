package com.example.pulsebeatlogger

import android.content.Context
import com.example.pulsebeatlogger.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AssistantChatMessage(
    val role: String,
    val text: String,
    val toolResults: List<String> = emptyList()
)

object AssistantService {

    private val history = mutableListOf<Pair<String, String>>()

    fun clearHistory() {
        history.clear()
        HeartRateState.assistantChatLog.clear()
    }

    suspend fun sendMessage(
        context: Context,
        db: AppDatabase,
        prefs: android.content.SharedPreferences,
        userText: String
    ): AssistantChatMessage = withContext(Dispatchers.IO) {
        val apiKey = HeartRateState.geminiApiKey
        HeartRateState.assistantChatLog.add(AssistantChatMessage("user", userText))
        history.add("user" to userText)

        if (apiKey.isBlank()) {
            val msg = AssistantChatMessage(
                "assistant",
                "Set your Gemini API key in Train → Settings first. Use \"debug\" for offline sandbox."
            )
            HeartRateState.assistantChatLog.add(msg)
            return@withContext msg
        }

        val appContext = buildAppContext(context, db)
        val prompt = buildPrompt(userText, appContext)
        HeartRateState.log("Assistant: sending to Gemini (${userText.take(50)})")

        val rawReply = if (apiKey.trim().lowercase() == "debug") {
            mockAssistantReply(userText)
        } else {
            GeminiService.assistantChat(apiKey, prompt, history.dropLast(1))
        }

        val parsed = parseAssistantResponse(rawReply)
        val toolResults = mutableListOf<String>()

        for (action in parsed.actions) {
            val result = AssistantToolExecutor.execute(context, db, prefs, action)
            toolResults.add("✓ ${action.tool}: $result")
            HeartRateState.assistantChatLog.add(AssistantChatMessage("action", result))
        }

        val finalText = buildString {
            append(parsed.message.ifBlank { rawReply })
            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine()
                toolResults.forEach { appendLine(it) }
            }
        }.trim()

        history.add("assistant" to finalText)
        val msg = AssistantChatMessage("assistant", finalText, toolResults)
        HeartRateState.assistantChatLog.add(msg)
        msg
    }

    private suspend fun buildAppContext(context: Context, db: AppDatabase): String {
        val sessions = db.skillSessionDao().getRecentSessions(5)
        val dueCount = try {
            db.learningItemDao().countAllDue(System.currentTimeMillis())
        } catch (_: Exception) { 0 }

        val googleStatus = when {
            HeartRateState.calendarSandboxMode -> "sandbox"
            GoogleAuthHelper.isGoogleConnected(context) -> "connected (${GoogleAuthHelper.accountEmail(context)})"
            else -> "not connected"
        }
        val sheetConfigured = GoogleSheetsService.parseSpreadsheetId(HeartRateState.googleSheetUrl) != null

        return """
            Active skill: ${HeartRateState.activeSkillName} (mode: ${HeartRateState.trackingMode})
            Streak: ${HeartRateState.streakDays} days | XP: ${HeartRateState.totalXp} | Due SRS items: $dueCount
            Live HR: ${HeartRateState.currentBpm} bpm | Online: ${HeartRateState.isOnline}
            Google (Calendar + Sheets): $googleStatus | Sheet configured: $sheetConfigured
            Recent sessions: ${sessions.joinToString { "${it.skillName} (${it.durationSeconds}s)" }.ifEmpty { "none" }}
        """.trimIndent()
    }

    private fun buildPrompt(userText: String, appContext: String): String = """
        You are PulseBeat Assistant — a personal coach with Google Calendar, Google Sheets, and in-app controls.
        All external data lives in the user's Google account (one ecosystem).

        APP STATE:
        $appContext

        USER MESSAGE:
        $userText

        INSTRUCTIONS:
        - If the user wants you to DO something, respond with ONLY valid JSON (no markdown fences):
        {"message":"brief reply","actions":[{"tool":"TOOL_NAME","args":{...}}]}
        - If just chatting, respond with plain text only.
        - For journaling/logging/archiving → sheets_log_entry
        - For reading past logs → sheets_read_log
        - For scheduling → calendar_create_event or calendar_daily_practice
        - ISO8601 times with timezone for calendar events.

        TOOLS:
        - sheets_log_entry: args {title, body, type?} — append row to Google Sheet log
        - sheets_read_log: args {limit?}
        - sheets_log_session: args {} — snapshot current stats + last session to sheet
        - calendar_list_events: args {daysAhead?}
        - calendar_create_event: args {title, startIso, endIso?, description?, recurrence?}
        - calendar_daily_practice: args {title, hour, minute?}
        - app_get_stats: args {}
        - app_switch_skill: args {skillName}
        - app_navigate: args {screen} — practice|mastery|onboarding|debug|assistant
        - app_set_reminder: args {hour, minute?}
    """.trimIndent()

    private data class ParsedResponse(val message: String, val actions: List<ToolAction>)

    private fun parseAssistantResponse(raw: String): ParsedResponse {
        val trimmed = raw.trim().stripMarkdownFences()
        if (!trimmed.startsWith("{")) return ParsedResponse(trimmed, emptyList())
        return try {
            val json = JSONObject(trimmed)
            val message = json.optString("message", trimmed)
            val actionsArr = json.optJSONArray("actions") ?: JSONArray()
            val actions = (0 until actionsArr.length()).mapNotNull { i ->
                val obj = actionsArr.getJSONObject(i)
                val tool = obj.optString("tool", "")
                if (tool.isBlank()) return@mapNotNull null
                ToolAction(tool, obj.optJSONObject("args") ?: JSONObject())
            }
            ParsedResponse(message, actions)
        } catch (_: Exception) {
            ParsedResponse(trimmed, emptyList())
        }
    }

    private fun mockAssistantReply(userText: String): String {
        val lower = userText.lowercase()
        return when {
            lower.contains("journal") || lower.contains("log") || lower.contains("save") || lower.contains("sheet") ->
                """{"message":"I'll log that to your Google Sheet.","actions":[{"tool":"sheets_log_entry","args":{"title":"Practice log","body":"${userText.replace("\"", "'")}","type":"journal"}}]}"""
            lower.contains("what") && lower.contains("calendar") || lower.contains("upcoming") || lower.contains("on my calendar") ->
                """{"message":"Here's what's coming up:","actions":[{"tool":"calendar_list_events","args":{"daysAhead":7}}]}"""
            lower.contains("calendar") || lower.contains("schedule") || lower.contains("remind") ->
                """{"message":"I'll add a daily practice block to your calendar.","actions":[{"tool":"calendar_daily_practice","args":{"title":"PulseBeat Practice","hour":7}}]}"""
            lower.contains("stats") || lower.contains("streak") ->
                """{"message":"Here's your progress:","actions":[{"tool":"app_get_stats","args":{}}]}"""
            lower.contains("read") && (lower.contains("log") || lower.contains("history")) ->
                """{"message":"Recent sheet entries:","actions":[{"tool":"sheets_read_log","args":{"limit":5}}]}"""
            else ->
                "[SANDBOX] Try \"Log today to my sheet\", \"What's on my calendar?\", or \"Show my stats\"."
        }
    }

    private fun String.stripMarkdownFences(): String =
        trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

data class ToolAction(val tool: String, val args: JSONObject)

object AssistantToolExecutor {

    private suspend fun resolveSheetId(
        context: Context,
        prefs: android.content.SharedPreferences
    ): String {
        var id = HeartRateState.googleSheetUrl.ifBlank {
            prefs.getString("googleSheetUrl", "") ?: ""
        }
        if (id.isBlank() && GoogleAuthHelper.isGoogleConnected(context)) {
            id = ErrorReportService.ensureDiagnosticsSheet(context, prefs) ?: ""
            if (id.isNotBlank()) HeartRateState.googleSheetUrl = id
        }
        return id
    }

    suspend fun execute(
        context: Context,
        db: AppDatabase,
        prefs: android.content.SharedPreferences,
        action: ToolAction
    ): String {
        val token = GoogleAuthHelper.getAccessToken(context) ?: ""
        val sheetId = resolveSheetId(context, prefs)

        return when (action.tool) {
            "sheets_log_entry" -> GoogleSheetsService.appendLogEntry(
                token, sheetId,
                type = action.args.optString("type", "journal"),
                title = action.args.optString("title", "Entry"),
                detail = action.args.optString("body", action.args.optString("detail", ""))
            )
            "sheets_read_log" -> GoogleSheetsService.readRecentLog(
                token, sheetId,
                action.args.optInt("limit", 10)
            )
            "sheets_log_session" -> GoogleSheetsService.logSessionSnapshot(token, sheetId, db)
            "calendar_list_events" -> GoogleCalendarService.listUpcoming(
                token, action.args.optInt("daysAhead", 7)
            )
            "calendar_create_event" -> GoogleCalendarService.createEvent(
                token,
                action.args.optString("title", "Event"),
                action.args.optString("startIso", ""),
                action.args.optString("endIso").takeIf { it.isNotBlank() },
                action.args.optString("description", ""),
                action.args.optString("recurrence").takeIf { it.isNotBlank() }
            )
            "calendar_daily_practice" -> GoogleCalendarService.createDailyPracticeBlock(
                token,
                action.args.optString("title", "PulseBeat Practice"),
                action.args.optInt("hour", 7),
                action.args.optInt("minute", 0)
            )
            "app_get_stats" -> getAppStats(db)
            "app_switch_skill" -> switchSkill(prefs, action.args.optString("skillName", ""))
            "app_navigate" -> navigate(action.args.optString("screen", "practice"))
            "app_set_reminder" -> setReminder(context, action.args.optInt("hour", 9), action.args.optInt("minute", 0))
            else -> "Unknown tool: ${action.tool}"
        }
    }

    private suspend fun getAppStats(db: AppDatabase): String {
        val total = db.skillSessionDao().totalCount()
        val recent = db.skillSessionDao().getRecentSessions(3)
        val due = try { db.learningItemDao().countAllDue(System.currentTimeMillis()) } catch (_: Exception) { 0 }
        return buildString {
            appendLine("PulseBeat stats:")
            appendLine("• Streak: ${HeartRateState.streakDays} days")
            appendLine("• Total XP: ${HeartRateState.totalXp}")
            appendLine("• Sessions recorded: $total")
            appendLine("• SRS items due today: $due")
            appendLine("• Active skill: ${HeartRateState.activeSkillName}")
            if (recent.isNotEmpty()) {
                appendLine("• Last session: ${recent.first().skillName} (${recent.first().durationSeconds}s)")
            }
        }.trim()
    }

    private fun switchSkill(prefs: android.content.SharedPreferences, skillName: String): String {
        if (skillName.isBlank()) return "No skill name provided."
        HeartRateState.activeSkillName = skillName
        prefs.edit().putString("activeSkillName", skillName).apply()
        return "Switched active skill to $skillName"
    }

    private fun navigate(screen: String): String {
        val valid = setOf("practice", "mastery", "onboarding", "debug", "assistant", "system")
        if (screen !in valid) return "Unknown screen: $screen"
        HeartRateState.activeScreen = screen
        return "Navigated to $screen"
    }

    private fun setReminder(context: Context, hour: Int, minute: Int): String {
        SrsReminderWorker.schedule(context, hourOfDay = hour.coerceIn(0, 23))
        return "Daily reminder set for ${hour.coerceIn(0, 23)}:${minute.toString().padStart(2, '0')}"
    }
}
