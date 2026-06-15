package com.example.pulsebeatlogger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin wrapper around the Gemini REST API.
 * All calls are suspend functions — safe to call from any coroutine scope.
 * Uses plain HttpURLConnection so no extra SDK dependency is required.
 *
 * Model pool: requests round-robin across all available free-tier text models.
 * A model that returns 429 is cooled down for 60 s before being retried.
 * A model that returns 404 is permanently removed from the pool for this session.
 */
object GeminiService {

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    /** Returned instead of throwing when the device is offline. UI checks for this string. */
    const val OFFLINE_PLACEHOLDER = "__OFFLINE__"

    /**
     * All text-generation models with non-zero free-tier RPD, ordered by preference.
     * Best quality first; highest RPD (Gemma 4) last as the safety net.
     */
    private val MODEL_POOL = listOf(
        "gemini-2.5-flash",         // 5 RPM, 20 RPD  — best quality
        "gemini-3.5-flash",         // 5 RPM, 20 RPD  — latest
        "gemini-3-flash",           // 5 RPM, 20 RPD
        "gemini-2.5-flash-lite",    // 10 RPM, 20 RPD — faster
        "gemini-3.1-flash-lite",    // 15 RPM, 500 RPD — most generous flash
        "gemma-4-26b-it",           // 15 RPM, 1500 RPD — highest daily quota
        "gemma-4-31b-it"            // 15 RPM, 1500 RPD — highest daily quota
    )

    // model → System.currentTimeMillis() when cooldown expires (0 = available)
    private val cooldownUntil = mutableMapOf<String, Long>()
    // models permanently removed (returned 404 = not a valid endpoint)
    private val removedModels = mutableSetOf<String>()

    private fun availableModels(): List<String> =
        MODEL_POOL.filter { it !in removedModels && (cooldownUntil[it] ?: 0L) < System.currentTimeMillis() }

    private fun coolDown(model: String, ms: Long = 60_000L) {
        cooldownUntil[model] = System.currentTimeMillis() + ms
        HeartRateState.log("⏳ Model $model cooled down for ${ms / 1000}s — trying next in pool")
    }

    // In-memory conversation history for chat continuity within a session
    private val conversationHistory = mutableListOf<JSONObject>()

    fun clearHistory() = conversationHistory.clear()

    /** Returns a human-readable status line for each model in the pool. */
    fun poolStatus(): String {
        val now = System.currentTimeMillis()
        return MODEL_POOL.joinToString("\n") { model ->
            when {
                model in removedModels -> "❌ $model (removed — 404)"
                (cooldownUntil[model] ?: 0L) > now -> {
                    val secsLeft = ((cooldownUntil[model]!! - now) / 1000).toInt()
                    "⏳ $model (cooldown ${secsLeft}s)"
                }
                else -> "✅ $model"
            }
        }
    }

    // ── Debug / Offline Mock Mode ─────────────────────────────────────────────
    // Set apiKey to "debug" in Settings to get instant offline responses.
    // All real HTTP calls are bypassed — safe on emulators with no internet.

    private fun isDebugKey(apiKey: String) = apiKey.trim().lowercase() == "debug"

