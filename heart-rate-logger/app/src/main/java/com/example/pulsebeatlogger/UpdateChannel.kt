package com.example.pulsebeatlogger

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * App updates: GitHub manifest (HTTPS) first, Google Drive fallback for profile sync folder.
 * No manual upload — run [scripts/publish_github_release.ps1] on PC after each build.
 */
object UpdateChannel {

    data class UpdateResult(
        val message: String,
        /** APK already on disk (Drive pull). */
        val localApk: File? = null,
        /** Needs download from [remoteInfo].apkUrl (GitHub Release). */
        val remoteInfo: AppUpdateChecker.UpdateInfo? = null
    )

    fun manifestUrl(prefs: SharedPreferences): String {
        prefs.getString(AppUpdateChecker.PREF_MANIFEST_URL, "")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return BuildConfig.UPDATE_MANIFEST_URL.trim()
    }

    /** Check GitHub (or custom manifest URL), then Drive. Works without Google sign-in. */
    suspend fun checkForUpdate(context: Context, prefs: SharedPreferences): UpdateResult {
        val url = manifestUrl(prefs)
        if (url.isNotBlank() && HeartRateState.isOnline) {
            try {
                AppUpdateChecker.checkForUpdate(context, url)?.let { info ->
                    HeartRateState.log("UpdateChannel: GitHub/HTTP update v${info.versionName} (${info.versionCode})")
                    return UpdateResult(
                        message = "Update v${info.versionName} ready — tap Install",
                        remoteInfo = info
                    )
                }
            } catch (e: Exception) {
                HeartRateState.log("UpdateChannel: manifest check failed: ${e.message}")
            }
        }

        if (GoogleAuthHelper.isGoogleConnected(context) && HeartRateState.isOnline) {
            val drive = DriveSyncService.checkReleaseUpdateOnly(context, prefs)
            if (drive.updateAvailable != null && drive.apkFile != null) {
                return UpdateResult(
                    message = drive.message,
                    localApk = drive.apkFile,
                    remoteInfo = drive.updateAvailable
                )
            }
            if (drive.updateBlockedReason != null) {
                return UpdateResult(drive.message)
            }
            if (drive.updateAvailable != null) {
                return UpdateResult(drive.message, remoteInfo = drive.updateAvailable)
            }
        }

        val installed = AppUpdateChecker.currentVersionCode(context)
        return UpdateResult(
            when {
                url.isBlank() -> "Up to date (v$installed). No update URL configured."
                !HeartRateState.isOnline -> "Offline — cannot check for updates."
                else -> "Up to date (v$installed)"
            }
        )
    }

    suspend fun downloadAndInstall(context: Context, info: AppUpdateChecker.UpdateInfo): Boolean {
        val apk = AppUpdateChecker.downloadApk(context, info) ?: return false
        AppUpdateChecker.launchInstall(context, apk)
        return true
    }
}
