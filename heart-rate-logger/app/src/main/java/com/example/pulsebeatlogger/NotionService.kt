package com.example.pulsebeatlogger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Notion REST client — same HttpURLConnection pattern as [GeminiService].
 * Set token to "debug" for sandbox responses without a real integration.
 */
object NotionService {

    private const val API_BASE = "https://api.notion.com/v1"
    private const val NOTION_VERSION = "2022-06-28"
    const val OFFLINE_PLACEHOLDER = "__OFFLINE__"

    private fun isDebugKey(token: String) = token.trim().lowercase() == "debug"

    private fun headers(token: String): Array<Pair<String, String>> = arrayOf(
        "Authorization" to "Bearer $token",
        "Notion-Version" to NOTION_VERSION,
        "Content-Type" to "application/json; charset=utf-8"
    )

    /** Append a journal entry as blocks on a page (second-brain / daily log). */
    suspend fun appendJournal(
        token: String,
        pageId: String,
        title: String,
        body: String
    ): String = withContext(Dispatchers.IO) {
        if (isDebugKey(token)) {
            HeartRateState.log("🔬 Notion [MOCK] appendJournal: '$title'")
            return@withContext "Mock journal saved: $title (${body.take(80)}…)"
        }
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (pageId.isBlank()) return@withContext "Error: Notion page ID not configured in Settings."

        val children = JSONArray().apply {
            put(paragraphBlock("📓 $title"))
            put(paragraphBlock(body))
            put(dividerBlock())
        }
        val bodyJson = JSONObject().put("children", children).toString()
        val result = httpPost("$API_BASE/blocks/${pageId.trim()}/children", headers(token), bodyJson)
        if (result.startsWith("Error")) result else "Journal entry saved to Notion: $title"
    }

    /** Create a row in a Notion database (journal DB). */
    suspend fun createDatabaseEntry(
        token: String,
        databaseId: String,
        title: String,
        body: String,
        tags: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (isDebugKey(token)) {
            HeartRateState.log("🔬 Notion [MOCK] createDatabaseEntry: '$title'")
            return@withContext "Mock DB entry created: $title"
        }
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (databaseId.isBlank()) return@withContext "Error: Notion database ID not configured in Settings."

        val properties = JSONObject().apply {
            put("Name", JSONObject().put("title", JSONArray().put(
                JSONObject().put("text", JSONObject().put("content", title))
            )))
            if (tags.isNotEmpty()) {
                put("Tags", JSONObject().put("multi_select", JSONArray().apply {
                    tags.forEach { put(JSONObject().put("name", it)) }
                }))
            }
        }
        val payload = JSONObject().apply {
            put("parent", JSONObject().put("database_id", databaseId.trim()))
            put("properties", properties)
            put("children", JSONArray().apply {
                put(paragraphBlock(body))
            })
        }.toString()

        val raw = httpPost("$API_BASE/pages", headers(token), payload)
        if (raw.startsWith("Error")) raw else "Created Notion page: $title"
    }

    /** Query recent entries from a journal database. */
    suspend fun queryJournal(
        token: String,
        databaseId: String,
        limit: Int = 5
    ): String = withContext(Dispatchers.IO) {
        if (isDebugKey(token)) {
            return@withContext """
                [MOCK Notion journal — last $limit entries]
                1. Morning practice — Japanese SRS, streak day 3
                2. Exercise log — 30 min pushups, avg HR 118
                3. Weekly reflection — focus on pronunciation
            """.trimIndent()
        }
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (databaseId.isBlank()) return@withContext "Error: Notion database ID not configured."

        val payload = JSONObject().apply {
            put("page_size", limit.coerceIn(1, 20))
            put("sorts", JSONArray().put(
                JSONObject().put("timestamp", "last_edited_time").put("direction", "descending")
            ))
        }.toString()

        val raw = httpPost("$API_BASE/databases/${databaseId.trim()}/query", headers(token), payload)
        if (raw.startsWith("Error")) return@withContext raw

        try {
            val json = JSONObject(raw)
            val results = json.getJSONArray("results")
            if (results.length() == 0) return@withContext "No journal entries found in Notion."
            buildString {
                appendLine("Recent Notion journal ($limit max):")
                for (i in 0 until results.length()) {
                    val page = results.getJSONObject(i)
                    val title = extractPageTitle(page) ?: "Untitled"
                    val edited = page.optString("last_edited_time", "").take(10)
                    appendLine("${i + 1}. [$edited] $title")
                }
            }.trim()
        } catch (e: Exception) {
            HeartRateState.logError("Notion parse query failed", e)
            "Error parsing Notion response."
        }
    }

    /** Verify token + page/database access. */
    suspend fun testConnection(token: String, pageOrDbId: String): String = withContext(Dispatchers.IO) {
        if (isDebugKey(token)) return@withContext "Notion sandbox connected (debug token)."
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (token.isBlank()) return@withContext "Notion token not set."
        if (pageOrDbId.isBlank()) return@withContext "Notion page/database ID not set."

        val id = pageOrDbId.trim()
        // Try database first, then page
        var raw = httpGet("$API_BASE/databases/$id", headers(token))
        if (!raw.startsWith("Error")) {
            val title = JSONObject(raw).optJSONArray("title")?.let { extractRichText(it) } ?: "database"
            return@withContext "Connected to Notion database: $title"
        }
        raw = httpGet("$API_BASE/pages/$id", headers(token))
        if (!raw.startsWith("Error")) {
            val title = extractPageTitle(JSONObject(raw)) ?: "page"
            return@withContext "Connected to Notion page: $title"
        }
        "Connection failed — check token and that the integration has access to this page/database."
    }

    // ── Block helpers ─────────────────────────────────────────────────────────

    private fun paragraphBlock(text: String) = JSONObject().apply {
        put("object", "block")
        put("type", "paragraph")
        put("paragraph", JSONObject().put(
            "rich_text", JSONArray().put(
                JSONObject().put("type", "text").put("text", JSONObject().put("content", text))
            )
        ))
    }

    private fun dividerBlock() = JSONObject().apply {
        put("object", "block")
        put("type", "divider")
        put("divider", JSONObject())
    }

    private fun extractPageTitle(page: JSONObject): String? {
        val props = page.optJSONObject("properties") ?: return null
        for (key in props.keys()) {
            val prop = props.getJSONObject(key)
            if (prop.optString("type") == "title") {
                return extractRichText(prop.optJSONArray("title"))
            }
        }
        return null
    }

    private fun extractRichText(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        val item = arr.getJSONObject(0)
        return item.optString("plain_text").takeIf { it.isNotBlank() }
            ?: item.optJSONObject("text")?.optString("content")
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun httpGet(urlStr: String, hdrs: Array<Pair<String, String>>): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            readResponse(conn)
        } catch (e: Exception) {
            HeartRateState.logError("Notion GET failed", e)
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPost(urlStr: String, hdrs: Array<Pair<String, String>>, body: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } catch (e: Exception) {
            HeartRateState.logError("Notion POST failed", e)
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        HeartRateState.log("Notion: HTTP $code — ${text.take(80)}")
        return if (code in 200..299) text else "Error HTTP $code: ${text.take(200)}"
    }
}