    /**
     * Generate an initial skill profile and seed curriculum for a new skill.
     * Returns a JSON string containing:
     *   { "welcome": "...", "items": [ { "category": "...", "content": "...", "contentJson": "...", "tags": [...] } ] }
     */
    suspend fun generateProfile(apiKey: String, skillName: String, userContext: String = ""): String {
        if (isDebugKey(apiKey)) {
            HeartRateState.log("🔬 Gemini [MOCK] generateProfile for: $skillName")
            return mockProfileJson(skillName)
        }
        val typeHint = skillTypeHint(skillName)
        val prompt = buildString {
            append("You are an expert adaptive learning coach. A user wants to learn: \"$skillName\".")
            if (userContext.isNotEmpty()) append(" User context: $userContext.")
            append("\n\n")
            append(typeHint)
            append("""

Return a JSON object — raw JSON only, NO markdown fences — with exactly these keys:
- "welcome": a 2-sentence motivating intro specific to $skillName (string)
- "items": a JSON array of exactly 10 learning items, each with:
  - "category": a category appropriate for $skillName (see guidance above)
  - "content": a SHORT, SPECIFIC, ACTIONABLE item title (not generic — name real techniques/exercises/words)
  - "contentJson": a JSON object with fields: {"hint":"...","example":"...","tip":"..."}
    where hint explains the concept, example shows a concrete instance, tip gives a memorable trick
  - "tags": JSON array of 1-3 relevant short tag strings

IMPORTANT: Every item must be SPECIFIC to $skillName. Do NOT use generic or unrelated categories.
Keep items varied across categories and difficulty levels (beginner to intermediate).
""")
        }
        val raw = callGemini(apiKey, prompt, history = emptyList(), maxTokens = 4096, temperature = 0.3).stripMarkdownFences()
        return raw.ifBlank { fallbackProfileJson(skillName) }
    }

    /**
     * Regenerates a single learning item with a simpler explanation.
     * Called when the user has rated an item "Again" 3+ consecutive times.
     * Returns a Pair(newContent, newContentJson) or null on failure.
     */
    suspend fun simplifyItem(
        apiKey: String,
        skillName: String,
        item: com.example.pulsebeatlogger.data.LearningItem
    ): Pair<String, String>? {
        if (isDebugKey(apiKey)) {
            return Pair(
                "${item.content} (simplified)",
                """{"hint":"Let's break this down more simply.","example":"${item.content}","tip":"Take it one step at a time."}"""
            )
        }
        val prompt = """
You are an adaptive learning coach for "$skillName".
A student has struggled with this item 3 times in a row:

Title: ${item.content}
Current explanation: ${item.contentJson}

Rewrite this as a SIMPLER version that a complete beginner can grasp. Keep the same topic but:
- Use much simpler language
- Break it into smaller steps
- Give a very concrete example

Return a JSON object ONLY (no markdown):
{
  "content": "shorter, simpler item title",
  "contentJson": {"hint":"simpler explanation","example":"very basic concrete example","tip":"one easy memory trick"}
}
""".trimIndent()

        return try {
            val raw = callGemini(apiKey, prompt, history = emptyList(), maxTokens = 512, temperature = 0.4).stripMarkdownFences()
            if (raw == OFFLINE_PLACEHOLDER || raw.isBlank()) return null
            val obj = org.json.JSONObject(raw)
            val content = obj.optString("content").ifBlank { return null }
            val contentJson = obj.optJSONObject("contentJson")?.toString() ?: return null
            Pair(content, contentJson)
        } catch (e: Exception) {
            HeartRateState.log("simplifyItem failed: ${e.message}")
            null
        }
    }

