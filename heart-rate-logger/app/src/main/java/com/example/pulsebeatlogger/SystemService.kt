package com.example.pulsebeatlogger

import android.content.Context
import com.example.pulsebeatlogger.data.AppDatabase
import com.example.pulsebeatlogger.data.SystemMemoryChunk
import com.example.pulsebeatlogger.data.SystemMessage
import com.example.pulsebeatlogger.data.UserGoal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The System — persistent AI coach with chunked memory bank. */
object SystemService {

    const val MAX_ACTIVE_GOALS_SOFT = 5
    private const val RECENT_TURNS_FOR_GEMINI = 8
    private const val MEMORY_RETRIEVAL_LIMIT = 18
    private val inMemoryHistory = mutableListOf<Pair<String, String>>()

    suspend fun loadFromDb(db: AppDatabase) = withContext(Dispatchers.IO) {
        val goals = db.userGoalDao().getActiveGoals()
        backfillMemoryFromMessagesIfNeeded(db)
        val chunks = db.systemMemoryChunkDao().getAllNewestFirst()
        val chatCount = db.systemMessageDao().count()

        withContext(Dispatchers.Main) {
            HeartRateState.systemGoals.clear()
            HeartRateState.systemGoals.addAll(goals)
            HeartRateState.systemMemoryBank.clear()
            HeartRateState.systemMemoryBank.addAll(chunks.map { it.toUiItem() })
            HeartRateState.systemTotalChatCount = chatCount
        }

        refreshChatLog(db)
        rebuildGeminiHistory(db)
    }

    private suspend fun refreshChatLog(db: AppDatabase) {
        val msgs = db.systemMessageDao().getAllOrdered()
            .filter { it.role == "user" || it.role == "system" }
        withContext(Dispatchers.Main) {
            HeartRateState.systemChatLog.clear()
            HeartRateState.systemChatLog.addAll(
                msgs.map { SystemChatLine(it.role, it.text, it.timestamp) }
            )
        }
    }

    suspend fun clearMemory(db: AppDatabase, prefs: android.content.SharedPreferences? = null) = withContext(Dispatchers.IO) {
        db.systemMemoryChunkDao().clearAll()
        db.systemMessageDao().clearAll()
        withContext(Dispatchers.Main) {
            HeartRateState.systemMemoryBank.clear()
            HeartRateState.systemChatLog.clear()
            HeartRateState.systemTotalChatCount = 0
        }
        inMemoryHistory.clear()
        prefs?.let { DriveSyncService.markDirty(it) }
    }

    suspend fun sendMessage(
        context: Context,
        db: AppDatabase,
        prefs: android.content.SharedPreferences,
        userText: String
    ): SystemMemoryItem = withContext(Dispatchers.IO) {
        persistMessage(db, "user", userText)
        inMemoryHistory.add("user" to userText)

        val apiKey = HeartRateState.geminiApiKey
        if (apiKey.isBlank()) {
            val reply = "Set your Gemini API key in Train → Settings. Use \"debug\" for offline sandbox."
            persistMessage(db, "system", reply)
            return@withContext storeMemoryChunk(db, userText, reply, emptyList(), "insight")
        }

        val memoryBank = retrieveMemoryBank(db, userText)
        val appContext = buildSystemContext(context, db)
        val prompt = buildPrompt(userText, appContext, memoryBank)
        HeartRateState.log("System: sending (${userText.take(50)}), memory=${memoryBank.lines().size} chunks")

        val rawReply = if (apiKey.trim().lowercase() == "debug") {
            mockSystemReply(userText, db)
        } else {
            GeminiService.assistantChat(
                apiKey,
                prompt,
                inMemoryHistory.dropLast(1).takeLast(RECENT_TURNS_FOR_GEMINI * 2)
            )
        }

        val parsed = parseResponse(rawReply)
        val toolResults = mutableListOf<String>()

        for (action in parsed.actions) {
            val result = SystemToolExecutor.execute(context, db, prefs, action)
            toolResults.add("✓ ${action.tool}: $result")
            persistMessage(db, "action", result)
        }

        val finalText = buildString {
            append(parsed.message.ifBlank { rawReply })
            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine()
                toolResults.forEach { appendLine(it) }
            }
        }.trim()

