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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Google Calendar REST client. Requires an OAuth access token from [GoogleAuthHelper].
 * Pass accessToken = "debug" for sandbox responses.
 */
object GoogleCalendarService {

    private const val API_BASE = "https://www.googleapis.com/calendar/v3"
    const val OFFLINE_PLACEHOLDER = "__OFFLINE__"

    private fun isDebugToken(token: String) = token.trim().lowercase() == "debug"

    /** List upcoming events on the user's primary calendar. */
    suspend fun listUpcoming(
        accessToken: String,
        daysAhead: Int = 7,
        maxResults: Int = 10
    ): String = withContext(Dispatchers.IO) {
        if (isDebugToken(accessToken)) {
            return@withContext """
                [MOCK Calendar — next $daysAhead days]
                • Mon 7:00 AM — Morning Exercise (PulseBeat)
                • Wed 6:30 PM — Japanese practice
                • Fri 7:00 AM — Pushups + stretch
            """.trimIndent()
        }
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (accessToken.isBlank()) return@withContext "Error: Google Calendar not connected — tap Connect Google in Assistant settings."

        val now = Instant.now()
        val until = now.plusSeconds(daysAhead.toLong() * 86400)
        val timeMin = URLEncoder.encode(now.toString(), "UTF-8")
        val timeMax = URLEncoder.encode(until.toString(), "UTF-8")
        val url = "$API_BASE/calendars/primary/events?singleEvents=true&orderBy=startTime" +
            "&timeMin=$timeMin&timeMax=$timeMax&maxResults=${maxResults.coerceIn(1, 25)}"

        val raw = httpGet(url, bearerHeaders(accessToken))
        if (raw.startsWith("Error")) return@withContext raw

        try {
            val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) return@withContext "No upcoming events in the next $daysAhead days."
            buildString {
                appendLine("Upcoming calendar events:")
                for (i in 0 until items.length()) {
                    val ev = items.getJSONObject(i)
                    val title = ev.optString("summary", "(no title)")
                    val start = ev.optJSONObject("start")?.optString("dateTime")
                        ?: ev.optJSONObject("start")?.optString("date") ?: "?"
                    appendLine("• $start — $title")
                }
            }.trim()
        } catch (e: Exception) {
            HeartRateState.logError("Calendar parse list failed", e)
            "Error parsing calendar response."
        }
    }

    /** Create an event on the primary calendar. Times in ISO-8601 (e.g. 2026-06-15T07:00:00-05:00). */
    suspend fun createEvent(
        accessToken: String,
        title: String,
        startIso: String,
        endIso: String? = null,
        description: String = "",
        recurrence: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (isDebugToken(accessToken)) {
            HeartRateState.log("🔬 Calendar [MOCK] createEvent: '$title' at $startIso")
            return@withContext "Mock calendar event created: $title at $startIso"
        }
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (accessToken.isBlank()) return@withContext "Error: Google Calendar not connected."

        val normalizedStart = normalizeDateTime(startIso)
            ?: return@withContext "Error: Invalid start time \"$startIso\". Use ISO format like 2026-06-15T07:00:00-05:00, or ask for a daily practice block instead."

        val end = endIso?.let { normalizeDateTime(it) } ?: run {
            try {
                ZonedDateTime.parse(normalizedStart, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .plusHours(1)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (_: Exception) {
                normalizedStart
            }
        }

        val event = JSONObject().apply {
            put("summary", title)
            if (description.isNotBlank()) put("description", description)
            put("start", JSONObject().put("dateTime", normalizedStart).put("timeZone", ZoneId.systemDefault().id))
            put("end", JSONObject().put("dateTime", end).put("timeZone", ZoneId.systemDefault().id))
            if (!recurrence.isNullOrBlank()) {
                val rule = if (recurrence.startsWith("RRULE:")) recurrence else "RRULE:$recurrence"
                put("recurrence", JSONArray().put(rule))
            }
        }

        val raw = httpPost("$API_BASE/calendars/primary/events", bearerHeaders(accessToken), event.toString())
        if (raw.startsWith("Error")) return@withContext raw

        try {
            val created = JSONObject(raw)
            val link = created.optString("htmlLink", "")
            "Calendar event created: $title${if (link.isNotBlank()) " ($link)" else ""}"
        } catch (_: Exception) {
            "Calendar event created: $title"
        }
    }

    /** Quick helper — daily recurring practice block starting tomorrow at [hour]:[minute]. */
    suspend fun createDailyPracticeBlock(
        accessToken: String,
        title: String,
        hour: Int,
        minute: Int = 0
    ): String {
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        val start = tomorrow.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            .atZone(ZoneId.systemDefault())
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val startIso = start.format(fmt)
        val endIso = start.plusHours(1).format(fmt)
        return createEvent(
            accessToken = accessToken,
            title = title,
            startIso = startIso,
            endIso = endIso,
            description = "Created by PulseBeat Assistant",
            recurrence = "RRULE:FREQ=DAILY"
        )
    }

    suspend fun testConnection(accessToken: String): String = withContext(Dispatchers.IO) {
        if (isDebugToken(accessToken)) return@withContext "Google Calendar sandbox connected (debug token)."
        if (!HeartRateState.isOnline) return@withContext OFFLINE_PLACEHOLDER
        if (accessToken.isBlank()) return@withContext "Not signed in to Google Calendar."

        val raw = httpGet("$API_BASE/calendars/primary", bearerHeaders(accessToken))
        if (raw.startsWith("Error")) return@withContext raw
        try {
            val cal = JSONObject(raw)
            val summary = cal.optString("summary", "Primary calendar")
            "Connected to Google Calendar: $summary"
        } catch (_: Exception) {
            "Connected to Google Calendar."
        }
    }

    private fun bearerHeaders(token: String) = arrayOf(
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json; charset=utf-8"
    )

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
            HeartRateState.logError("Calendar GET failed", e)
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
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
            readResponse(conn)
        } catch (e: Exception) {
            HeartRateState.logError("Calendar POST failed", e)
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        HeartRateState.log("Calendar: HTTP $code — ${text.take(80)}")
        return if (code in 200..299) text else "Error HTTP $code: ${formatApiError(text)}"
    }

    /** Accept ISO instants, offset datetimes, or local datetimes — Calendar requires RFC3339 with offset. */
    private fun normalizeDateTime(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val zone = ZoneId.systemDefault()
        val outFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        try {
            return Instant.parse(t).atZone(zone).format(outFmt)
        } catch (_: Exception) { }
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).format(outFmt)
        } catch (_: Exception) { }
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.ISO_ZONED_DATE_TIME).format(outFmt)
        } catch (_: Exception) { }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).format(outFmt)
        } catch (_: Exception) { }
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).format(outFmt)
        } catch (_: Exception) { }
        return null
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
