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

/** Google Drive API v3 — sync folder for profile, manifest, and optional APK updates. */
object GoogleDriveService {

    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    const val SYNC_FOLDER_NAME = "PulseBeat-Correct"
    /** Old folder names — merged into [SYNC_FOLDER_NAME] on next sync. */
    private val LEGACY_FOLDER_NAMES = listOf(
        "PulseBeat Learner Sync",
        "PulseBeat Learner Releases"
    )
    const val RELEASE_FOLDER_NAME = SYNC_FOLDER_NAME

    data class DriveFile(
        val id: String,
        val name: String,
        val modifiedTime: String,
        val mimeType: String = ""
    )

    fun parseFolderId(urlOrId: String): String? {
        val t = urlOrId.trim()
        if (t.isBlank()) return null
        Regex("/folders/([a-zA-Z0-9_-]+)").find(t)?.groupValues?.get(1)?.let { return it }
        if (t.matches(Regex("[a-zA-Z0-9_-]{10,}"))) return t
        return null
    }

    suspend fun findOrCreateSyncFolder(token: String): String? = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(token)) return@withContext "SANDBOX_DRIVE_FOLDER"

        findFoldersByName(token, SYNC_FOLDER_NAME).let { folders ->
            if (folders.isNotEmpty()) {
                return@withContext pickBestFolder(token, folders)
            }
        }

        val legacy = LEGACY_FOLDER_NAMES.flatMap { findFoldersByName(token, it) }
        if (legacy.isNotEmpty()) {
            val bestId = pickBestFolder(token, legacy)
            val bestFile = legacy.find { it.id == bestId }
            if (bestFile != null && renameFolder(token, bestId, SYNC_FOLDER_NAME)) {
                HeartRateState.log("Drive: renamed '${bestFile.name}' → $SYNC_FOLDER_NAME")
            }
            return@withContext bestId
        }

        createFolder(token, SYNC_FOLDER_NAME, null)
    }

    /** Prefer folder that already has manifest + APK (update channel). */
    private suspend fun pickBestFolder(token: String, folders: List<DriveFile>): String {
        var best = folders.first()
        var bestScore = folderScore(token, best.id)
        for (folder in folders.drop(1)) {
            val score = folderScore(token, folder.id)
            if (score > bestScore ||
                (score == bestScore && folder.modifiedTime > best.modifiedTime)
            ) {
                best = folder
                bestScore = score
            }
        }
        return best.id
    }

    suspend fun findOrCreateReleaseFolder(token: String): String? =
        findOrCreateSyncFolder(token)

    private suspend fun folderScore(token: String, folderId: String): Int {
        val files = listFolder(token, folderId)
        var score = files.size
        if (files.any { it.name == "profile.json" }) score += 100
        if (files.any { it.name == "manifest.json" }) score += 50
        if (files.any { it.name == "pulsebeat.apk" }) score += 50
        return score
    }

    private suspend fun findFoldersByName(token: String, name: String): List<DriveFile> {
        val q = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false",
            "UTF-8"
        )
        val raw = httpGet(
            "$API/files?q=$q&spaces=drive&fields=files(id,name,modifiedTime)&pageSize=20&orderBy=modifiedTime desc",
            bearer(token)
        )
        if (raw.startsWith("Error")) return emptyList()
        val arr = JSONObject(raw).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DriveFile(
                id = o.getString("id"),
                name = o.optString("name", name),
                modifiedTime = o.optString("modifiedTime", "")
            )
        }
    }

    private suspend fun renameFolder(token: String, folderId: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("name", newName).toString()
            val resp = httpPatchJson("$API/files/$folderId", bearer(token), body)
            !resp.startsWith("Error")
        }

    private fun httpPatchJson(url: String, hdrs: Array<Pair<String, String>>, body: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } catch (e: Exception) {
            HeartRateState.logError("Drive PATCH failed", e)
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun listFolder(token: String, folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(token)) {
            return@withContext listOf(
                DriveFile("mock-manifest", "manifest.json", "2026-06-14T12:00:00.000Z"),
                DriveFile("mock-profile", "profile.json", "2026-06-14T12:00:00.000Z")
            )
        }
        val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
        val raw = httpGet(
            "$API/files?q=$q&fields=files(id,name,modifiedTime,mimeType)&pageSize=100",
            bearer(token)
        )
        if (raw.startsWith("Error")) return@withContext emptyList()
        val arr = JSONObject(raw).optJSONArray("files") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DriveFile(
                id = o.getString("id"),
                name = o.getString("name"),
                modifiedTime = o.optString("modifiedTime", ""),
                mimeType = o.optString("mimeType", "")
            )
        }
    }

    suspend fun downloadText(token: String, fileId: String): String? = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(token)) {
            return@withContext when {
                fileId.contains("manifest") -> """{"versionCode":999,"versionName":"99.0","notes":"Sandbox update"}"""
                else -> """{"updatedAt":0,"goals":[],"memoryChunks":[]}"""
            }
        }
        val raw = httpGet("$API/files/$fileId?alt=media", bearer(token))
        if (raw.startsWith("Error")) null else raw
    }

    suspend fun downloadBinary(token: String, fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(token)) return@withContext ByteArray(0)
        downloadBytes("$API/files/$fileId?alt=media", bearer(token))
    }

    suspend fun uploadOrUpdateText(
        token: String,
        folderId: String,
        fileName: String,
        content: String,
        existingFileId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (GoogleAuthHelper.isDebugToken(token)) {
            HeartRateState.log("🔬 Drive [MOCK] upload $fileName (${content.length} chars)")
            return@withContext "mock-$fileName"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (existingFileId != null) {
            val url = "$UPLOAD/$existingFileId?uploadType=media"
            val ok = httpPatchBytes(url, bearer(token), bytes)
            return@withContext if (ok) existingFileId else null
        }
        val meta = JSONObject()
            .put("name", fileName)
            .put("mimeType", "application/json")
            .put("parents", JSONArray().put(folderId))
        multipartUpload(token, meta, bytes, "application/json")
    }

    suspend fun findFileInFolder(token: String, folderId: String, name: String): DriveFile? =
        listFolder(token, folderId)
            .filter { it.name == name }
            .maxByOrNull { it.modifiedTime }

    /** Quick update-channel probe for UI / diagnostics. */
    suspend fun probeUpdateChannel(token: String, folderId: String): String {
        val files = listFolder(token, folderId)
        val names = files.map { it.name }
        val hasManifest = "manifest.json" in names
        val hasApk = names.any { it.equals("pulsebeat.apk", ignoreCase = true) }
        return when {
            hasManifest && hasApk -> "Update channel OK (manifest.json + pulsebeat.apk)"
            hasManifest -> "manifest.json found — pulsebeat.apk MISSING (upload from PC)"
            hasApk -> "pulsebeat.apk found — manifest.json missing"
            else -> "No update files yet — run publish_release.ps1 on PC"
        }
    }

    suspend fun testConnection(token: String, folderId: String): String {
        if (GoogleAuthHelper.isDebugToken(token)) return "Drive · sandbox OK"
        val id = parseFolderId(folderId) ?: return "Drive · invalid folder ID/URL"
        val files = listFolder(token, id)
        return if (files.isEmpty()) "Drive · folder OK (empty — add manifest.json)"
        else "Drive · OK (${files.size} file(s): ${files.take(3).joinToString { it.name }})"
    }

    private suspend fun createFolder(token: String, name: String, parentId: String?): String? =
        withContext(Dispatchers.IO) {
            val meta = JSONObject()
                .put("name", name)
                .put("mimeType", "application/vnd.google-apps.folder")
            if (parentId != null) meta.put("parents", JSONArray().put(parentId))
            multipartUpload(token, meta, ByteArray(0), "application/vnd.google-apps.folder")
        }

    private fun bearer(token: String) = arrayOf("Authorization" to "Bearer $token")

    private fun multipartUpload(token: String, metadata: JSONObject, data: ByteArray, mime: String): String? {
        val boundary = "pulsebeat_${System.currentTimeMillis()}"
        val body = buildMultipartBody(boundary, metadata, data, mime)
        val hdrs = arrayOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "multipart/related; boundary=$boundary"
        )
        val raw = httpPostBytes("$UPLOAD?uploadType=multipart", hdrs, body)
        return try {
            if (raw.startsWith("Error")) null else JSONObject(raw).optString("id", null)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildMultipartBody(boundary: String, meta: JSONObject, data: ByteArray, mime: String): ByteArray {
        val crlf = "\r\n"
        val sb = StringBuilder()
        sb.append("--").append(boundary).append(crlf)
        sb.append("Content-Type: application/json; charset=UTF-8").append(crlf).append(crlf)
        sb.append(meta.toString()).append(crlf)
        sb.append("--").append(boundary).append(crlf)
        sb.append("Content-Type: $mime").append(crlf).append(crlf)
        val prefix = sb.toString().toByteArray(Charsets.UTF_8)
        val suffix = "${crlf}--$boundary--$crlf".toByteArray(Charsets.UTF_8)
        return prefix + data + suffix
    }

    private fun httpGet(url: String, hdrs: Array<Pair<String, String>>): String =
        httpText("GET", url, hdrs, null)

    private fun httpPostBytes(url: String, hdrs: Array<Pair<String, String>>, body: ByteArray): String =
        httpBytes("POST", url, hdrs, body)

    private fun httpPatchBytes(url: String, hdrs: Array<Pair<String, String>>, body: ByteArray): Boolean {
        val resp = httpBytes("PATCH", url, hdrs, body)
        return !resp.startsWith("Error")
    }

    private fun httpText(method: String, url: String, hdrs: Array<Pair<String, String>>, body: String?): String {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val resp = httpBytes(method, url, hdrs, bytes)
        return resp
    }

    private fun httpBytes(method: String, url: String, hdrs: Array<Pair<String, String>>, body: ByteArray?): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = body != null
                hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            body?.let { conn.outputStream.use { it.write(body) } }
            readResponse(conn)
        } catch (e: Exception) {
            HeartRateState.logError("Drive $method failed", e)
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadBytes(url: String, hdrs: Array<Pair<String, String>>): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            HeartRateState.logError("Drive download failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val isBinary = conn.contentType?.contains("application/vnd.android") == true ||
            conn.contentType?.contains("octet-stream") == true
        if (isBinary && code in 200..299) return ""
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        HeartRateState.log("Drive: HTTP $code — ${text.take(80)}")
        return if (code in 200..299) text else "Error HTTP $code: ${text.take(200)}"
    }
}
