package com.example.pulsebeatlogger

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Queues app errors locally, then flushes to:
 * - **Errors** tab on the diagnostics Google Sheet (auto-created on first Google connect)
 * - **errors.json** in the Drive sync folder (via [DriveSyncService])
 */
object ErrorReportService {

    private const val PREF_PENDING = "pending_error_reports"
    private const val MAX_PENDING = 50

    fun report(source: String, message: String, throwable: Throwable? = null) {
        val detail = buildString {
            append(message)
            throwable?.let {
                append(" | ")
                append(it.javaClass.simpleName)
                append(": ")
                append(it.message ?: "unknown")
                it.stackTraceToString().lines().take(6).forEach { line ->
                    append("\n")
                    append(line.trim())
                }
            }
        }
        enqueuePending(source, detail)
        DriveSyncService.requestSync()
    }

    /** Create or reuse diagnostics sheet; saves URL to prefs. */
    suspend fun ensureDiagnosticsSheet(context: Context, prefs: SharedPreferences): String? =
        withContext(Dispatchers.IO) {
            if (HeartRateState.calendarSandboxMode) return@withContext null
            if (!GoogleAuthHelper.isGoogleConnected(context)) return@withContext null
            val token = GoogleAuthHelper.getAccessToken(context) ?: return@withContext null

            val existing = prefs.getString("googleSheetUrl", "")?.trim().orEmpty()
            if (existing.isNotBlank()) {
                val id = GoogleSheetsService.parseSpreadsheetId(existing) ?: return@withContext existing
                GoogleSheetsService.ensureErrorsTab(token, id)
                return@withContext existing
            }

            val created = GoogleSheetsService.createDiagnosticsSpreadsheet(token) ?: return@withContext null
            val url = "https://docs.google.com/spreadsheets/d/$created/edit"
            prefs.edit().putString("googleSheetUrl", url).apply()
            HeartRateState.googleSheetUrl = url
            HeartRateState.log("Diagnostics sheet created: $url")
            url
        }

    suspend fun flush(context: Context, prefs: SharedPreferences) = withContext(Dispatchers.IO) {
        val pending = drainPending(prefs)
        if (pending.length() == 0) return@withContext

        if (GoogleAuthHelper.isGoogleConnected(context) && HeartRateState.isOnline) {
            val token = GoogleAuthHelper.getAccessToken(context)
            var sheetUrl = prefs.getString("googleSheetUrl", "")?.trim().orEmpty()
            if (sheetUrl.isBlank() && token != null) {
                sheetUrl = ensureDiagnosticsSheet(context, prefs) ?: ""
            }
            if (token != null && sheetUrl.isNotBlank()) {
                for (i in 0 until pending.length()) {
                    val entry = pending.getJSONObject(i)
                    GoogleSheetsService.appendErrorEntry(
                        token,
                        sheetUrl,
                        entry.optString("source", "app"),
                        entry.optString("message", ""),
                        entry.optString("detail", ""),
                        DriveSyncService.deviceId(prefs)
                    )
                }
            }
        }

        uploadErrorsJson(context, prefs, pending)
    }

    fun pendingErrorsJson(prefs: SharedPreferences): String {
        val arr = readPending(prefs)
        return if (arr.length() == 0) "[]" else arr.toString(2)
    }

    private fun enqueuePending(source: String, detail: String) {
        // HeartRateState has no prefs — caller sync triggers flush from DriveSyncService with prefs
        HeartRateState.pendingErrorEnqueue.add(
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("source", source)
                .put("message", detail.lineSequence().first().take(500))
                .put("detail", detail.take(4000))
                .put("version", BuildConfig.VERSION_NAME)
        )
    }

    private fun drainPending(prefs: SharedPreferences): JSONArray {
        val arr = readPending(prefs)
        HeartRateState.pendingErrorEnqueue.forEach { arr.put(it) }
        HeartRateState.pendingErrorEnqueue.clear()
        if (arr.length() == 0) return arr
        prefs.edit().remove(PREF_PENDING).apply()
        return arr
    }

    private fun readPending(prefs: SharedPreferences): JSONArray {
        val raw = prefs.getString(PREF_PENDING, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private suspend fun uploadErrorsJson(context: Context, prefs: SharedPreferences, batch: JSONArray) {
        if (!GoogleAuthHelper.isGoogleConnected(context) || !HeartRateState.isOnline) {
            persistPending(prefs, batch)
            return
        }
        val token = GoogleAuthHelper.getAccessToken(context) ?: run {
            persistPending(prefs, batch)
            return
        }
        var folderId = prefs.getString(DriveSyncService.PREF_FOLDER_ID, "")?.trim().orEmpty()
        if (folderId.isBlank()) {
            folderId = GoogleDriveService.findOrCreateSyncFolder(token) ?: run {
                persistPending(prefs, batch)
                return
            }
            prefs.edit().putString(DriveSyncService.PREF_FOLDER_ID, folderId).apply()
        }

        val existingId = prefs.getString("drive_errors_file_id", null)
        val merged = mergeErrorArrays(
            if (existingId != null) {
                GoogleDriveService.downloadText(token, existingId)?.let { parseErrorArray(it) }
            } else {
                GoogleDriveService.findFileInFolder(token, folderId, "errors.json")?.let { file ->
                    prefs.edit().putString("drive_errors_file_id", file.id).apply()
                    GoogleDriveService.downloadText(token, file.id)?.let { parseErrorArray(it) }
                }
            },
            batch
        )
        val out = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("deviceId", DriveSyncService.deviceId(prefs))
            .put("errors", merged)
            .toString(2)

        val uploaded = GoogleDriveService.uploadOrUpdateText(
            token, folderId, "errors.json", out, existingId
        )
        if (uploaded != null) {
            prefs.edit().putString("drive_errors_file_id", uploaded).apply()
        } else {
            persistPending(prefs, batch)
        }
    }

    private fun persistPending(prefs: SharedPreferences, batch: JSONArray) {
        val existing = readPending(prefs)
        for (i in 0 until batch.length()) {
            existing.put(batch.getJSONObject(i))
        }
        while (existing.length() > MAX_PENDING) {
            existing.remove(0)
        }
        prefs.edit().putString(PREF_PENDING, existing.toString()).apply()
    }

    private fun mergeErrorArrays(existing: JSONArray?, batch: JSONArray): JSONArray {
        val out = JSONArray()
        existing?.let { for (i in 0 until it.length()) out.put(it.getJSONObject(i)) }
        for (i in 0 until batch.length()) out.put(batch.getJSONObject(i))
        val start = maxOf(0, out.length() - 200)
        val trimmed = JSONArray()
        for (i in start until out.length()) trimmed.put(out.getJSONObject(i))
        return trimmed
    }

    private fun parseErrorArray(json: String): JSONArray {
        return try {
            val obj = JSONObject(json)
            obj.optJSONArray("errors") ?: JSONArray()
        } catch (_: Exception) {
            try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        }
    }

    fun formatTs(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