    /** Returns skill-type-specific prompt guidance so Gemini generates appropriate categories and content. */
    private fun skillTypeHint(skillName: String): String {
        val s = skillName.lowercase()
        return when {
            listOf("python","javascript","typescript","kotlin","java","swift","go","rust",
                "c++","c#","ruby","php","scala","dart","flutter","react","html","css",
                "sql","coding","programming","code","algorithm","data structure").any { s.contains(it) } ->
                """SKILL TYPE: Programming / Coding
Categories to use: "syntax", "concept", "exercise", "debugging", "best_practice"
Content format: each item should be a specific coding concept or mini-challenge.
  example content: "Write a function that reverses a string", "Explain what a list comprehension does"
  hint: explain the concept, example: show the actual code snippet, tip: common mistake to avoid"""

            listOf("japanese","mandarin","chinese","spanish","french","german","italian",
                "korean","portuguese","arabic","hindi","russian","latin","sign language",
                "language","vocabulary","grammar","pronunciation","translation").any { s.contains(it) } ->
                """SKILL TYPE: Language Learning
Categories to use: "vocabulary", "grammar", "pronunciation", "writing", "listening"
Content format: specific words, grammar points, or pronunciation rules from the language.
  example content: "ありがとう (arigatou) - Thank you", "ser vs estar distinction in Spanish"
  hint: explain meaning/usage, example: show in a real sentence, tip: memory trick or common error"""

            listOf("math","algebra","calculus","geometry","trigonometry","statistics",
                "probability","physics","chemistry","logic","engineering","arithmetic").any { s.contains(it) } ->
                """SKILL TYPE: Mathematics / Science
Categories to use: "concept", "formula", "problem", "proof", "application"
Content format: specific theorems, formulas, or problem types.
  example content: "The quadratic formula", "Solve dx/dt = 3x²"
  hint: explain what it means, example: show the worked formula/equation, tip: when to apply it"""

            listOf("running","yoga","pushup","squat","deadlift","workout","gym","cycling",
                "swimming","weightlifting","hiit","cardio","stretching","pilates","crossfit",
                "boxing","martial arts","karate","fitness","exercise","sport","training").any { s.contains(it) } ->
                """SKILL TYPE: Physical Training / Sport
Categories to use: "form", "warmup", "exercise", "technique", "cooldown"
Content format: specific exercises with reps/sets guidance and form cues.
  example content: "Push-up: perfect form (3 sets × 10 reps)", "Hip hinge for deadlift setup"
  hint: describe the movement, example: give exact reps/sets or distance/time, tip: most common form mistake"""

            listOf("piano","guitar","violin","ukulele","bass","drums","cello","flute","trumpet",
                "singing","vocal","choir","music theory","chord","rhythm","beat",
                "music","instrument","saxophone","harmonica","banjo","mandolin").any { s.contains(it) } ->
                """SKILL TYPE: Music / Instrument
Categories to use: "technique", "music_theory", "ear_training", "practice_piece", "rhythm"
Content format: specific techniques, scales, chords, or short pieces to practice.
  example content: "C major scale - right hand, 2 octaves", "G major chord voicing on guitar"
  hint: describe finger position or theory, example: name the specific notes or tab, tip: common beginner mistake"""

            listOf("draw","sketch","paint","watercolor","oil paint","acrylic","illustration",
                "calligraphy","lettering","manga","anime","portrait","figure drawing",
                "art","doodle","comic","digital art","procreate","charcoal","pencil shading").any { s.contains(it) } ->
                """SKILL TYPE: Drawing / Visual Art
Categories to use: "line_work", "shading", "composition", "form", "style_study"
Content format: specific exercises with a clear reference subject to draw.
  example content: "Draw 10 quick gesture lines — straight, then curved", "Shade a sphere using hatching"
  hint: explain the technique, example: give the exact subject/reference to draw, tip: most common beginner mistake"""

            else ->
                """SKILL TYPE: General / Custom
Categories to use: choose 3-5 categories that make the most sense for "$skillName"
Content format: specific, actionable tasks or concepts relevant to "$skillName"
  hint: explain why this matters, example: show a real-world use, tip: efficiency or memory trick"""
        }
    }

    /** Single-prompt overload — sends the prompt verbatim, no system context prepended. */
    suspend fun chat(apiKey: String, prompt: String): String {
        if (isDebugKey(apiKey)) return "[DEBUG] Code looks good! Great job with the structure."
        return callGemini(apiKey, prompt, history = emptyList())
    }

