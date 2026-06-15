package com.example.pulsebeatlogger

import android.content.Context
import android.content.SharedPreferences
import com.example.pulsebeatlogger.data.AppDatabase
import com.example.pulsebeatlogger.data.SystemMemoryChunk
import com.example.pulsebeatlogger.data.UserGoal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Drive sync — one folder **PulseBeat-Correct** for profile data and app updates.
 * Profile is saved on the phone first, pushed to `profile.json` when online.
 * Updates: `manifest.json` + `pulsebeat.apk` in the same folder.
 */
object DriveSyncService {

    const val PREF_FOLDER_ID = "googleDriveFolderId"
    const val PREF_RELEASE_FOLDER_ID = "release_drive_folder_id"
    const val PREF_LAST_SYNC_MS = "drive_last_sync_ms"
    const val PREF_PROFILE_FILE_ID = "drive_profile_file_id"
    const val PREF_OUTBOX = "drive_sync_outbox"
    const val PREF_DEVICE_ID = "drive_device_id"

    data class SyncResult(
        val message: String,
        val updateAvailable: AppUpdateChecker.UpdateInfo? = null,
        val apkFile: File? = null,
        /** Newer manifest on Drive but APK missing or download failed. */
        val updateBlockedReason: String? = null
    )

    fun deviceId(prefs: SharedPreferences): String {
        var id = prefs.getString(PREF_DEVICE_ID, "") ?: ""
        if (id.isBlank()) {
            id = UUID.randomUUID().toString().take(12)
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
        }
        return id
    }

    /** Queue a background sync (debounced in MainScreen). */
    fun requestSync() {
        HeartRateState.driveSyncRequested++
    }

    suspend fun sync(context: Context, db: AppDatabase, prefs: SharedPreferences): SyncResult =
        withContext(Dispatchers.IO) {
            if (!HeartRateState.isOnline) {
                markDirty(prefs, triggerSync = false)
                return@withContext SyncResult("Offline — saved on phone, will sync when online")
            }
            if (!GoogleAuthHelper.isGoogleConnected(context)) {
                return@withContext SyncResult("Google not connected — sign in under ◈ System → Setup")
            }

            val token = GoogleAuthHelper.getAccessToken(context)
                ?: return@withContext SyncResult("Could not get Google token — reconnect Google")

            ErrorReportService.ensureDiagnosticsSheet(context, prefs)

            HeartRateState.driveSyncStatus = "Syncing…"
            var updateInfo: AppUpdateChecker.UpdateInfo? = null
            var apkFile: File? = null
            val parts = mutableListOf<String>()

            // ── App update check (canonical PulseBeat-Correct folder) ───────────
            var updateBlockedReason: String? = null
            val canonicalFolder = refreshCanonicalFolder(prefs, token)
            if (canonicalFolder.isNotBlank()) {
                when (val updateResult = pullUpdateIfNewer(context, token, canonicalFolder)) {
                    is UpdatePullResult.Ready -> {
                        updateInfo = updateResult.info
                        apkFile = updateResult.apkFile
                        parts.add("update v${updateResult.info.versionName} ready")
                    }
                    is UpdatePullResult.UpToDate -> { /* ok */ }
                    is UpdatePullResult.NoManifest ->
                        parts.add("no manifest.json in Drive yet")
                    is UpdatePullResult.ApkMissing -> {
                        updateInfo = updateResult.info
                        updateBlockedReason = "manifest v${updateResult.info.versionName} on Drive but pulsebeat.apk is missing — upload both files from PC"
                        parts.add("update blocked — APK missing in Drive")
                    }
                    is UpdatePullResult.DownloadFailed -> {
                        updateInfo = updateResult.info
                        updateBlockedReason = "Update v${updateResult.info.versionName} found but download failed — tap Check for app update to retry"
                        parts.add("update download failed")
                    }
                }
            }

            // ── Your data: pull + push profile.json ───────────────────────────
            var folderId = canonicalFolder.ifBlank {
                prefs.getString(PREF_FOLDER_ID, "")?.trim().orEmpty()
            }
            if (folderId.isBlank()) {
                folderId = GoogleDriveService.findOrCreateSyncFolder(token) ?: ""
                if (folderId.isNotBlank()) {
                    prefs.edit().putString(PREF_FOLDER_ID, folderId).apply()
                    HeartRateState.googleDriveFolderId = folderId
                }
            } else if (folderId != prefs.getString(PREF_FOLDER_ID, "")) {
                prefs.edit().putString(PREF_FOLDER_ID, folderId).apply()
                HeartRateState.googleDriveFolderId = folderId
            }
            if (folderId.isNotBlank()) {
                val profileFile = GoogleDriveService.findFileInFolder(token, folderId, "profile.json")
                if (profileFile != null) {
                    GoogleDriveService.downloadText(token, profileFile.id)?.let { text ->
                        val merged = mergeRemoteProfile(db, text)
                        if (merged > 0) parts.add("merged $merged from cloud")
                        prefs.edit().putString(PREF_PROFILE_FILE_ID, profileFile.id).apply()
                    }
                }

                val snapshot = buildProfileJson(db, prefs)
                val profileFileId = prefs.getString(PREF_PROFILE_FILE_ID, null)
                val uploadedId = GoogleDriveService.uploadOrUpdateText(
                    token, folderId, "profile.json", snapshot, profileFileId
                )
                if (uploadedId != null) {
                    prefs.edit().putString(PREF_PROFILE_FILE_ID, uploadedId).apply()
                    parts.add("profile saved to cloud")
                }
                flushOutbox(token, folderId, prefs)
            } else {
                parts.add("data folder pending — tap Sync after Google connect")
            }

            prefs.edit().putLong(PREF_LAST_SYNC_MS, System.currentTimeMillis()).apply()
            prefs.edit().remove(PREF_OUTBOX).apply()
            HeartRateState.driveLastSyncMs = System.currentTimeMillis()

            SystemService.loadFromDb(db)

            ErrorReportService.flush(context, prefs)

            val msg = if (parts.isEmpty()) "Synced" else parts.joinToString(" · ")
            HeartRateState.driveSyncStatus = msg
            HeartRateState.log("DriveSync: $msg")
            SyncResult(msg, updateInfo, apkFile, updateBlockedReason)
        }