        inMemoryHistory.add("assistant" to finalText)
        persistMessage(db, "system", finalText)
        DriveSyncService.markDirty(prefs)
        storeMemoryChunk(db, userText, finalText, toolResults, categoryForTools(toolResults))
    }

    private suspend fun persistMessage(db: AppDatabase, role: String, text: String) {
        db.systemMessageDao().insert(SystemMessage(role = role, text = text))
        withContext(Dispatchers.Main) {
            HeartRateState.systemTotalChatCount = db.systemMessageDao().count()
        }
        if (role == "user" || role == "system") {
            refreshChatLog(db)
        }
    }

    private suspend fun storeMemoryChunk(
        db: AppDatabase,
        userText: String,
        systemReply: String,
        toolResults: List<String>,
        category: String
    ): SystemMemoryItem {
        val content = buildChunkSummary(userText, systemReply, toolResults)
        val chunk = SystemMemoryChunk(
            id = UUID.randomUUID().toString(),
            content = content,
            category = category,
            keywords = extractKeywords(userText, systemReply, toolResults),
            userSnippet = userText.take(280),
            systemSnippet = systemReply.take(400)
        )
        db.systemMemoryChunkDao().upsert(chunk)
        val item = chunk.toUiItem()
        withContext(Dispatchers.Main) {
            HeartRateState.systemMemoryBank.add(0, item)
        }
        return item
    }

    /** Pull relevant memory chunks — always consulted before every response. */
    private suspend fun retrieveMemoryBank(db: AppDatabase, userText: String): String {
        val all = db.systemMemoryChunkDao().getRecent(250)
        if (all.isEmpty()) return "(empty — first conversation)"

        val queryWords = tokenize(userText)
        val scored = all.map { chunk ->
            val haystack = "${chunk.keywords} ${chunk.content} ${chunk.userSnippet}".lowercase()
            val score = queryWords.sumOf { w -> if (haystack.contains(w)) 2 else 0 } +
                when (chunk.category) {
                    "goal" -> 1
                    "preference" -> 1
                    else -> 0
                }
            chunk to score
        }

        val relevant = scored
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MEMORY_RETRIEVAL_LIMIT)
            .map { it.first }

        val recent = all.take(6)
        val merged = (relevant + recent).distinctBy { it.id }.take(MEMORY_RETRIEVAL_LIMIT)

        return buildString {
            appendLine("Total stored memories: ${all.size} | Full chat archive: ${db.systemMessageDao().count()} messages")
            merged.forEach { c ->
                appendLine("• [${c.category}] ${c.content}")
            }
        }.trim()
    }

    private suspend fun buildSystemContext(context: Context, db: AppDatabase): String {
        val goals = db.userGoalDao().getActiveGoals()
        withContext(Dispatchers.Main) {
            HeartRateState.systemGoals.clear()
            HeartRateState.systemGoals.addAll(goals)
        }

        val activeCount = goals.size
        val overload = activeCount >= MAX_ACTIVE_GOALS_SOFT
        val goalLines = goals.take(8).joinToString("\n") { g ->
            "  • id=${g.id} | ${g.title} (${g.priority})${if (g.description.isNotBlank()) " — ${g.description.take(60)}" else ""}"
        }.ifBlank { "  (none yet)" }

        val sessions = db.skillSessionDao().getRecentSessions(3)
        val googleStatus = when {
            HeartRateState.calendarSandboxMode -> "sandbox"
            GoogleAuthHelper.isGoogleConnected(context) -> "connected"
            else -> "not connected"
        }

        return """
            ACTIVE GOALS ($activeCount${if (overload) " — OVERLOAD: suggest consolidating or pausing some" else ""}):
            $goalLines

            App: skill=${HeartRateState.activeSkillName} streak=${HeartRateState.streakDays} xp=${HeartRateState.totalXp}
            Google: $googleStatus | Sheet: ${if (HeartRateState.googleSheetUrl.isNotBlank()) "configured" else "not set"}
            Recent sessions: ${sessions.joinToString { it.skillName }.ifEmpty { "none" }}
        """.trimIndent()
    }

    private fun buildPrompt(userText: String, ctx: String, memoryBank: String): String = """
        You are THE SYSTEM — the user's personal operating system and long-term coach inside PulseBeat.
        You have a persistent MEMORY BANK of everything you've learned about them. ALWAYS read it before replying.
        Reference past memories naturally when relevant. Never claim you forgot something that's in memory.

        MEMORY BANK (retrieved for this message — full archive kept on device):
        $memoryBank

        CURRENT STATE:
        $ctx

        USER:
        $userText

        When you need to ACT (add/update goals, log to sheet, schedule, etc.), respond with ONLY JSON:
        {"message":"your reply","actions":[{"tool":"TOOL","args":{}}]}
        Otherwise plain text only.

        GOAL TOOLS:
        - goal_add: {title, description?, priority?} — priority: low|medium|high
        - goal_list: {}
        - goal_pause: {id}
        - goal_complete: {id}
        - goal_update: {id, title?, description?, priority?}

        OTHER TOOLS (same Google + app stack):
        - sheets_log_entry, sheets_read_log, calendar_list_events, calendar_create_event, calendar_daily_practice
        - app_get_stats, app_set_reminder, app_switch_skill, app_navigate
    """.trimIndent()

    private suspend fun backfillMemoryFromMessagesIfNeeded(db: AppDatabase) {
        if (db.systemMemoryChunkDao().count() > 0) return
        val msgs = db.systemMessageDao().getAllOrdered()
        if (msgs.isEmpty()) return

        var pendingUser: String? = null
        for (msg in msgs) {
            when (msg.role) {
                "user" -> pendingUser = msg.text
                "system" -> {
                    if (pendingUser != null) {
                        val chunk = SystemMemoryChunk(
                            id = UUID.randomUUID().toString(),
                            content = buildChunkSummary(pendingUser, msg.text, emptyList()),
                            category = "exchange",
                            keywords = extractKeywords(pendingUser, msg.text, emptyList()),
                            userSnippet = pendingUser.take(280),
                            systemSnippet = msg.text.take(400),
                            createdAt = msg.timestamp
                        )
                        db.systemMemoryChunkDao().upsert(chunk)
                        pendingUser = null
                    }
                }
            }
        }
        HeartRateState.log("System: backfilled ${db.systemMemoryChunkDao().count()} memory chunks from chat archive")
    }

    private suspend fun rebuildGeminiHistory(db: AppDatabase) {
        inMemoryHistory.clear()
        val msgs = db.systemMessageDao().getRecent(RECENT_TURNS_FOR_GEMINI * 3).asReversed()
        msgs.filter { it.role == "user" || it.role == "system" }.forEach {
            val role = if (it.role == "system") "assistant" else "user"
            inMemoryHistory.add(role to it.text)
        }
    }

    private fun buildChunkSummary(userText: String, systemReply: String, toolResults: List<String>): String {
        val toolHint = toolResults.firstOrNull()?.substringAfter(": ")?.take(80)
        return when {
            toolHint != null -> "$toolHint — user said: \"${userText.take(80)}\""
            systemReply.length <= 120 -> "User: \"${userText.take(60)}\" → $systemReply"
            else -> "User: \"${userText.take(80)}\" → ${systemReply.take(100).replace("\n", " ")}…"
        }
    }

    private fun categoryForTools(toolResults: List<String>): String = when {
        toolResults.any { it.contains("goal_") } -> "goal"
        toolResults.any { it.contains("sheets_") } -> "log"
        toolResults.any { it.contains("calendar_") } -> "log"
        else -> "exchange"
    }

    private fun extractKeywords(userText: String, systemReply: String, toolResults: List<String>): String {
        val raw = "$userText $systemReply ${toolResults.joinToString(" ")}"
        return tokenize(raw).distinct().take(24).joinToString(" ")
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .filter { it !in STOP_WORDS }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "you", "your", "that", "this", "with", "have", "are", "was", "what",
        "how", "can", "will", "from", "about", "just", "not", "but", "all", "any", "say", "said"
    )

    private data class Parsed(val message: String, val actions: List<ToolAction>)

    private fun parseResponse(raw: String): Parsed {
        val trimmed = raw.trim().stripFences()
        if (!trimmed.startsWith("{")) return Parsed(trimmed, emptyList())
        return try {
            val json = JSONObject(trimmed)
            val message = json.optString("message", trimmed)
            val arr = json.optJSONArray("actions") ?: JSONArray()
            val actions = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val tool = o.optString("tool", "")
                if (tool.isBlank()) null else ToolAction(tool, o.optJSONObject("args") ?: JSONObject())
            }
            Parsed(message, actions)
        } catch (_: Exception) {
            Parsed(trimmed, emptyList())
        }
    }

    private suspend fun mockSystemReply(userText: String, db: AppDatabase): String {
        val lower = userText.lowercase()
        val active = db.userGoalDao().countActive()
        return when {
            (lower.contains("goal") && (lower.contains("new") || lower.contains("want to") || lower.contains("learn") || lower.contains("start"))) ||
                ((lower.contains("want to") || lower.contains("learn") || lower.contains("start")) &&
                    !lower.contains("my goals") && !lower.contains("list goal")) ->
                """{"message":"I'll track that as a new goal for you.${if (active >= MAX_ACTIVE_GOALS_SOFT) " Heads up — you already have $active active goals. Consider pausing one first." else ""}","actions":[{"tool":"goal_add","args":{"title":"${userText.take(60).replace("\"", "'")}","description":"Added via The System"}}]}"""
            lower.contains("my goals") || lower.contains("list goal") || lower.contains("memory") ->
                """{"message":"Here are your active goals:","actions":[{"tool":"goal_list","args":{}}]}"""
            lower.contains("log") || lower.contains("journal") ->
                """{"message":"Logging to your Google Sheet.","actions":[{"tool":"sheets_log_entry","args":{"title":"System note","body":"${userText.replace("\"", "'")}","type":"system"}}]}"""
            lower.contains("calendar") || lower.contains("schedule") ->
                """{"message":"Checking your calendar.","actions":[{"tool":"calendar_list_events","args":{"daysAhead":7}}]}"""
            lower.contains("stats") || lower.contains("progress") ->
                """{"message":"Here's where you stand:","actions":[{"tool":"app_get_stats","args":{}}]}"""
            else ->
                "[SYSTEM SANDBOX] Memory bank active. Tell me a new goal, ask \"what are my goals\", or say \"log today to my sheet\". You have $active active goal(s)."
        }
    }

    private fun SystemMemoryChunk.toUiItem() = SystemMemoryItem(
        id = id,
        content = content,
        category = category,
        userSnippet = userSnippet,
        systemSnippet = systemSnippet,
        createdAt = createdAt
    )

    private fun String.stripFences() =
        trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

