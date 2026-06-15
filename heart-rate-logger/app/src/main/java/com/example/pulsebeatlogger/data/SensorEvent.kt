package com.example.pulsebeatlogger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every individual sensor reading, device connection/disconnection event, and
 * data-transmission moment — each row is a single timestamped data point.
 *
 * sensorType values:
 *   BLE_HR        — heart rate reading from BLE chest strap / HR monitor
 *   GPS           — location/speed/altitude from phone GPS
 *   ACCELEROMETER — phone accelerometer (logged at ~1 Hz, not raw 50 Hz)
 *   MICROPHONE    — pitch/dB sample from passive voice profiling
 *   WATCH_HR      — HR + motion + sound from Pixel Watch via Wear OS
 *   DEVICE_CONNECT    — a sensor device connected
 *   DEVICE_DISCONNECT — a sensor device disconnected
 */
@Entity(
    tableName = "sensor_events",
    indices = [
        Index("timestamp"),
        Index("sensorType"),
        Index("sessionId"),
        Index("skillName")
    ]
)
data class SensorEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Epoch milliseconds — System.currentTimeMillis() at the moment data arrived */
    val timestamp: Long,

    /** One of the constants listed above */
    val sensorType: String,

    /** Skill being practised when this reading was captured (blank if service running passively) */
    val skillName: String,

    /** Session ID linking back to SkillSession (blank between sessions) */
    val sessionId: String,

    /** JSON blob containing the sensor-specific payload, e.g.:
     *  BLE_HR:    {"bpm":72,"device":"Polar H10"}
     *  GPS:       {"lat":35.68,"lon":139.76,"speedKmh":9.2,"altM":42.0,"accuracyM":4.1}
     *  ACCEL:     {"x":0.12,"y":-9.78,"z":0.43,"magnitude":9.79}
     *  MICROPHONE:{"hz":220.5,"db":58.3,"note":"A3"}
     *  WATCH_HR:  {"bpm":68,"soundDb":34.1,"motionMag":0.21}
     *  DEVICE_CONNECT/DISCONNECT: {"deviceName":"Polar H10","deviceAddress":"AA:BB:CC:..","type":"BLE_HR"}
     */
    val valueJson: String
)