    /** Check Drive for a newer APK (Sync now / Check for app update button). */
    suspend fun checkReleaseUpdateOnly(context: Context, prefs: SharedPreferences): SyncResult = withContext(Dispatchers.IO) {
        if (!HeartRateState.isOnline) {
            return@withContext SyncResult("Offline — connect to Wi‑Fi or mobile data")
        }
        if (!GoogleAuthHelper.isGoogleConnected(context)) {
            return@withContext SyncResult("Connect Google first")
        }
        val token = GoogleAuthHelper.getAccessToken(context)
            ?: return@withContext SyncResult("Could not get Google token")
        val folderId = refreshCanonicalFolder(prefs, token)
        if (folderId.isBlank()) {
            return@withContext SyncResult("No Drive folder — tap Sync now once after connecting Google")
        }
        val channel = GoogleDriveService.probeUpdateChannel(token, folderId)
        when (val result = pullUpdateIfNewer(context, token, folderId)) {
            is UpdatePullResult.Ready -> SyncResult(
                "Update v${result.info.versionName} ready — tap Install",
                result.info,
                result.apkFile
            )
            is UpdatePullResult.UpToDate -> SyncResult(
                "App is up to date (v${AppUpdateChecker.currentVersionCode(context)}). $channel"
            )
            is UpdatePullResult.NoManifest -> SyncResult(
                "No manifest.json in PulseBeat-Correct. $channel"
            )
            is UpdatePullResult.ApkMissing -> SyncResult(
                "Update v${result.info.versionName} listed in manifest but pulsebeat.apk is NOT in Drive. Upload both files from PC (release-staging folder).",
                result.info,
                null,
                "Upload pulsebeat.apk to PulseBeat-Correct in Google Drive"
            )
            is UpdatePullResult.DownloadFailed -> SyncResult(
                "Update v${result.info.versionName} found but download failed — retry in a moment",
                result.info,
                null,
                result.reason
            )
        }
    }