data class SystemMemoryItem(
    val id: String,
    val content: String,
    val category: String,
    val userSnippet: String = "",
    val systemSnippet: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** One line in The System chat thread (oldest → newest). */
data class SystemChatLine(
    val role: String,
    val text: String,
    val timestamp: Long
)

object SystemToolExecutor {

    suspend fun execute(
        context: Context,
        db: AppDatabase,
        prefs: android.content.SharedPreferences,
        action: ToolAction
    ): String {
        if (action.tool.startsWith("goal_")) {
            return executeGoal(db, action)
        }
        return AssistantToolExecutor.execute(context, db, prefs, action)
    }

    private suspend fun executeGoal(db: AppDatabase, action: ToolAction): String {
        return when (action.tool) {
            "goal_add" -> {
                val active = db.userGoalDao().countActive()
                if (active >= SystemService.MAX_ACTIVE_GOALS_SOFT) {
                    return "Warning: you already have $active active goals. Added anyway — consider pausing one. " +
                        addGoal(db, action)
                }
                addGoal(db, action)
            }
            "goal_list" -> {
                val goals = db.userGoalDao().getActiveGoals()
                refreshGoals(db)
                if (goals.isEmpty()) return "No active goals yet. Tell me what you want to work toward."
                buildString {
                    appendLine("Active goals (${goals.size}):")
                    goals.forEachIndexed { i, g ->
                        appendLine("${i + 1}. ${g.title} [${g.priority}] id=${g.id}")
                        if (g.description.isNotBlank()) appendLine("   ${g.description}")
                    }
                }.trim()
            }
            "goal_pause" -> updateGoalStatus(db, action.args.optString("id"), "paused")
            "goal_complete" -> updateGoalStatus(db, action.args.optString("id"), "completed")
            "goal_update" -> {
                val idArg = action.args.optString("id")
                val existing = resolveGoal(db, idArg) ?: return "Goal not found: $idArg"
                val title = action.args.optString("title", existing.title)
                val desc = action.args.optString("description", existing.description)
                val pri = action.args.optString("priority", existing.priority)
                db.userGoalDao().updateDetails(existing.id, title, desc, pri)
                refreshGoals(db)
                "Updated goal: $title"
            }
            else -> "Unknown goal tool: ${action.tool}"
        }
    }

    private suspend fun addGoal(db: AppDatabase, action: ToolAction): String {
        val title = action.args.optString("title", "").ifBlank { return "Goal needs a title." }
        val goal = UserGoal(
            id = UUID.randomUUID().toString(),
            title = title,
            description = action.args.optString("description", ""),
            priority = action.args.optString("priority", "medium"),
            status = "active"
        )
        db.userGoalDao().upsert(goal)
        refreshGoals(db)
        return "Goal tracked: $title"
    }

    private suspend fun updateGoalStatus(db: AppDatabase, idOrPrefix: String, status: String): String {
        if (idOrPrefix.isBlank()) return "Goal id required."
        val g = resolveGoal(db, idOrPrefix) ?: return "Goal not found."
        db.userGoalDao().updateStatus(g.id, status)
        refreshGoals(db)
        return "${g.title} → $status"
    }

    private suspend fun resolveGoal(db: AppDatabase, idOrPrefix: String): UserGoal? {
        db.userGoalDao().getById(idOrPrefix)?.let { return it }
        if (idOrPrefix.length >= 8) {
            db.userGoalDao().getByIdPrefix(idOrPrefix)?.let { return it }
        }
        return null
    }

    private suspend fun refreshGoals(db: AppDatabase) {
        val goals = db.userGoalDao().getActiveGoals()
        withContext(Dispatchers.Main) {
            HeartRateState.systemGoals.clear()
            HeartRateState.systemGoals.addAll(goals)
        }
    }
}
