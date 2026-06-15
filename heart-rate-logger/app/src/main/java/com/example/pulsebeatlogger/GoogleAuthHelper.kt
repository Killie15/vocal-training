package com.example.pulsebeatlogger

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single Google Sign-In for Calendar + Sheets (one token, one sandbox).
 * Gemini API key is separate.
 */
object GoogleAuthHelper {

    const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"
    const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    const val RC_GOOGLE = 4401

    /** User-visible message from the last sign-in attempt (cleared on success). */
    var lastSignInError: String? = null
        private set

    private val ALL_SCOPES = arrayOf(
        Scope(CALENDAR_SCOPE),
        Scope(SHEETS_SCOPE),
        Scope(DRIVE_FILE_SCOPE),
        Scope(DRIVE_READONLY_SCOPE)
    )

    enum class SignInStatus { Success, NeedsScope, Failed }

    fun isDebugToken(token: String) = token.trim().lowercase() == "debug"

    fun signInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    /** Sign-in first (email), then grant Calendar + Sheets scopes — more reliable on real devices. */
    fun signInIntent(context: Context): Intent {
        val playCheck = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playCheck != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            lastSignInError = "Google Play Services unavailable (code $playCheck). Update Play Services and retry."
        }
        return signInClient(context).signInIntent
    }

    fun lastAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun hasAllScopes(account: GoogleSignInAccount): Boolean =
        GoogleSignIn.hasPermissions(account, Scope(CALENDAR_SCOPE)) &&
            GoogleSignIn.hasPermissions(account, Scope(SHEETS_SCOPE)) &&
            GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE)) &&
            GoogleSignIn.hasPermissions(account, Scope(DRIVE_READONLY_SCOPE))

    /** Connected when signed in with Calendar + Sheets scopes, or sandbox mode. */
    fun isGoogleConnected(context: Context): Boolean {
        if (HeartRateState.calendarSandboxMode) return true
        val account = lastAccount(context) ?: return false
        return hasAllScopes(account)
    }

    /** @deprecated use [isGoogleConnected] */
    fun isCalendarConnected(context: Context) = isGoogleConnected(context)

    fun accountEmail(context: Context): String? = lastAccount(context)?.email

    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        if (HeartRateState.calendarSandboxMode) return@withContext "debug"
        val account = lastAccount(context) ?: return@withContext null
        if (!hasAllScopes(account)) return@withContext null
        val acct = account.account ?: return@withContext null
        try {
            @Suppress("DEPRECATION")
            GoogleAuthUtil.getToken(
                context,
                acct,
                "oauth2:$CALENDAR_SCOPE $SHEETS_SCOPE $DRIVE_FILE_SCOPE $DRIVE_READONLY_SCOPE"
            )
        } catch (e: Exception) {
            HeartRateState.logError("Google access token failed — try signing in again", e)
            null
        }
    }

    fun handleSignInResult(context: Context, data: Intent?): SignInStatus {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            val account = task.getResult(ApiException::class.java)
            lastSignInError = null
            HeartRateState.googleAccountEmail = account.email ?: ""
            HeartRateState.calendarSandboxMode = false
            HeartRateState.log("GoogleAuth: signed in as ${account.email}")
            if (hasAllScopes(account)) {
                HeartRateState.googleAuthEvent++
                SignInStatus.Success
            } else {
                SignInStatus.NeedsScope
            }
        } catch (e: ApiException) {
            val msg = signInErrorMessage(e)
            lastSignInError = msg
            HeartRateState.logError("Google sign-in failed: $msg", e)
            SignInStatus.Failed
        } catch (e: Exception) {
            val msg = "Sign-in failed: ${e.message ?: "unknown error"}"
            lastSignInError = msg
            HeartRateState.logError("Google sign-in failed", e)
            SignInStatus.Failed
        }
    }

    /** Call from MainActivity.onActivityResult after scope permission dialog. */
    fun onScopePermissionResult(context: Context): SignInStatus {
        val account = lastAccount(context)
        return if (account != null && hasAllScopes(account)) {
            lastSignInError = null
            HeartRateState.googleAccountEmail = account.email ?: ""
            HeartRateState.calendarSandboxMode = false
            HeartRateState.googleAuthEvent++
            HeartRateState.log("GoogleAuth: Calendar + Sheets + Drive scopes granted")
            SignInStatus.Success
        } else {
            lastSignInError = "Calendar/Sheets/Drive access was not granted. Tap Connect again and accept all permissions."
            SignInStatus.Failed
        }
    }

    private fun signInErrorMessage(e: ApiException): String = when (e.statusCode) {
        10 -> "OAuth not configured for this app. In Google Cloud Console, add an Android OAuth client " +
            "for package com.example.pulsebeatlogger with your phone's SHA-1 fingerprint, and enable Calendar, Sheets, and Drive APIs."
        12501 -> "Sign-in cancelled"
        7 -> "Network error — check your connection"
        8 -> "Internal error — try again"
        else -> "Sign-in failed (code ${e.statusCode}). ${e.message ?: ""}".trim()
    }

    fun requestGoogleScopes(activity: Activity) {
        val account = lastAccount(activity) ?: return
        if (hasAllScopes(account)) return
        GoogleSignIn.requestPermissions(activity, RC_GOOGLE, account, *ALL_SCOPES)
    }

    /** @deprecated use [requestGoogleScopes] */
    fun requestCalendarScope(activity: Activity) = requestGoogleScopes(activity)

    suspend fun signOut(context: Context) {
        signInClient(context).signOut()
        HeartRateState.googleAccountEmail = ""
        HeartRateState.calendarSandboxMode = false
    }

    fun enableSandbox() {
        HeartRateState.calendarSandboxMode = true
        HeartRateState.googleAccountEmail = "sandbox@debug"
        HeartRateState.log("GoogleAuth: Google sandbox enabled (Calendar + Sheets)")
    }

    suspend fun testAll(context: Context, spreadsheetId: String, driveFolderId: String = ""): String {
        val token = getAccessToken(context) ?: return "Not connected — sign in or enable sandbox."
        val cal = GoogleCalendarService.testConnection(token)
        val sheet = GoogleSheetsService.testConnection(token, spreadsheetId)
        val folder = driveFolderId.ifBlank { HeartRateState.googleDriveFolderId }
        val drive = if (folder.isNotBlank()) {
            GoogleDriveService.testConnection(token, folder) + "\n" +
                GoogleDriveService.probeUpdateChannel(token, folder)
        } else "Drive · tap Sync now to create PulseBeat-Correct folder"
        return "$cal\n$sheet\n$drive"
    }
}