    fun markDirty(prefs: SharedPreferences, triggerSync: Boolean = true) {
        val outbox = prefs.getString(PREF_OUTBOX, "[]") ?: "[]"
        val arr = try { JSONArray(outbox) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("reason", "local_change"))
        prefs.edit().putString(PREF_OUTBOX, arr.toString()).apply()
        HeartRateState.driveSyncStatus = "Saved locally · sync when online"
        if (triggerSync) requestSync()
    }

    private fun releaseFolderId(prefs: SharedPreferences): String? {
        prefs.getString(PREF_RELEASE_FOLDER_ID, "")?.trim()?.let { saved ->
            GoogleDriveService.parseFolderId(saved)?.let { return it }
        }
        val baked = BuildConfig.RELEASE_DRIVE_FOLDER_ID.trim()
        if (baked.isNotBlank()) return GoogleDriveService.parseFolderId(baked)
        return null
    }

    /** Creates "PulseBeat Learner Releases" in the user's Drive and saves the folder id. */
    suspend fun ensureReleaseFolder(context: Context, prefs: SharedPreferences): String? =
        withContext(Dispatchers.IO) {
            if (!GoogleAuthHelper.isGoogleConnected(context)) return@withContext null
            releaseFolderId(prefs)?.let { return@withContext it }
            val token = GoogleAuthHelper.getAccessToken(context) ?: return@withContext null
            val folderId = GoogleDriveService.findOrCreateReleaseFolder(token) ?: return@withContext null
            prefs.edit().putString(PREF_RELEASE_FOLDER_ID, folderId).apply()
            HeartRateState.log("Release folder ready: $folderId")
            folderId
        }

    fun releaseFolderUrl(prefs: SharedPreferences): String {
        val id = releaseFolderId(prefs) ?: prefs.getString(PREF_FOLDER_ID, "")?.trim()?.takeIf { it.isNotBlank() }
        if (id.isNullOrBlank()) return ""
        return "https://drive.google.com/drive/folders/${GoogleDriveService.parseFolderId(id) ?: id}"
    }

    /** Resolves PulseBeat-Correct (best folder if duplicates) and saves id to prefs. */
    private suspend fun refreshCanonicalFolder(prefs: SharedPreferences, token: String): String {
        releaseFolderId(prefs)?.let { return it }
        val id = GoogleDriveService.findOrCreateSyncFolder(token) ?: return ""
        prefs.edit().putString(PREF_FOLDER_ID, id).apply()
        HeartRateState.googleDriveFolderId = id
        return id
    }

    private sealed class UpdatePullResult {
        data class Ready(val info: AppUpdateChecker.UpdateInfo, val apkFile: File) : UpdatePullResult()
        data object UpToDate : UpdatePullResult()
        data object NoManifest : UpdatePullResult()
        data class ApkMissing(val info: AppUpdateChecker.UpdateInfo) : UpdatePullResult()
        data class DownloadFailed(val info: AppUpdateChecker.UpdateInfo, val reason: String) : UpdatePullResult()
    }

    private suspend fun pullUpdateIfNewer(
        context: Context,
        token: String,
        folderId: String
    ): UpdatePullResult {
        val manifestFile = GoogleDriveService.findFileInFolder(token, folderId, "manifest.json")
            ?: return UpdatePullResult.NoManifest
        val manifestText = GoogleDriveService.downloadText(token, manifestFile.id)
            ?: return UpdatePullResult.NoManifest
        return applyManifestPull(context, manifestText, token, folderId)
    }

