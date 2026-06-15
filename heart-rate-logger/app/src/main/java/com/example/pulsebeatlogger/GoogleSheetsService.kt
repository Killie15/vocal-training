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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Sheets API v4 — journal / log store using the same OAuth token as Calendar.
 * Spreadsheet ID from [HeartRateState.googleSheetUrl] (full URL or raw ID).
 */
object GoogleSheetsService {

    private const val API_BASE = "https://sheets.googleapis.com/v4/spreadsheets"
    private const val ERRORS_TAB = "Errors"
    private const val ACTIVITY_TAB = "Activity"

    fun parseSpreadsheetId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.isBlank()) return null
        Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(trimmed)?.groupValues?.get(1)?.let { return it }
        if (trimmed.matches(Regex("[a-zA-Z0-9_-]{20,}"))) return trimmed
        return null
    }

    suspend fun appendLogEntry(
        accessToken: String,
        spreadsheetId: String,
        type: String,
        title: String,
        detail: String,
        skill: String = "",
        hr: Int = 0
    ): String = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) {
            HeartRateState.log("🔬 Sheets [MOCK] append: '$title'")
            return@withContext "Mock log saved to Google Sheet: $title"
        }
        if (!HeartRateState.isOnline) return@withContext GeminiService.OFFLINE_PLACEHOLDER
        val id = parseSpreadsheetId(spreadsheetId)
            ?: return@withContext "Error: No Google Sheet configured — connect Google and tap Sync in ◈ System."

        val logTab = ensureLogTab(accessToken, id)
            ?: return@withContext "Error: Could not access spreadsheet tabs — check the sheet URL in Setup."

        writeHeaderIfEmpty(
            accessToken, id, "$logTab!A1:F1",
            listOf("Timestamp", "Type", "Title", "Detail", "Skill", "HR")
        )

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val row = JSONArray().put(JSONArray().apply {
            put(ts)
            put(type)
            put(title)
            put(detail)
            put(skill.ifBlank { HeartRateState.activeSkillName })
            put(if (hr > 0) hr else HeartRateState.currentBpm)
        })
        val body = JSONObject().put("values", row).toString()
        val range = URLEncoder.encode("$logTab!A:F", "UTF-8")
        val url = "$API_BASE/$id/values/$range:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS"

        val raw = httpPost(url, bearerHeaders(accessToken), body)
        if (raw.startsWith("Error")) raw else "Logged to Google Sheet: $title"
    }

    suspend fun appendErrorEntry(
        accessToken: String,
        spreadsheetId: String,
        source: String,
        message: String,
        detail: String,
        deviceId: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) {
            HeartRateState.log("🔬 Sheets [MOCK] error: '$message'")
            return@withContext "Mock error logged"
        }
        if (!HeartRateState.isOnline) return@withContext GeminiService.OFFLINE_PLACEHOLDER
        val id = parseSpreadsheetId(spreadsheetId) ?: return@withContext "Error: Invalid spreadsheet URL/ID."
        val errorsTab = ensureErrorsTab(accessToken, id)
            ?: return@withContext "Error: Could not open Errors tab on spreadsheet."
        writeHeaderIfEmpty(
            accessToken, id, "$errorsTab!A1:F1",
            listOf("Timestamp", "Source", "Message", "Detail", "App Version", "Device")
        )

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val row = JSONArray().put(JSONArray().apply {
            put(ts)
            put(source)
            put(message.take(500))
            put(detail.take(4000))
            put(BuildConfig.VERSION_NAME)
            put(deviceId)
        })
        val body = JSONObject().put("values", row).toString()
        val range = URLEncoder.encode("$errorsTab!A:F", "UTF-8")
        val url = "$API_BASE/$id/values/$range:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS"
        val raw = httpPost(url, bearerHeaders(accessToken), body)
        if (raw.startsWith("Error")) raw else "Error logged to sheet"
    }

    suspend fun createDiagnosticsSpreadsheet(accessToken: String): String? = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) return@withContext "SANDBOX_SHEET_ID"
        val body = JSONObject()
            .put("properties", JSONObject().put("title", "PulseBeat Learner Diagnostics"))
            .put("sheets", JSONArray().apply {
                put(JSONObject().put("properties", JSONObject().put("title", ACTIVITY_TAB)))
                put(JSONObject().put("properties", JSONObject().put("title", ERRORS_TAB)))
            })
            .toString()
        val raw = httpPost("$API_BASE", bearerHeaders(accessToken), body)
        if (raw.startsWith("Error")) return@withContext null
        try {
            val id = JSONObject(raw).optString("spreadsheetId", "")
            if (id.isBlank()) null else {
                ensureActivityHeader(accessToken, id)
                ensureErrorsHeader(accessToken, id)
                id
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun ensureErrorsTab(accessToken: String, spreadsheetId: String): String? = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) return@withContext ERRORS_TAB
        var tabs = listSheetTitles(accessToken, spreadsheetId)
        if (tabs.isEmpty()) return@withContext null
        if (ERRORS_TAB in tabs) return@withContext ERRORS_TAB
        if (addSheetTab(accessToken, spreadsheetId, ERRORS_TAB)) {
            tabs = listSheetTitles(accessToken, spreadsheetId)
            if (ERRORS_TAB in tabs) return@withContext ERRORS_TAB
        }
        // Fallback: log errors to first tab if we cannot add Errors
        tabs.firstOrNull()
    }

    /** Returns Activity, Sheet1, or the first tab — creates Activity if the sheet is empty. */
    private suspend fun ensureLogTab(accessToken: String, spreadsheetId: String): String? {
        if (GoogleAuthHelper.isDebugToken(accessToken)) return ACTIVITY_TAB
        var tabs = listSheetTitles(accessToken, spreadsheetId)
        if (tabs.isEmpty()) return null
        when {
            ACTIVITY_TAB in tabs -> return ACTIVITY_TAB
            "Sheet1" in tabs -> return "Sheet1"
            else -> {
                if (addSheetTab(accessToken, spreadsheetId, ACTIVITY_TAB)) {
                    tabs = listSheetTitles(accessToken, spreadsheetId)
                    if (ACTIVITY_TAB in tabs) return ACTIVITY_TAB
                }
                return tabs.firstOrNull()
            }
        }
    }

    suspend fun readRecentLog(
        accessToken: String,
        spreadsheetId: String,
        limit: Int = 10
    ): String = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) {
            return@withContext """
                [MOCK Sheet log — last $limit entries]
                2026-06-14 07:00 | practice | Morning Japanese | SRS review, streak day 3 | japanese | 82
                2026-06-13 18:30 | journal | Exercise note | Pushups 30 min | pushup | 118
            """.trimIndent()
        }
        if (!HeartRateState.isOnline) return@withContext GeminiService.OFFLINE_PLACEHOLDER
        val id = parseSpreadsheetId(spreadsheetId) ?: return@withContext "Error: Spreadsheet not configured."

        val logTab = ensureLogTab(accessToken, id)
            ?: return@withContext "Error: Could not read spreadsheet tabs."
        val range = URLEncoder.encode("$logTab!A:F", "UTF-8")
        val url = "$API_BASE/$id/values/$range"
        val raw = httpGet(url, bearerHeaders(accessToken))
        if (raw.startsWith("Error")) return@withContext raw

        try {
            val values = JSONObject(raw).optJSONArray("values") ?: JSONArray()
            if (values.length() <= 1) return@withContext "Sheet log is empty (add entries via assistant)."
            buildString {
                appendLine("Recent Google Sheet log (newest last):")
                val start = maxOf(1, values.length() - limit.coerceIn(1, 25))
                for (i in start until values.length()) {
                    val row = values.getJSONArray(i)
                    appendLine(formatRow(row))
                }
            }.trim()
        } catch (e: Exception) {
            HeartRateState.logError("Sheets parse failed", e)
            "Error reading sheet."
        }
    }

    suspend fun testConnection(accessToken: String, spreadsheetId: String): String = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) return@withContext "Google Sheets sandbox connected."
        if (!HeartRateState.isOnline) return@withContext GeminiService.OFFLINE_PLACEHOLDER
        val id = parseSpreadsheetId(spreadsheetId) ?: return@withContext "Paste your Google Sheet URL in setup."
        if (accessToken.isBlank()) return@withContext "Connect Google first (Calendar + Sheets)."

        val url = "$API_BASE/$id?fields=properties.title"
        val raw = httpGet(url, bearerHeaders(accessToken))
        if (raw.startsWith("Error")) return@withContext raw
        try {
            val title = JSONObject(raw).optJSONObject("properties")?.optString("title") ?: "Spreadsheet"
            "Connected to Google Sheet: $title"
        } catch (_: Exception) {
            "Connected to Google Sheet."
        }
    }

    /** Log latest app session summary to the sheet. */
    suspend fun logSessionSnapshot(accessToken: String, spreadsheetId: String, db: com.example.pulsebeatlogger.data.AppDatabase): String {
        val recent = db.skillSessionDao().getRecentSessions(1).firstOrNull()
        val detail = if (recent != null) {
            "duration=${recent.durationSeconds}s avgHr=${recent.avgHr} accuracy=${recent.accuracyPct.toInt()}%"
        } else {
            "No sessions yet — streak=${HeartRateState.streakDays} xp=${HeartRateState.totalXp}"
        }
        return appendLogEntry(
            accessToken, spreadsheetId,
            type = "session",
            title = "PulseBeat session snapshot",
            detail = detail,
            skill = HeartRateState.activeSkillName,
            hr = HeartRateState.currentBpm
        )
    }

    private suspend fun ensureHeader(accessToken: String, spreadsheetId: String) {
        if (GoogleAuthHelper.isDebugToken(accessToken)) return
        ensureLogTab(accessToken, spreadsheetId)?.let { tab ->
            writeHeaderIfEmpty(
                accessToken, spreadsheetId, "$tab!A1:F1",
                listOf("Timestamp", "Type", "Title", "Detail", "Skill", "HR")
            )
        }
        ensureErrorsTab(accessToken, spreadsheetId)?.let { tab ->
            writeHeaderIfEmpty(
                accessToken, spreadsheetId, "$tab!A1:F1",
                listOf("Timestamp", "Source", "Message", "Detail", "App Version", "Device")
            )
        }
    }

    private suspend fun ensureActivityHeader(accessToken: String, spreadsheetId: String) {
        ensureLogTab(accessToken, spreadsheetId)?.let { tab ->
            writeHeaderIfEmpty(
                accessToken, spreadsheetId, "$tab!A1:F1",
                listOf("Timestamp", "Type", "Title", "Detail", "Skill", "HR")
            )
        }
    }

    private suspend fun ensureErrorsHeader(accessToken: String, spreadsheetId: String) {
        ensureErrorsTab(accessToken, spreadsheetId)?.let { tab ->
            writeHeaderIfEmpty(
                accessToken, spreadsheetId, "$tab!A1:F1",
                listOf("Timestamp", "Source", "Message", "Detail", "App Version", "Device")
            )
        }
    }

    private suspend fun writeHeaderIfEmpty(
        accessToken: String,
        spreadsheetId: String,
        a1Range: String,
        headers: List<String>
    ) {
        val range = URLEncoder.encode(a1Range, "UTF-8")
        val url = "$API_BASE/$spreadsheetId/values/$range"
        val raw = httpGet(url, bearerHeaders(accessToken))
        if (raw.startsWith("Error")) return
        try {
            val values = JSONObject(raw).optJSONArray("values")
            if (values == null || values.length() == 0) {
                val headerRow = JSONArray().put(JSONArray(headers))
                val body = JSONObject().put("values", headerRow).toString()
                val updateUrl = "$API_BASE/$spreadsheetId/values/$range?valueInputOption=USER_ENTERED"
                httpPut(updateUrl, bearerHeaders(accessToken), body)
            }
        } catch (_: Exception) { /* non-fatal */ }
    }

    private suspend fun listSheetTitles(accessToken: String, spreadsheetId: String): List<String> {
        val url = "$API_BASE/$spreadsheetId?fields=sheets.properties.title"
        val raw = httpGet(url, bearerHeaders(accessToken))
        if (raw.startsWith("Error")) return emptyList()
        return try {
            val sheets = JSONObject(raw).optJSONArray("sheets") ?: return emptyList()
            (0 until sheets.length()).mapNotNull { i ->
                sheets.getJSONObject(i).optJSONObject("properties")?.optString("title")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun addSheetTab(accessToken: String, spreadsheetId: String, title: String): Boolean {
        val body = JSONObject()
            .put("requests", JSONArray().put(
                JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", title)))
            ))
            .toString()
        val raw = httpPost("$API_BASE/$spreadsheetId:batchUpdate", bearerHeaders(accessToken), body)
        return !raw.startsWith("Error")
    }

    private fun formatRow(row: JSONArray): String {
        val parts = (0 until row.length()).map { row.optString(it, "") }
        return parts.joinToString(" | ")
    }

    private fun bearerHeaders(token: String) = arrayOf(
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json; charset=utf-8"
    )

    private fun httpGet(urlStr: String, hdrs: Array<Pair<String, String>>): String =
        httpRequest("GET", urlStr, hdrs, null)

    private fun httpPost(urlStr: String, hdrs: Array<Pair<String, String>>, body: String): String =
        httpRequest("POST", urlStr, hdrs, body)

    private fun httpPut(urlStr: String, hdrs: Array<Pair<String, String>>, body: String): String =
        httpRequest("PUT", urlStr, hdrs, body)

    private fun httpRequest(method: String, urlStr: String, hdrs: Array<Pair<String, String>>, body: String?): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = body != null
                hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            body?.let { conn.outputStream.use { os: OutputStream -> os.write(it.toByteArray(Charsets.UTF_8)) } }
            readResponse(conn)
        } catch (e: Exception) {
            HeartRateState.log("Sheets $method failed: ${e.message}")
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        HeartRateState.log("Sheets: HTTP $code — ${text.take(80)}")
        return if (code in 200..299) text else "Error HTTP $code: ${formatApiError(text)}"
    }

    private fun formatApiError(body: String): String {
        return try {
            val err = JSONObject(body).optJSONObject("error")
            val msg = err?.optString("message") ?: body.take(200)
            val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
            if (reason != null) "$msg ($reason)" else msg
        } catch (_: Exception) {
            body.take(200)
        }
    }
}
