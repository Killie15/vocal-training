package com.example.pulsebeatlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.pulsebeatlogger.theme.PulseBeatLoggerTheme

class MainActivity : ComponentActivity() {

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            HeartRateState.isOnline = true
            HeartRateState.driveSyncRequested++
        }
        override fun onLost(network: Network) {
            val cm = getSystemService(ConnectivityManager::class.java)
            HeartRateState.isOnline = cm?.activeNetworkInfo?.isConnected == true
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        HeartRateState.log("MainActivity: Permission results: allGranted=$allGranted")
        if (!allGranted) {
            HeartRateState.log("MainActivity Warning: Some permissions were denied! BLE background service may not start.")
        } else {
            HeartRateState.log("MainActivity: All requested permissions successfully granted.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeartRateState.log("MainActivity: onCreate initiated")

        enableEdgeToEdge()
        checkAndRequestPermissions()

        // Register network callback to track online/offline state
        val cm = getSystemService(ConnectivityManager::class.java)
        val initialNet = cm?.activeNetwork
        HeartRateState.isOnline = cm?.getNetworkCapabilities(initialNet)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        cm?.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )

        // Schedule daily SRS reminder notification (fires at 9am)
        SrsReminderWorker.schedule(this, hourOfDay = 9)

        setContent {
            PulseBeatLoggerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    @Deprecated("Needed for GoogleSignIn.requestPermissions scope grant callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GoogleAuthHelper.RC_GOOGLE) {
            when (GoogleAuthHelper.onScopePermissionResult(this)) {
                GoogleAuthHelper.SignInStatus.Success -> {
                    Toast.makeText(this, "Google connected — Calendar, Sheets + Drive ready", Toast.LENGTH_SHORT).show()
                    HeartRateState.driveSyncRequested++
                }
                GoogleAuthHelper.SignInStatus.Failed ->
                    Toast.makeText(
                        this,
                        GoogleAuthHelper.lastSignInError ?: "Scope grant failed",
                        Toast.LENGTH_LONG
                    ).show()
                else -> Unit
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            HeartRateState.log("MainActivity: Permissions missing: ${missing.joinToString()}. Requesting...")
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            HeartRateState.log("MainActivity: All permissions already granted. Pre-flight checks passed.")
        }
    }
}
