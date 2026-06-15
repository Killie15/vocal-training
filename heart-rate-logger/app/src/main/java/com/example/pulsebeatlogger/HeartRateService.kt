package com.example.pulsebeatlogger

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.pulsebeatlogger.data.AppDatabase
import com.example.pulsebeatlogger.data.SensorEvent as SensorEventLog
import com.example.pulsebeatlogger.data.SkillSession
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HeartRateService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "HeartRateServiceChannel"
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    // Session Data
    private var isRecording = false
    private var sessionStartTime: Long = 0
    private var sessionHeartRates = mutableListOf<Int>()
    private var sessionDataPoints = JSONArray()
    private var maxHr = 0
    private var minHr = 999
    private var totalCalories = 0.0
    private var zonePeakSeconds = 0
    private var zoneCardioSeconds = 0
    private var zoneFatBurnSeconds = 0
    private var zoneWarmUpSeconds = 0
    private var lastPulseTime: Long = 0

    // Target Zone Alerts
    private var targetZone: String = "none"
    private var lastAlertTime: Long = 0
    private val alertIntervalMs: Long = 20000 // Alert at most once every 20 seconds
    private var toneGenerator: android.media.ToneGenerator? = null
    private var vibrator: android.os.Vibrator? = null

    // Sensors and Location managers
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    
    // GPS variables
    private var lastLocation: Location? = null
    private var cumulativeDistance = 0f
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0
    private var currentSpeed = 0f
    private var currentAltitude = 0.0

    // Sleep variables
    private var activeMode = "workout"
    private var sleepTimer: Timer? = null
    private var isAudioRecording = false
    private var audioRecord: AudioRecord? = null
    private var maxAudioDb = 0.0
    private var lastDbSample = 0.0
    private var maxAccelVar = 0f
    private var lastWatchUpdateTime: Long = 0
    private var isLocalSensorsRunning = false
    
    private val sampleRate = 8000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Passive voice tagger
    private val passiveTagger = PassiveTagger(sampleRate = 8000)

    // Coroutine scope for Room writes and Gemini calls
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Accelerometer throttle — only log once per second, not at raw sensor Hz
    private var lastAccelLogMs = 0L

    /**
     * Fire-and-forget: insert one timestamped sensor event row into Room.
     * Never blocks the calling thread.
     */
    private fun logSensor(type: String, valueJson: String) {
        serviceScope.launch {
            try {
                AppDatabase.getInstance(this@HeartRateService).sensorEventDao().insert(
                    SensorEventLog(
                        timestamp  = System.currentTimeMillis(),
                        sensorType = type,
                        skillName  = HeartRateState.activeSkillName,
                        sessionId  = HeartRateState.currentSessionId,
                        valueJson  = valueJson
                    )
                )
            } catch (e: Exception) {
                HeartRateState.log("⚠️ logSensor failed ($type): ${e.message}")
            }
        }
    }

    // Handlers & Executors
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var connectivityManager: ConnectivityManager

    // Settings
    private var googleSheetUrl: String = ""
    private var age: Int = 30
    private var weight: Float = 70f
    private var gender: String = "male"

    // Network Callback for Auto-Sync
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            HeartRateState.log("Network callback: Internet connection available. Triggering auto-sync check.")
            checkAndSyncSessions()
        }
    }

    override fun onCreate() {
        super.onCreate()
        HeartRateState.log("HeartRateService: onCreate triggered")
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        try {
            toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            HeartRateState.log("HeartRateService: ToneGenerator and Vibrator initialized successfully.")
        } catch (e: Exception) {
            HeartRateState.log("Warning: Failed to initialize ToneGenerator/Vibrator: ${e.message}")
        }
        
        // Register network listener to trigger uploads when phone connects to WiFi/Internet
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            HeartRateState.log("HeartRateService: Connectivity callback registered successfully.")
        } catch (e: Exception) {
            HeartRateState.logError("Failed to register ConnectivityManager callback", e)
        }
        
        loadUserSettings()
        initializeBluetooth()
        
        try {
            Wearable.getMessageClient(this).addListener(wearMessageListener)
            HeartRateState.log("Wearable: play-services-wearable listener registered.")
        } catch (e: Exception) {
            HeartRateState.log("Warning: play-services-wearable unavailable on this device: ${e.message}")
        }
    }

    private fun loadUserSettings() {
        HeartRateState.log("HeartRateService: Loading user settings from SharedPreferences...")
        try {
            val prefs = getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE)
            googleSheetUrl = prefs.getString("googleSheetUrl", "") ?: ""
            age = prefs.getInt("age", 30)
            weight = prefs.getFloat("weight", 70f)
            gender = prefs.getString("gender", "male") ?: "male"
            targetZone = prefs.getString("targetZone", "none") ?: "none"
            
            val savedMode = prefs.getString("trackingMode", "workout") ?: "workout"
            activeMode = savedMode
            HeartRateState.trackingMode = savedMode
            
            updateQueueCountState()
            
            HeartRateState.log("Loaded Settings - URL: $googleSheetUrl, Age: $age, Weight: $weight, Gender: $gender, TargetZone: $targetZone, Mode: $activeMode")
        } catch (e: Exception) {
            HeartRateState.logError("Error loading user settings", e)
        }
    }

    private fun initializeBluetooth() {
        HeartRateState.log("HeartRateService: Initializing Bluetooth adapter...")
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            HeartRateState.logError("Bluetooth adapter is null! Bluetooth not supported on this device.")
            stopSelf()
            return
        }
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            HeartRateState.log("BluetoothLeScanner is null. Bluetooth is likely turned off.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunerMicCoordinator.ACTION_HOLD_MIC -> {
                HeartRateState.log("HeartRateService: Tuner requested mic — releasing AudioRecord")
                stopAudioMonitoring()
                return START_STICKY
            }
            TunerMicCoordinator.ACTION_RELEASE_MIC -> {
                HeartRateState.log("HeartRateService: Tuner released mic")
                if (HeartRateState.isServiceRunning &&
                    (activeMode == "japanese" || activeMode == "ukulele") &&
                    !HeartRateState.tunerMicActive
                ) {
                    startAudioMonitoring()
                }
                return START_STICKY
            }
        }

        HeartRateState.log("HeartRateService: onStartCommand invoked")
        
        if (intent != null && intent.action == "UPDATE_MODE") {
            val newMode = intent.getStringExtra("mode") ?: "workout"
            updateServiceMode(newMode)
            return START_STICKY
        }

        // 1. Create Notification Channel
        createNotificationChannel()
        
        // 2. Build Foreground Notification
        val notification = buildServiceNotification("Scanning for heart rate sensor...")
        
        // 3. Start as Foreground Service
        HeartRateState.log("HeartRateService: Starting foreground...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        HeartRateState.isServiceRunning = true
        
        // Reload settings in case they changed before starting
        loadUserSettings()
        
        // Load activeMode
        val prefs = getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE)
        activeMode = prefs.getString("trackingMode", "workout") ?: "workout"
        HeartRateState.trackingMode = activeMode
        HeartRateState.log("HeartRateService: Initializing in mode: $activeMode")

        if (HeartRateState.debugMode) {
            // ── Debug / Sandbox path ──────────────────────────────────────────
            // Skip all real hardware. The DebugSimulator drives HeartRateState fields
            // and bumps debugInjectBpmTick; we process each tick through processLiveBpm
            // so session recording still works end-to-end.
            HeartRateState.log("🔬 Debug mode active — skipping real BLE/GPS/mic. Watching debugInjectBpmTick.")
            HeartRateState.connectionState = "Connected (Debug)"
            HeartRateState.deviceName = "Debug HR Sensor"
            startSessionRecording()
            startDebugBpmObserver()
        } else {
            // ── Normal production path ────────────────────────────────────────
            startBleScan()
            if (activeMode == "sleep") {
                isLocalSensorsRunning = true
                startSleepTimer()
                startAudioMonitoring()
                startMotionMonitoring()
            } else {
                if (activeMode == "running" || activeMode == "workout") {
                    startGpsTracking()
                }
                if (activeMode == "japanese" || activeMode == "ukulele") {
                    startAudioMonitoring()
                    isLocalSensorsRunning = true
                }
            }
        }

        return START_STICKY
    }

    // ── Debug BPM Observer ────────────────────────────────────────────────────

    private var lastDebugTick = 0
    private var lastDebugEndTick = 0

    /**
     * Polls [HeartRateState.debugInjectBpmTick] every 200 ms.
     * When the BPM counter advances, feeds the value through [processLiveBpm] so
     * session recording accumulates data exactly as it would with a real BLE strap.
     * When [HeartRateState.debugEndSessionTick] advances, calls [compileAndSaveSession]
     * so the simulated session actually persists to Room.
     */
    private fun startDebugBpmObserver() {
        HeartRateState.log("🔬 Debug: BPM observer started.")
        serviceScope.launch {
            while (HeartRateState.isServiceRunning && HeartRateState.debugMode) {
                val bpmTick = HeartRateState.debugInjectBpmTick
                if (bpmTick != lastDebugTick) {
                    lastDebugTick = bpmTick
                    val bpm = HeartRateState.currentBpm
                    if (bpm > 0) processLiveBpm(bpm)
                }

                val endTick = HeartRateState.debugEndSessionTick
                if (endTick != lastDebugEndTick) {
                    lastDebugEndTick = endTick
                    HeartRateState.log("🔬 Debug: End-session signal received — compiling session.")
                    if (isRecording) compileAndSaveSession()
                    // Re-arm for the next simulated session
                    startSessionRecording()
                }

                delay(200)
            }
            HeartRateState.log("🔬 Debug: BPM observer stopped.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            HeartRateState.log("HeartRateService: Creating notification channel...")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Heart Rate Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors heart rate strap in the background and logs sessions."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(contentText: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PulseBeat Background Sync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Simple default icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildServiceNotification(text))
        } catch (e: Exception) {
            HeartRateState.logError("Failed to update notification", e)
        }
    }

    // BLE Scanning
    private fun startBleScan() {
        if (isScanning) {
            HeartRateState.log("startBleScan: Already scanning. Skipping.")
            return
        }

        // Verify permissions
        val hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasScan || !hasConnect || !hasLocation) {
            HeartRateState.logError("startBleScan: Missing required Bluetooth permissions! Cannot scan.")
            HeartRateState.connectionState = "Missing Permissions"
            updateNotification("Error: Missing Bluetooth Permissions")
            return
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        }

        if (bluetoothLeScanner == null) {
            HeartRateState.logError("startBleScan: Bluetooth scanner is null. Is Bluetooth disabled?")
            HeartRateState.connectionState = "Bluetooth Disabled"
            updateNotification("Bluetooth is off. Turn it on to connect.")
            return
        }

        HeartRateState.log("startBleScan: Scanning initiated...")
        HeartRateState.connectionState = "Scanning"
        updateNotification("Scanning for heart rate sensor...")

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            HeartRateState.log("startBleScan: startScan called successfully.")
        } catch (e: Exception) {
            isScanning = false
            HeartRateState.logError("Exception in startBleScan", e)
        }
    }

    private fun stopBleScan() {
        if (!isScanning) return
        HeartRateState.log("stopBleScan: Stopping scan...")
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            HeartRateState.log("stopBleScan: Scan stopped.")
        } catch (e: SecurityException) {
            HeartRateState.logError("SecurityException stopping scan", e)
        } catch (e: Exception) {
            HeartRateState.logError("Exception stopping scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                HeartRateState.log("ScanResult: Found matching sensor: ${device.name ?: "Unknown"} [${device.address}]")
                stopBleScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning = false
            HeartRateState.logError("BLE scan failed with error code: $errorCode")
            HeartRateState.connectionState = "Scan Failed ($errorCode)"
            handler.postDelayed({ startBleScan() }, 10000) // Retry scan in 10s
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        handler.post {
            HeartRateState.log("connectToDevice: Attempting connection to ${device.name ?: "Device"} (${device.address}) on main thread")
            HeartRateState.connectionState = "Connecting"
            updateNotification("Connecting to ${device.name ?: "sensor"}...")
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    bluetoothGatt = device.connectGatt(this@HeartRateService, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    bluetoothGatt = device.connectGatt(this@HeartRateService, false, gattCallback)
                }
                HeartRateState.log("connectToDevice: connectGatt triggered successfully.")
            } catch (e: SecurityException) {
                HeartRateState.logError("SecurityException during connectGatt", e)
            } catch (e: Exception) {
                HeartRateState.logError("Exception during connectGatt", e)
            }
        }
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            HeartRateState.log("GATT: onConnectionStateChange status=$status, newState=$newState")
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                HeartRateState.logError("GATT connection failed or disconnected with status=$status. Cleaning up connection.")
                gatt.close()
                bluetoothGatt = null
                handleDisconnection()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                HeartRateState.log("GATT: Connected to device server. Initiating service discovery...")
                HeartRateState.connectionState = "Connected"
                try {
                    HeartRateState.deviceName = gatt.device.name ?: "Heart Rate Sensor"
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    HeartRateState.logError("SecurityException discovering services", e)
                }
                logSensor("DEVICE_CONNECT", """{"deviceName":"${HeartRateState.deviceName}","deviceAddress":"${gatt.device.address}","type":"BLE_HR"}""")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                HeartRateState.log("GATT: Disconnected from device.")
                logSensor("DEVICE_DISCONNECT", """{"deviceName":"${HeartRateState.deviceName}","deviceAddress":"${gatt.device.address}","type":"BLE_HR"}""")
                gatt.close()
                bluetoothGatt = null
                handleDisconnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            HeartRateState.log("GATT: onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val hrService = gatt.getService(HEART_RATE_SERVICE_UUID)
                if (hrService != null) {
                    val hrChar = hrService.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
                    if (hrChar != null) {
                        enableCharacteristicNotifications(gatt, hrChar)
                    } else {
                        HeartRateState.logError("GATT: Heart Rate measurement characteristic not found!")
                    }
                } else {
                    HeartRateState.logError("GATT: Heart Rate service not found on device!")
                }
            } else {
                HeartRateState.logError("GATT: Services discovery failed with status $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (HEART_RATE_MEASUREMENT_CHAR_UUID == characteristic.uuid) {
                parseHeartRateCharacteristic(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Android 13+ support
            if (HEART_RATE_MEASUREMENT_CHAR_UUID == characteristic.uuid) {
                parseHeartRateByteArray(value)
            }
        }
    }

    private fun enableCharacteristicNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        HeartRateState.log("GATT: Enabling notifications for characteristic ${characteristic.uuid}...")
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                HeartRateState.log("GATT: Notifications enabled successfully.")
                
                // Start a fresh session if in workout mode
                if (activeMode == "workout") {
                    startSessionRecording()
                }
            } else {
                HeartRateState.logError("GATT: CCCD descriptor not found on characteristic!")
            }
        } catch (e: SecurityException) {
            HeartRateState.logError("SecurityException enabling notifications", e)
        } catch (e: Exception) {
            HeartRateState.logError("Exception enabling notifications", e)
        }
    }

    // Parsing BLE heart rate packets
    private fun parseHeartRateCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val value = characteristic.value ?: return
        parseHeartRateByteArray(value)
    }

    private fun parseHeartRateByteArray(value: ByteArray) {
        if (value.isEmpty()) return
        
        val flags = value[0].toInt()
        val rate16Bits = (flags and 0x1) != 0
        var offset = 1
        val heartRate = if (rate16Bits) {
            // uint16
            val low = value[offset].toInt() and 0xFF
            val high = value[offset + 1].toInt() and 0xFF
            offset += 2
            (high shl 8) or low
        } else {
            // uint8
            val hr = value[offset].toInt() and 0xFF
            offset += 1
            hr
        }

        // Check for Battery level service if battery is not retrieved yet
        // (Optional: standard CooSpo includes battery level in notify flag or separate GATT reads)
        
        processLiveBpm(heartRate)
    }

    private var bpmTickCount = 0   // counts every BPM processed for selective logging

    private fun processLiveBpm(bpm: Int) {
        if (bpm <= 0) {
            HeartRateState.log("processLiveBpm: skipped — bpm=$bpm (invalid)")
            return
        }

        bpmTickCount++
        lastPulseTime = System.currentTimeMillis()
        HeartRateState.currentBpm = bpm
        updateNotification("Pulse: $bpm BPM")
        logSensor("BLE_HR", """{"bpm":$bpm,"device":"${HeartRateState.deviceName}"}""")

        // Log every 10th BPM to keep logs readable without missing data
        if (bpmTickCount % 10 == 0) {
            HeartRateState.log("BPM tick #$bpmTickCount: $bpm bpm | recording=$isRecording | " +
                "samples=${sessionHeartRates.size} | maxHr=$maxHr | cal=${"%.1f".format(totalCalories)} kcal")
        }

        if (isRecording) {
            sessionHeartRates.add(bpm)
            maxHr = Math.max(maxHr, bpm)
            minHr = Math.min(minHr, bpm)
            
            // Log timestamped point (offset in seconds)
            val elapsedSec = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
            val point = JSONObject().apply {
                put("timeOffset", elapsedSec)
                put("hr", bpm)
                if (activeMode == "workout") {
                    put("speed", Math.round(currentSpeed * 10.0) / 10.0)
                    put("dist", Math.round(cumulativeDistance * 10.0) / 10.0)
                    put("alt", Math.round(currentAltitude * 10.0) / 10.0)
                    put("lat", currentLatitude)
                    put("lon", currentLongitude)
                }
            }
            sessionDataPoints.put(point)

            // Compute dynamic calorie burn per second (Keytel formula / 60)
            val kcalMin = if (gender == "male") {
                (-55.0969 + (0.6309 * bpm) + (0.1988 * weight) + (0.2017 * age)) / 4.184
            } else {
                (-20.4022 + (0.4472 * bpm) - (0.1263 * weight) + (0.0740 * age)) / 4.184
            }
            totalCalories += Math.max(0.0, kcalMin / 60.0)

            // Accumulate zones
            val maxAllowed = 220 - age
            when {
                bpm >= maxAllowed * 0.85 -> zonePeakSeconds++
                bpm >= maxAllowed * 0.70 -> zoneCardioSeconds++
                bpm >= maxAllowed * 0.50 -> zoneFatBurnSeconds++
                else -> zoneWarmUpSeconds++
            }
            
            // Target Zone Alerts Check
            if (targetZone != "none") {
                if (targetZone == "meditation") {
                    val limit = 75 // Relaxed heart rate ceiling for meditation practice
                    val now = System.currentTimeMillis()
                    if (now - lastAlertTime > alertIntervalMs) {
                        if (bpm > limit) {
                            lastAlertTime = now
                            HeartRateState.log("Meditation Alert: Heart rate $bpm BPM exceeds relaxation limit ($limit BPM). Focus on slow, deep breathing.")
                            try {
                                toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 250)
                                val pattern = longArrayOf(0, 400)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                                } else {
                                    vibrator?.vibrate(pattern, -1)
                                }
                            } catch (e: Exception) {
                                HeartRateState.log("Warning: Meditation alert sound/vibration feedback failed: ${e.message}")
                            }
                        }
                    }
                } else {
                    var minLimit = 0
                    var maxLimit = 300
                    
                    when (targetZone) {
                        "zone1" -> { // Warm Up / Light (50% - 60% of MHR)
                            minLimit = (maxAllowed * 0.50).toInt()
                            maxLimit = (maxAllowed * 0.60).toInt()
                        }
                        "zone2" -> { // Fat Burn (60% - 70% of MHR)
                            minLimit = (maxAllowed * 0.60).toInt()
                            maxLimit = (maxAllowed * 0.70).toInt()
                        }
                        "zone3" -> { // Cardio (70% - 80% of MHR)
                            minLimit = (maxAllowed * 0.70).toInt()
                            maxLimit = (maxAllowed * 0.80).toInt()
                        }
                        "zone4" -> { // Threshold (80% - 90% of MHR)
                            minLimit = (maxAllowed * 0.80).toInt()
                            maxLimit = (maxAllowed * 0.90).toInt()
                        }
                        "zone5" -> { // Peak (90% - 100% of MHR)
                            minLimit = (maxAllowed * 0.90).toInt()
                            maxLimit = maxAllowed
                        }
                    }
                    
                    val now = System.currentTimeMillis()
                    if (now - lastAlertTime > alertIntervalMs) {
                        if (bpm > maxLimit) {
                            // Too high: beep once, vibrate once
                            lastAlertTime = now
                            HeartRateState.log("Alert: Heart rate $bpm BPM is above target $targetZone max ($maxLimit BPM). Nudging to slow down.")
                            try {
                                toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 250)
                                val pattern = longArrayOf(0, 400)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                                } else {
                                    vibrator?.vibrate(pattern, -1)
                                }
                            } catch (e: Exception) {
                                HeartRateState.log("Warning: High alert sound/vibration feedback failed: ${e.message}")
                            }
                        } else if (bpm < minLimit) {
                            // Too low: beep twice, vibrate twice
                            lastAlertTime = now
                            HeartRateState.log("Alert: Heart rate $bpm BPM is below target $targetZone min ($minLimit BPM). Nudging to speed up.")
                            try {
                                toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 350)
                                val pattern = longArrayOf(0, 200, 150, 200)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                                } else {
                                    vibrator?.vibrate(pattern, -1)
                                }
                            } catch (e: Exception) {
                                HeartRateState.log("Warning: Low alert sound/vibration feedback failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startSessionRecording() {
        val sessionId = "session_${System.currentTimeMillis()}"
        HeartRateState.log("Recording: ── START SESSION ── id=$sessionId mode=$activeMode")
        isRecording = true
        sessionStartTime = System.currentTimeMillis()
        sessionHeartRates.clear()
        sessionDataPoints = JSONArray()
        maxHr = 0
        minHr = 999
        totalCalories = 0.0
        zonePeakSeconds = 0
        zoneCardioSeconds = 0
        zoneFatBurnSeconds = 0
        zoneWarmUpSeconds = 0
        bpmTickCount = 0
        passiveTagger.reset()
        handler.post {
            HeartRateState.currentSessionId = sessionId
            HeartRateState.log("Recording: sessionId=$sessionId set on UI thread")
        }
        logSensor("SESSION_START", """{"sessionId":"$sessionId","mode":"$activeMode","skill":"${HeartRateState.activeSkillName}"}""")
    }

    private fun handleDisconnection() {
        HeartRateState.connectionState = "Disconnected"
        HeartRateState.currentBpm = 0
        HeartRateState.deviceName = "None"
        
        if (isRecording) {
            compileAndSaveSession()
        }
        
        // Restart scanning to connect automatically next time the user puts the sensor on
        startBleScan()
    }

    private fun compileAndSaveSession() {
        isRecording = false
        val endTime = System.currentTimeMillis()
        val durationSec = ((endTime - sessionStartTime) / 1000).toInt()

        logSensor("SESSION_END", """{"sessionId":"${HeartRateState.currentSessionId}","durationSec":$durationSec,"skill":"${HeartRateState.activeSkillName}"}""")
        HeartRateState.log("Recording: ── COMPILE SESSION ── duration=${durationSec}s " +
            "samples=${sessionHeartRates.size} dataPoints=${sessionDataPoints.length()} mode=$activeMode")

        if (sessionHeartRates.isEmpty() || durationSec < 10) {
            HeartRateState.log("Recording: ⚠️ Session discarded — too short (${durationSec}s) or no HR readings (${sessionHeartRates.size} samples). Min 10s required.")
            return
        }

        val avgHr = sessionHeartRates.average().toInt()
        val minRecord = if (minHr == 999) 0 else minHr

        HeartRateState.log("Recording: HR stats — avg=$avgHr max=$maxHr min=$minRecord samples=${sessionHeartRates.size}")
        HeartRateState.log("Recording: Zone breakdown — peak=${zonePeakSeconds}s cardio=${zoneCardioSeconds}s fatBurn=${zoneFatBurnSeconds}s warmUp=${zoneWarmUpSeconds}s")
        HeartRateState.log("Recording: Calories=${"%.1f".format(totalCalories)} kcal | Distance=${HeartRateState.gpsDistance} m")
        
        val dateString = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(sessionStartTime))
        val endDateString = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(endTime))

        try {
            val sessionId = HeartRateState.currentSessionId.ifBlank { "local_android_${System.currentTimeMillis()}" }

            // Build passive voice tags
            val voiceSnapshot = passiveTagger.buildSnapshot()
            val passiveTagsJson = voiceSnapshot?.toJson() ?: "{}"
            handler.post {
                if (voiceSnapshot != null) HeartRateState.passiveVoiceProfile = voiceSnapshot
            }

            // Write to Room database
            val roomSession = SkillSession(
                id = sessionId,
                skillName = activeMode,
                startTime = sessionStartTime,
                endTime = endTime,
                durationSeconds = durationSec,
                avgHr = avgHr.toFloat(),
                maxHr = maxHr.toFloat(),
                minHr = minRecord.toFloat(),
                hrDataPoints = sessionDataPoints.toString(),
                avgPitchHz = voiceSnapshot?.medianF0 ?: 0f,
                pitchMinHz = voiceSnapshot?.minHz ?: 0f,
                pitchMaxHz = voiceSnapshot?.maxHz ?: 0f,
                speakingRegister = voiceSnapshot?.register ?: "",
                calories = Math.round(totalCalories).toFloat(),
                distance = HeartRateState.gpsDistance.toFloat(),
                passiveTags = passiveTagsJson,
                syncedToGoogle = false
            )
            HeartRateState.log("Room: Preparing to insert session $sessionId — avgHr=$avgHr avgPitch=${voiceSnapshot?.medianF0 ?: 0f} Hz")
            serviceScope.launch {
                try {
                    AppDatabase.getInstance(this@HeartRateService).skillSessionDao().insert(roomSession)
                    HeartRateState.log("Room: ✅ Session $sessionId saved to database. Skill=${roomSession.skillName} dur=${roomSession.durationSeconds}s")
                    // Award session XP and check streak achievements
                    val prefs = getSharedPreferences("PulseBeatLoggerPrefs", android.content.Context.MODE_PRIVATE)
                    GamificationHelper.onSessionCompleted(prefs)
                } catch (e: Exception) {
                    HeartRateState.logError("Room: ❌ Failed to save session $sessionId", e)
                }
            }

            // Also save to legacy SharedPreferences queue for Google Sheets sync
            val sessionRecord = JSONObject().apply {
                put("id", sessionId)
                put("type", activeMode)
                put("start_time", dateString)
                put("end_time", endDateString)
                put("duration_seconds", durationSec)
                put("average_hr", avgHr)
                put("max_hr", maxHr)
                put("min_hr", minRecord)
                put("calories", Math.round(totalCalories).toInt())
                put("distance", Math.round(HeartRateState.gpsDistance * 10.0) / 10.0)
                put("age", age)
                put("weight", weight)
                put("gender", gender)
                put("data_points", sessionDataPoints)
                put("passive_tags", passiveTagsJson)
                put("synced_to_server", false)
                put("synced_to_google", false)
            }

            HeartRateState.log("Recording: Writing session to SharedPreferences offline queue...")
            saveSessionToOfflineQueue(sessionRecord)
            HeartRateState.log("Recording: SharedPreferences queue write complete")

            // Persist long-term stats
            val prefs = getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE)
            val currentWorkouts = prefs.getInt("stats_total_workouts", 0) + 1
            val currentDist = prefs.getFloat("stats_total_distance", 0f) + HeartRateState.gpsDistance.toFloat()
            val currentMastery = prefs.getInt("mastery_$activeMode", 0)
            val newMastery = Math.min(100, currentMastery + 5)
            HeartRateState.log("Recording: Stats update — totalWorkouts=$currentWorkouts totalDist=${"%.0f".format(currentDist)} m mastery $activeMode: $currentMastery → $newMastery")
            
            prefs.edit().apply {
                putInt("stats_total_workouts", currentWorkouts)
                putFloat("stats_total_distance", currentDist)
                putInt("mastery_$activeMode", newMastery)
                apply()
            }
            handler.post {
                HeartRateState.skillsMastery[activeMode] = newMastery
            }
            
            // Force sync immediately
            checkAndSyncSessions()
        } catch (e: Exception) {
            HeartRateState.logError("Failed to compile and save session", e)
        }
    }

    private fun saveSessionToOfflineQueue(record: JSONObject) {
        try {
            val prefs = getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE)
            val queueString = prefs.getString("offline_queue", "[]") ?: "[]"
            val queue = JSONArray(queueString)
            queue.put(record)
            
            prefs.edit().putString("offline_queue", queue.toString()).apply()
            HeartRateState.log("Session saved in storage. Queue size: ${queue.length()}")
            updateQueueCountState()
        } catch (e: Exception) {
            HeartRateState.logError("Error saving session to local queue", e)
        }
    }

    private fun updateQueueCountState() {
        val prefs = getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE)
        val queueString = prefs.getString("offline_queue", "[]") ?: "[]"
        try {
            val queue = JSONArray(queueString)
            HeartRateState.unsyncedSessionCount = queue.length()
        } catch (e: Exception) {
            HeartRateState.unsyncedSessionCount = 0
        }
    }

    // Google Sheets WiFi synchronization
    private fun checkAndSyncSessions() {
        loadUserSettings() // Make sure we have latest Sheets URL
        
        if (googleSheetUrl.isEmpty()) {
            HeartRateState.log("Sync: Google Sheets URL is not configured. Sync skipped.")
            HeartRateState.lastSyncStatus = "No Sheet URL configured"
            return
        }

        // Check if WiFi connected (using network transport helper)
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!hasInternet || (!isWifi && !isCellular)) {
            HeartRateState.log("Sync: No internet connection available. Skipping upload.")
            HeartRateState.lastSyncStatus = "Offline"
            return
        }

        HeartRateState.log("Sync: Online connection confirmed. Launching sync background task...")
        HeartRateState.lastSyncStatus = "Syncing..."
        
        executor.execute {
            syncQueueTask()
        }
    }

    private fun syncQueueTask() {
        HeartRateState.log("SyncTask: Fetching offline queue from storage...")
        val prefs = getSharedPreferences("PulseBeatLoggerPrefs", Context.MODE_PRIVATE)
        val queueString = prefs.getString("offline_queue", "[]") ?: "[]"
        
        var queue = JSONArray()
        try {
            queue = JSONArray(queueString)
        } catch (e: Exception) {
            HeartRateState.logError("SyncTask: Failed to parse local queue JSON", e)
            return
        }

        if (queue.length() == 0) {
            HeartRateState.log("SyncTask: Queue is empty. Nothing to sync.")
            handler.post {
                HeartRateState.lastSyncStatus = "Up to date"
                updateQueueCountState()
            }
            return
        }

        HeartRateState.log("SyncTask: Found ${queue.length()} unsynced sessions.")
        val remainingQueue = JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until queue.length()) {
            val session = queue.getJSONObject(i)
            val id = session.getString("id")
            
            // Clean control variables from record payload to keep Google Sheets clean
            val payload = JSONObject(session.toString()).apply {
                remove("id")
                remove("synced_to_server")
                remove("synced_to_google")
            }

            HeartRateState.log("SyncTask: Uploading session $id...")
            val success = postSessionToGoogleSheet(googleSheetUrl, payload.toString())
            
            if (success) {
                successCount++
                HeartRateState.log("SyncTask: Session $id successfully synced to Google Sheet!")
            } else {
                failCount++
                HeartRateState.logError("SyncTask: Failed to sync session $id. Keeping in queue.")
                remainingQueue.put(session)
            }
        }

        // Save updated queue back to SharedPreferences
        prefs.edit().putString("offline_queue", remainingQueue.toString()).apply()
        
        handler.post {
            HeartRateState.log("SyncTask: Finished. Synced: $successCount, Failed: $failCount, Remaining: ${remainingQueue.length()}")
            HeartRateState.lastSyncStatus = "Sync: $successCount Succeeded, $failCount Failed"
            updateQueueCountState()
        }
    }

    private fun postSessionToGoogleSheet(urlStr: String, jsonString: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true
            
            // Sending as text/plain bypasses CORS checks on Google Apps Script and works 100%
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            
            val outputStream: OutputStream = connection.outputStream
            outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            HeartRateState.log("GSheets POST: Response Code = $responseCode")
            
            // Google Sheets Apps Script Redirects to googleusercontent.com (HTTP 302)
            // HttpURLConnection automatically follows redirects for GET, but NOT always for POST.
            // However, Apps Script returns 200/302. If responseCode is 200 or 302, it succeeded.
            responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
        } catch (e: Exception) {
            HeartRateState.logError("GSheets POST failed due to exception", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    // GPS & Motion Listeners and Helper Methods
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (activeMode != "workout") return
            
            val lastLoc = lastLocation
            if (lastLoc != null) {
                val dist = lastLoc.distanceTo(location)
                if (dist > 0.5f) { // filter noise
                    cumulativeDistance += dist
                }
            }
            lastLocation = location
            
            currentLatitude = location.latitude
            currentLongitude = location.longitude
            currentAltitude = location.altitude
            currentSpeed = location.speed * 3.6f // convert m/s to km/h
            
            HeartRateState.gpsSpeed = Math.round(currentSpeed * 10.0) / 10.0
            HeartRateState.gpsDistance = Math.round(cumulativeDistance * 10.0) / 10.0
            HeartRateState.gpsAltitude = Math.round(currentAltitude * 10.0) / 10.0
            
            HeartRateState.log("GPS Location update: Lat=$currentLatitude, Lon=$currentLongitude, Speed=${HeartRateState.gpsSpeed} km/h, Dist=${HeartRateState.gpsDistance} m")
            logSensor("GPS", """{"lat":$currentLatitude,"lon":$currentLongitude,"speedKmh":${HeartRateState.gpsSpeed},"altM":${HeartRateState.gpsAltitude},"accuracyM":${location.accuracy},"distanceM":${HeartRateState.gpsDistance}}""")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || activeMode != "sleep") return
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val mag = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val deviation = Math.abs(mag - 9.80665f)

                synchronized(this@HeartRateService) {
                    if (deviation > maxAccelVar) maxAccelVar = deviation
                }

                // Throttle DB writes to ~1 per second to avoid flooding the table
                val now = System.currentTimeMillis()
                if (now - lastAccelLogMs >= 1000) {
                    lastAccelLogMs = now
                    val mag2dp = String.format("%.2f", mag)
                    logSensor("ACCELEROMETER", """{"x":${x.toBigDecimal().toPlainString()},"y":${y.toBigDecimal().toPlainString()},"z":${z.toBigDecimal().toPlainString()},"magnitude":$mag2dp}""")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startGpsTracking() {
        HeartRateState.log("GPS: Starting location tracking...")
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            if (!hasFine && !hasCoarse) {
                HeartRateState.log("Warning: Location permissions are not granted. GPS tracking is disabled.")
                return
            }
            
            // Reset GPS metrics
            lastLocation = null
            cumulativeDistance = 0f
            currentLatitude = 0.0
            currentLongitude = 0.0
            currentSpeed = 0f
            currentAltitude = 0.0
            
            HeartRateState.gpsSpeed = 0.0
            HeartRateState.gpsDistance = 0.0
            HeartRateState.gpsAltitude = 0.0
            
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            
            locationManager.requestLocationUpdates(
                provider,
                1000L, // 1 second
                1f,    // 1 meter
                locationListener,
                Looper.getMainLooper()
            )
            HeartRateState.log("GPS: Registered location listener using $provider.")
        } catch (e: Exception) {
            HeartRateState.logError("Failed to start GPS tracking", e)
        }
    }

    private fun stopGpsTracking() {
        HeartRateState.log("GPS: Stopping location tracking...")
        try {
            if (::locationManager.isInitialized) {
                locationManager.removeUpdates(locationListener)
            }
        } catch (e: Exception) {
            HeartRateState.logError("Failed to stop GPS tracking", e)
        }
    }

    private fun startMotionMonitoring() {
        HeartRateState.log("Motion: Starting accelerometer monitoring...")
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            if (accelerometer != null) {
                sensorManager.registerListener(
                    sensorListener,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                HeartRateState.log("Motion: Accelerometer listener registered successfully.")
            } else {
                HeartRateState.log("Warning: Accelerometer sensor not available.")
            }
        } catch (e: Exception) {
            HeartRateState.logError("Failed to start accelerometer monitoring", e)
        }
    }

    private fun stopMotionMonitoring() {
        HeartRateState.log("Motion: Stopping accelerometer monitoring...")
        try {
            if (::sensorManager.isInitialized) {
                sensorManager.unregisterListener(sensorListener)
            }
        } catch (e: Exception) {
            HeartRateState.logError("Failed to stop accelerometer monitoring", e)
        }
    }

    private fun startAudioMonitoring() {
        if (isAudioRecording) return
        
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            HeartRateState.log("Warning: RECORD_AUDIO permission not granted. Sleep decibels will be logged as 0.")
            return
        }
        
        isAudioRecording = true
        maxAudioDb = 0.0
        lastDbSample = 0.0
        
        executor.execute {
            try {
                HeartRateState.log("AudioRecord: Initializing bufferSize=$bufferSize...")
                val localRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                audioRecord = localRecord
                
                if (localRecord.state != AudioRecord.STATE_INITIALIZED) {
                    HeartRateState.logError("AudioRecord could not be initialized. State is uninitialized.")
                    isAudioRecording = false
                    return@execute
                }
                
                localRecord.startRecording()
                HeartRateState.log("AudioRecord: Ambient sound monitoring started.")
                
                val buffer = ShortArray(bufferSize)
                while (isAudioRecording && HeartRateState.isServiceRunning) {
                    if (HeartRateState.tunerMicActive) {
                        HeartRateState.log("AudioRecord: Pausing — tuner has mic")
                        break
                    }
                    val read = localRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = Math.sqrt(sum / read)
                        
                        // Max value of short RMS is 32767. 20 * log10(32767) = 90.3 dB.
                        val db = if (rms > 0.1) 20 * Math.log10(rms) else 0.0
                        
                        synchronized(this@HeartRateService) {
                            if (db > maxAudioDb) maxAudioDb = db
                            lastDbSample = db
                        }

                        // Feed to passive voice tagger (runs on every frame, samples internally)
                        passiveTagger.addFrame(buffer, read)

                        if (activeMode == "ukulele" || activeMode == "japanese") {
                            val hz = passiveTagger.lastPitchHz()
                            handler.post {
                                HeartRateState.sleepSoundDb = Math.round(db * 10.0) / 10.0
                                if (hz > 50f && !HeartRateState.tunerMicActive) {
                                    val (note, oct, cents) = hzToNoteInfo(hz)
                                    HeartRateState.livePitchHz = hz
                                    HeartRateState.livePitchNote = note
                                    HeartRateState.livePitchCents = cents
                                } else if (hz <= 0f) {
                                    HeartRateState.livePitchHz = 0f
                                    HeartRateState.livePitchNote = "--"
                                    HeartRateState.livePitchCents = 0
                                }
                            }
                        }

                        // Log mic sample at ~2 Hz (every ~4 frames × 150ms = ~600ms)
                        if (db > -80) {
                            val dbRounded = String.format("%.1f", db)
                            val pitchHz = passiveTagger.lastPitchHz()
                            val pitchPart = if (pitchHz > 0) ""","hz":$pitchHz""" else ""
                            logSensor("MICROPHONE", """{"db":$dbRounded$pitchPart}""")
                        }
                    }
                    Thread.sleep(150)
                }
                
                try {
                    localRecord.stop()
                } catch (e: Exception) {
                    // Ignore if already stopped
                }
                localRecord.release()
                audioRecord = null
                HeartRateState.log("AudioRecord: Ambient sound monitoring stopped.")
            } catch (e: Exception) {
                HeartRateState.logError("Error in audio record thread", e)
                isAudioRecording = false
            }
        }
    }

    private fun stopAudioMonitoring() {
        HeartRateState.log("AudioRecord: Requesting stop...")
        isAudioRecording = false
    }

    private fun startSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = Timer()
        sessionStartTime = System.currentTimeMillis()
        sessionDataPoints = JSONArray()
        maxAudioDb = 0.0
        maxAccelVar = 0f
        HeartRateState.log("Sleep Mode: Timer started. Logging every 10 seconds.")
        
        sleepTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    if (activeMode != "sleep" || !HeartRateState.isServiceRunning) {
                        cancel()
                        return
                    }
                    
                    val elapsedSec = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
                    
                    // Get latest heart rate from BLE sensor
                    val now = System.currentTimeMillis()
                    val isWatchActive = (now - lastWatchUpdateTime) < 30000
                    
                    handler.post {
                        HeartRateState.isWatchConnected = isWatchActive
                    }

                    val hr = if (now - lastPulseTime < 15000) HeartRateState.currentBpm else 0
                    
                    val db: Double
                    val motion: Double
                    
                    if (isWatchActive) {
                        // Watch On Protocol: read values stored directly from watch messages
                        db = HeartRateState.sleepSoundDb
                        motion = HeartRateState.sleepMotionMag
                        
                        // Turn off local phone sensors if they are running to save battery
                        if (isLocalSensorsRunning) {
                            HeartRateState.log("WearOS: Watch detected. Pausing phone's local microphone and accelerometer to save battery.")
                            stopAudioMonitoring()
                            stopMotionMonitoring()
                            isLocalSensorsRunning = false
                        }
                    } else {
                        // Watch Off Protocol: use phone's sensors
                        var localDb = 0.0
                        var localMotion = 0f
                        synchronized(this@HeartRateService) {
                            localDb = maxAudioDb
                            localMotion = maxAccelVar
                            // Reset interval peaks
                            maxAudioDb = lastDbSample
                            maxAccelVar = 0f
                        }
                        db = localDb
                        motion = localMotion.toDouble()
                        
                        // Turn on local phone sensors if they are not running
                        if (!isLocalSensorsRunning) {
                            HeartRateState.log("WearOS: Watch disconnected or inactive. Resuming phone's local microphone and accelerometer.")
                            startAudioMonitoring()
                            startMotionMonitoring()
                            isLocalSensorsRunning = true
                        }
                        
                        handler.post {
                            HeartRateState.sleepSoundDb = Math.round(db * 10.0) / 10.0
                            HeartRateState.sleepMotionMag = Math.round(motion * 100.0) / 100.0
                        }
                    }
                    
                    val point = JSONObject().apply {
                        put("timeOffset", elapsedSec)
                        put("hr", hr)
                        put("db", Math.round(db * 10.0) / 10.0)
                        put("motion", Math.round(motion * 100.0) / 100.0)
                    }
                    
                    sessionDataPoints.put(point)
                    HeartRateState.log("Sleep Data Point [${elapsedSec}s]: HR=$hr, Sound=${String.format(Locale.US, "%.1f", db)} dB, Motion=${String.format(Locale.US, "%.2f", motion)}")
                } catch (e: Exception) {
                    HeartRateState.logError("Exception in sleep timer tick", e)
                }
            }
        }, 10000L, 10000L)
    }

    private fun stopSleepTimer() {
        HeartRateState.log("Sleep Mode: Stopping timer.")
        sleepTimer?.cancel()
        sleepTimer = null
    }

    private fun compileAndSaveSleepSession() {
        HeartRateState.log("Sleep Mode: Compiling sleep session records...")
        val endTime = System.currentTimeMillis()
        val durationSec = ((endTime - sessionStartTime) / 1000).toInt()

        if (sessionDataPoints.length() == 0 || durationSec < 10) {
            HeartRateState.log("Sleep Mode: Session was too short (${durationSec}s) or empty. Discarding.")
            return
        }

        var hrSum = 0
        var hrCount = 0
        var dbSum = 0.0
        var motionSum = 0.0
        
        for (i in 0 until sessionDataPoints.length()) {
            val pt = sessionDataPoints.getJSONObject(i)
            val hrVal = pt.getInt("hr")
            if (hrVal > 0) {
                hrSum += hrVal
                hrCount++
            }
            dbSum += pt.getDouble("db")
            motionSum += pt.getDouble("motion")
        }
        
        val avgHr = if (hrCount > 0) hrSum / hrCount else 0
        val avgDb = dbSum / sessionDataPoints.length()
        val avgMotion = motionSum / sessionDataPoints.length()

        val dateString = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(sessionStartTime))
        val endDateString = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(endTime))

        try {
            val sessionRecord = JSONObject().apply {
                put("id", "local_android_sleep_" + System.currentTimeMillis())
                put("type", "sleep")
                put("start_time", dateString)
                put("end_time", endDateString)
                put("duration_seconds", durationSec)
                put("average_hr", avgHr)
                put("average_db", Math.round(avgDb * 10.0) / 10.0)
                put("average_motion", Math.round(avgMotion * 100.0) / 100.0)
                put("data_points", sessionDataPoints)
                put("synced_to_server", false)
                put("synced_to_google", false)
            }

            HeartRateState.log("Sleep Mode: Saving session locally to SharedPreferences queue...")
            saveSessionToOfflineQueue(sessionRecord)
            
            // Force sync immediately
            checkAndSyncSessions()
        } catch (e: Exception) {
            HeartRateState.logError("Failed to compile and save sleep session", e)
        }
    }

    private fun updateServiceMode(newMode: String) {
        if (activeMode == newMode) {
            HeartRateState.log("updateServiceMode: Mode is already $newMode. No action taken.")
            return
        }
        
        HeartRateState.log("updateServiceMode: Transitioning from $activeMode to $newMode...")
        
        // Cleanup previous mode
        if (activeMode == "sleep") {
            stopSleepTimer()
            stopAudioMonitoring()
            stopMotionMonitoring()
            compileAndSaveSleepSession()
        } else if (activeMode == "workout" || activeMode == "running" || activeMode == "pushup" || activeMode == "stress" || activeMode == "japanese" || activeMode == "ukulele") {
            if (activeMode == "running" || activeMode == "workout") {
                stopGpsTracking()
            }
            if (activeMode == "japanese" || activeMode == "ukulele") {
                stopAudioMonitoring()
            }
            if (isRecording) {
                compileAndSaveSession()
            }
        }
        
        activeMode = newMode
        HeartRateState.trackingMode = newMode
        
        // Setup new mode
        if (activeMode == "sleep") {
            isLocalSensorsRunning = true
            startSleepTimer()
            startAudioMonitoring()
            startMotionMonitoring()
        } else if (activeMode == "workout" || activeMode == "running" || activeMode == "pushup" || activeMode == "stress" || activeMode == "japanese" || activeMode == "ukulele") {
            if (activeMode == "running" || activeMode == "workout") {
                startGpsTracking()
            }
            if (activeMode == "japanese" || activeMode == "ukulele") {
                startAudioMonitoring()
                isLocalSensorsRunning = true
            }
            // If BLE is already connected or watch is connected, start recording immediately
            if (HeartRateState.connectionState == "Connected" || HeartRateState.isWatchConnected) {
                startSessionRecording()
            }
        }
    }

    private val wearMessageListener = MessageClient.OnMessageReceivedListener { messageEvent ->
        if (messageEvent.path == "/sleep_update") {
            try {
                val dataStr = String(messageEvent.data, Charsets.UTF_8)
                val json = JSONObject(dataStr)
                
                val watchHr = json.optInt("hr", 0)
                val watchDb = json.optDouble("db", 0.0)
                val watchMotion = json.optDouble("motion", -1.0)
                
                lastWatchUpdateTime = System.currentTimeMillis()
                
                handler.post {
                    HeartRateState.isWatchConnected = true
                    if (watchHr > 0) {
                        HeartRateState.currentBpm = watchHr
                        lastPulseTime = System.currentTimeMillis()
                    }
                    if (watchDb >= 0.0) {
                        HeartRateState.sleepSoundDb = Math.round(watchDb * 10.0) / 10.0
                    }
                    if (watchMotion >= 0.0) {
                        HeartRateState.sleepMotionMag = Math.round(watchMotion * 100.0) / 100.0
                    }
                }
                
                HeartRateState.log("WearOS: Received update - HR=$watchHr, Sound=$watchDb dB, Motion=$watchMotion")
                logSensor("WATCH_HR", """{"bpm":$watchHr,"soundDb":$watchDb,"motionMag":$watchMotion}""")
                if (!HeartRateState.isWatchConnected) {
                    // First message after watch was not connected = it just connected
                    logSensor("DEVICE_CONNECT", """{"deviceName":"Pixel Watch","type":"WEAR_OS"}""")
                }
            } catch (e: Exception) {
                HeartRateState.logError("Failed to parse WearOS message payload", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        HeartRateState.log("HeartRateService: onDestroy invoked. Cleaning up...")
        
        try {
            Wearable.getMessageClient(this).removeListener(wearMessageListener)
        } catch (e: Exception) {
            // Ignore
        }

        if (activeMode == "sleep") {
            stopSleepTimer()
            stopAudioMonitoring()
            stopMotionMonitoring()
            compileAndSaveSleepSession()
        } else if (activeMode == "workout") {
            stopGpsTracking()
            if (isRecording) {
                compileAndSaveSession()
            }
        }
        
        if (!HeartRateState.debugMode) stopBleScan()

        try {
            toneGenerator?.release()
            toneGenerator = null
            HeartRateState.log("ToneGenerator released.")
        } catch (e: Exception) {
            // Ignore
        }
        
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            HeartRateState.log("Connectivity network callback unregistered.")
        } catch (e: Exception) {
            // Ignore if never registered
        }

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {
            HeartRateState.logError("SecurityException closing GATT in onDestroy", e)
        }
        
        isRecording = false
        HeartRateState.isServiceRunning = false
        HeartRateState.connectionState = "Disconnected"
        HeartRateState.currentBpm = 0
        HeartRateState.deviceName = "None"
        
        super.onDestroy()
    }
}