    /**
     * Assistant chat — single-turn with optional prior history (separate from coach chat history).
     */
    suspend fun assistantChat(
        apiKey: String,
        prompt: String,
        priorTurns: List<Pair<String, String>> = emptyList()
    ): String {
        if (isDebugKey(apiKey)) return ""
        val history = mutableListOf<JSONObject>()
        for ((role, text) in priorTurns) {
            val geminiRole = if (role == "assistant") "model" else "user"
            history.add(JSONObject().apply {
                put("role", geminiRole)
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }
        history.add(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        })
        return callGemini(apiKey, null, history = history, maxTokens = 2048, temperature = 0.4)
    }

    /**
     * Send a user message in the ongoing coaching chat.
     * Maintains conversation history for context continuity.
     */
    suspend fun chat(apiKey: String, userMessage: String, skillName: String, sessionContext: String = ""): String {
        if (isDebugKey(apiKey)) {
            HeartRateState.log("🔬 Gemini [MOCK] chat: \"$userMessage\"")
            return mockChatReply(userMessage, skillName)
        }
        val systemContext = buildString {
            append("You are an adaptive AI learning coach helping a user practice \"$skillName\".")
            if (sessionContext.isNotEmpty()) append(" Current session: $sessionContext.")
            append(" Be encouraging, concise (2-3 sentences), and practical. Adjust difficulty hints based on user feedback.")
        }

        val userTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
        }
        conversationHistory.add(userTurn)

        val historyWithSystem = mutableListOf<JSONObject>().apply {
            add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", systemContext)))
            })
            add(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", "Understood. I'm your coaching assistant for $skillName. Let's go!")))
            })
            addAll(conversationHistory)
        }

        val reply = callGemini(apiKey, null, history = historyWithSystem)
        if (reply.isNotBlank()) {
            conversationHistory.add(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", reply)))
            })
        }
        return reply.ifBlank { "Keep going! I'm tracking your progress." }
    }

    /**
     * Generate end-of-session feedback.
     * Returns a plain text string (2-3 sentences).
     */
    suspend fun generateFeedback(
        apiKey: String,
        skillName: String,
        durationMin: Int,
        accuracyPct: Float,
        weakCategories: List<String>
    ): String {
        if (isDebugKey(apiKey)) {
            HeartRateState.log("🔬 Gemini [MOCK] feedback: ${durationMin}min, ${accuracyPct.toInt()}%")
            return "Great debug session! You practiced for $durationMin minutes with ${accuracyPct.toInt()}% accuracy. " +
                "Keep working on ${weakCategories.firstOrNull() ?: "all areas"} — you're making solid progress."
        }
        val weak = if (weakCategories.isEmpty()) "none identified" else weakCategories.joinToString(", ")
        val prompt = """
            You are an expert coach for "$skillName". Generate end-of-session feedback (2-3 sentences, plain text, no markdown).
            Session: ${durationMin} minutes, accuracy: ${accuracyPct.toInt()}%, weak areas: $weak.
            Be specific, encouraging, and give one actionable tip for the next session.
        """.trimIndent()
        val result = callGemini(apiKey, prompt, history = emptyList())
        return result.ifBlank { "Good session! Focus on your weak areas next time and keep building consistency." }
    }

    /**
     * Generate weekly review summary.
     * Returns a plain text paragraph.
     */
    suspend fun generateWeeklyReview(
        apiKey: String,
        skillName: String,
        sessionsThisWeek: Int,
        avgAccuracy: Float,
        topWeakCategory: String
    ): String {
        if (isDebugKey(apiKey)) {
            HeartRateState.log("🔬 Gemini [MOCK] weeklyReview: $sessionsThisWeek sessions")
            return "Debug weekly review — you completed $sessionsThisWeek sessions this week with an average accuracy of ${avgAccuracy.toInt()}%. " +
                "Your main challenge was $topWeakCategory. Next week, try to do one extra session focused entirely on that area. " +
                "Overall trend is positive — keep the momentum going!"
        }
        val prompt = """
            Generate a weekly practice review (3-4 sentences, plain text) for a student learning "$skillName".
            This week: $sessionsThisWeek sessions, average accuracy: ${avgAccuracy.toInt()}%, most challenging area: "$topWeakCategory".
            Highlight progress, name the weak area specifically, and suggest a concrete focus for next week.
        """.trimIndent()
        val result = callGemini(apiKey, prompt, history = emptyList())
        return result.ifBlank { "Great week! Keep focusing on $topWeakCategory to accelerate your progress." }
    }

    // ── Core HTTP layer ───────────────────────────────────────────────────────

    /**
     * Try every available model in pool order until one succeeds.
     * 429 → cool that model down 60 s, try next immediately.
     * 404 → permanently remove model from pool, try next.
     * All others → log and try next.
     */
    private suspend fun callGemini(
        apiKey: String,
        singlePrompt: String?,
        history: List<JSONObject>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7
    ): String = withContext(Dispatchers.IO) {
        // Fail fast with a clear offline message — prevents confusing network errors in the UI
        if (!HeartRateState.isOnline) {
            HeartRateState.log("Gemini: skipped — device is offline")
            return@withContext OFFLINE_PLACEHOLDER
        }

        val promptPreview = singlePrompt?.take(80) ?: "chat (${history.size} turns)"

        val contents: JSONArray = if (singlePrompt != null) {
            JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", singlePrompt)))
                }
            )
        } else {
            JSONArray().apply { history.forEach { put(it) } }
        }

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", temperature)
                // Disable thinking mode for structured/fast calls — thinking tokens
                // count against maxOutputTokens in gemini-2.5-flash and can starve
                // the actual response when maxOutputTokens is low.
                put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
            })
        }.toString()

        val pool = availableModels()
        if (pool.isEmpty()) {
            HeartRateState.log("Gemini: ❌ All models are on cooldown or removed — no request sent")
            return@withContext ""
        }

        HeartRateState.log("Gemini: pool=${pool.size} available [${pool.joinToString()}]")

        for (model in pool) {
            HeartRateState.log("Gemini: → $model | prompt='$promptPreview'")
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$API_BASE/$model:generateContent?key=$apiKey")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { os: OutputStream ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_OK -> {
                        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                        val json = JSONObject(response)
                        val text = json
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                            .trim()
                        HeartRateState.log("Gemini: ✅ $model — ${text.length} chars '${text.take(60)}'")
                        return@withContext text
                    }
                    code == 429 -> {
                        val err = try { connection.errorStream?.bufferedReader()?.readText()?.take(80) ?: "" } catch (e: Exception) { "" }
                        HeartRateState.log("Gemini: 429 $model — cooling 60s ($err)")
                        coolDown(model, 60_000L)
                    }
                    code == 404 -> {
                        HeartRateState.log("Gemini: 404 $model — removing from pool permanently")
                        removedModels.add(model)
                    }
                    else -> {
                        val err = try { connection.errorStream?.bufferedReader()?.readText()?.take(100) ?: "" } catch (e: Exception) { "" }
                        HeartRateState.log("Gemini: ❌ HTTP $code $model — $err")
                    }
                }
            } catch (e: Exception) {
                HeartRateState.logError("Gemini $model call failed", e)
            } finally {
                connection?.disconnect()
            }
        }

        HeartRateState.log("Gemini: ❌ All pool models exhausted for this request")
        ""
    }

    // ── Mock helpers ──────────────────────────────────────────────────────────

    private fun mockProfileJson(skillName: String): String {
        val items = JSONArray()
        val mockItems = listOf(
            Triple("vocabulary", "[DEBUG] $skillName — Core Concept 1", "core"),
            Triple("pronunciation", "[DEBUG] Pronunciation Basics", "pronunciation"),
            Triple("vocabulary", "[DEBUG] Essential Vocabulary Set A", "vocabulary"),
            Triple("grammar", "[DEBUG] Sentence Structure Rule 1", "grammar"),
            Triple("listening", "[DEBUG] Audio Recognition Exercise", "listening"),
            Triple("vocabulary", "[DEBUG] Intermediate Vocabulary Set B", "vocabulary"),
            Triple("pronunciation", "[DEBUG] Intonation Patterns", "intonation"),
            Triple("grammar", "[DEBUG] Question Formation", "grammar"),
            Triple("custom", "[DEBUG] Cultural Context Note", "culture"),
            Triple("vocabulary", "[DEBUG] Review: All Core Terms", "review")
        )
        mockItems.forEach { (cat, content, tag) ->
            items.put(JSONObject().apply {
                put("category", cat)
                put("content", content)
                put("contentJson", JSONObject().apply {
                    put("hint", "Debug hint: focus on the core pattern here")
                    put("example", "Example usage in context: '$content in a sentence'")
                    put("tip", "Mock tip: repeat 3 times then test yourself")
                }.toString())
                put("tags", JSONArray().put(tag).put("debug"))
            })
        }
        return JSONObject().apply {
            put("welcome", "[DEBUG MODE] Welcome to your $skillName practice sandbox! This curriculum was generated offline — swap in a real Gemini key to get a personalised one.")
            put("items", items)
        }.toString()
    }

    private fun mockChatReply(userMessage: String, skillName: String): String {
        val lower = userMessage.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") ->
                "[DEBUG] Hey! Ready to practice $skillName. What do you want to work on today?"
            lower.contains("help") ->
                "[DEBUG] Sure! For $skillName, I'd suggest starting with the vocabulary items and then moving to pronunciation drills. What area feels hardest for you?"
            lower.contains("hard") || lower.contains("difficult") ->
                "[DEBUG] Totally normal to find this challenging! Break it into smaller pieces — try just 2 items at a time and rate honestly. The SRS system will keep bringing back the tough ones."
            lower.contains("good") || lower.contains("great") || lower.contains("easy") ->
                "[DEBUG] Excellent! If things feel easy, try rating items 'Good' or 'Easy' so the algorithm spaces them out further and introduces harder material."
            lower.contains("next") ->
                "[DEBUG] Next up: look at the due items on the Practice screen. The SRS algorithm surfaces whichever items need the most attention today."
            else ->
                "[DEBUG] Got it: \"$userMessage\". In a real session this would go to Gemini. For now, keep practising the due items on the Practice screen!"
        }
    }

    // Strips ```json ... ``` or ``` ... ``` wrappers that Gemini sometimes adds
    private fun String.stripMarkdownFences(): String {
        val trimmed = this.trim()
        val noFence = trimmed
            .removePrefix("```json").removePrefix("```JSON")
            .removePrefix("```").removeSuffix("```").trim()
        return noFence
    }

    private fun fallbackProfileJson(skillName: String): String {
        val items = JSONArray()
        val defaults = listOf(
            Triple("vocabulary", "Introduction to $skillName - basics", "beginner"),
            Triple("pronunciation", "Core sounds and phonetics", "pronunciation"),
            Triple("vocabulary", "Top 10 essential words", "vocabulary"),
            Triple("grammar", "Basic sentence structure", "grammar"),
            Triple("vocabulary", "Numbers 1-10", "numbers"),
            Triple("pronunciation", "Common greetings", "greetings"),
            Triple("vocabulary", "Colors and shapes", "vocabulary"),
            Triple("grammar", "Question formation", "grammar"),
            Triple("vocabulary", "Daily routine vocabulary", "daily"),
            Triple("pronunciation", "Rhythm and intonation basics", "pronunciation")
        )
        defaults.forEach { (cat, content, tag) ->
            items.put(JSONObject().apply {
                put("category", cat)
                put("content", content)
                put("contentJson", JSONObject().apply {
                    put("hint", "Focus on clear pronunciation")
                    put("example", "Practice with a native speaker recording")
                    put("tip", "Repeat 5 times aloud")
                }.toString())
                put("tags", JSONArray().put(tag).put("beginner"))
            })
        }
        return JSONObject().apply {
            put("welcome", "Welcome to $skillName practice! Let's start with the fundamentals and build your skills step by step.")
            put("items", items)
        }.toString()
    }
}