    private suspend fun applyManifestPull(
        context: Context,
        manifestText: String,
        token: String,
        folderId: String
    ): UpdatePullResult {
        return try {
            val obj = JSONObject(manifestText)
            val remoteCode = obj.optInt("versionCode", 0)
            if (remoteCode <= 0) return UpdatePullResult.NoManifest
            if (remoteCode <= AppUpdateChecker.currentVersionCode(context)) {
                return UpdatePullResult.UpToDate
            }
            val info = AppUpdateChecker.UpdateInfo(
                versionCode = remoteCode,
                versionName = obj.optString("versionName", "$remoteCode"),
                apkUrl = "",
                notes = obj.optString("notes", "Update available")
            )
            val apkName = obj.optString("apkFileName", "pulsebeat.apk")
            val apkDrive = GoogleDriveService.findFileInFolder(token, folderId, apkName)
                ?: return UpdatePullResult.ApkMissing(info)
            val bytes = GoogleDriveService.downloadBinary(token, apkDrive.id)
                ?: return UpdatePullResult.DownloadFailed(info, "Drive download returned empty")
            if (bytes.size < 10_000) {
                return UpdatePullResult.DownloadFailed(info, "APK too small (${bytes.size} bytes)")
            }
            val file = File(context.cacheDir, "updates/pulsebeat-drive.apk").also {
                it.parentFile?.mkdirs()
                it.writeBytes(bytes)
            }
            HeartRateState.log("DriveSync: pulled APK v${info.versionName} (${bytes.size} bytes)")
            UpdatePullResult.Ready(info, file)
        } catch (e: Exception) {
            HeartRateState.logError("DriveSync: manifest/APK pull failed", e)
            UpdatePullResult.NoManifest
        }
    }

    private suspend fun mergeRemoteProfile(db: AppDatabase, json: String): Int {
        val obj = JSONObject(json)
        var count = 0
        val goals = obj.optJSONArray("goals") ?: JSONArray()
        for (i in 0 until goals.length()) {
            val g = goals.getJSONObject(i)
            val goal = UserGoal(
                id = g.optString("id", UUID.randomUUID().toString()),
                title = g.optString("title", ""),
                description = g.optString("description", ""),
                status = g.optString("status", "active"),
                priority = g.optString("priority", "medium"),
                createdAt = g.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = g.optLong("updatedAt", System.currentTimeMillis())
            )
            if (goal.title.isNotBlank()) {
                db.userGoalDao().upsert(goal)
                count++
            }
        }
        val chunks = obj.optJSONArray("memoryChunks") ?: JSONArray()
        for (i in 0 until chunks.length()) {
            val c = chunks.getJSONObject(i)
            val chunk = SystemMemoryChunk(
                id = c.optString("id", UUID.randomUUID().toString()),
                content = c.optString("content", ""),
                category = c.optString("category", "exchange"),
                keywords = c.optString("keywords", ""),
                userSnippet = c.optString("userSnippet", ""),
                systemSnippet = c.optString("systemSnippet", ""),
                createdAt = c.optLong("createdAt", System.currentTimeMillis())
            )
            if (chunk.content.isNotBlank()) {
                db.systemMemoryChunkDao().upsert(chunk)
                count++
            }
        }
        return count
    }

    private suspend fun buildProfileJson(db: AppDatabase, prefs: SharedPreferences): String {
        val goals = db.userGoalDao().getAllRecent(50)
        val chunks = db.systemMemoryChunkDao().getRecent(100)
        val goalsArr = JSONArray()
        goals.forEach { g ->
            goalsArr.put(JSONObject().apply {
                put("id", g.id)
                put("title", g.title)
                put("description", g.description)
                put("status", g.status)
                put("priority", g.priority)
                put("createdAt", g.createdAt)
                put("updatedAt", g.updatedAt)
            })
        }
        val chunkArr = JSONArray()
        chunks.forEach { c ->
            chunkArr.put(JSONObject().apply {
                put("id", c.id)
                put("content", c.content)
                put("category", c.category)
                put("keywords", c.keywords)
                put("userSnippet", c.userSnippet)
                put("systemSnippet", c.systemSnippet)
                put("createdAt", c.createdAt)
            })
        }
        return JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("deviceId", deviceId(prefs))
            put("goals", goalsArr)
            put("memoryChunks", chunkArr)
            put("activeSkill", HeartRateState.activeSkillName)
            put("totalXp", HeartRateState.totalXp)
            put("streakDays", HeartRateState.streakDays)
        }.toString(2)
    }

    private suspend fun flushOutbox(token: String, folderId: String, prefs: SharedPreferences) {
        val outbox = prefs.getString(PREF_OUTBOX, "[]") ?: return
        if (outbox == "[]") return
        val name = "outbox_${deviceId(prefs)}_${System.currentTimeMillis()}.json"
        GoogleDriveService.uploadOrUpdateText(token, folderId, name, outbox, null)
    }
}
