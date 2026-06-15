package com.example.pulsebeatlogger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a small JSON manifest on the internet for a newer APK.
 * No USB / adb — user taps Install once when an update is available.
 *
 * Manifest JSON (host on Google Drive, GitHub, Dropbox, etc.):
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "apkUrl": "https://... direct link to .apk",
 *   "notes": "Optional changelog"
 * }
 */
object AppUpdateChecker {

    const val PREF_MANIFEST_URL = "update_manifest_url"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    fun currentVersionCode(context: Context): Int = try {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) {
        1
    }

    suspend fun checkForUpdate(context: Context, manifestUrl: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            if (manifestUrl.isBlank()) return@withContext null
            try {
                val json = fetchText(manifestUrl.trim()) ?: return@withContext null
                val obj = JSONObject(json.trim().removePrefix("\uFEFF"))
                val remoteCode = obj.optInt("versionCode", 0)
                val apkUrl = obj.optString("apkUrl", "").trim()
                if (remoteCode <= 0 || apkUrl.isBlank()) return@withContext null
                if (remoteCode <= currentVersionCode(context)) return@withContext null
                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = obj.optString("versionName", "$remoteCode"),
                    apkUrl = apkUrl,
                    notes = obj.optString("notes", "Bug fixes and improvements.")
                )
            } catch (e: Exception) {
                HeartRateState.log("Update check skipped: ${e.message}")
                null
            }
        }

    suspend fun downloadApk(context: Context, update: UpdateInfo): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
                val out = File(dir, "pulsebeat-update.apk")
                val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                if (out.length() < 10_000) {
                    out.delete()
                    return@withContext null
                }
                out
            } catch (e: Exception) {
                HeartRateState.logError("Update download failed", e)
                null
            }
        }

    fun launchInstall(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun fetchText(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
