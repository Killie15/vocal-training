package com.example.pulsebeatlogger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persistent chat line in The System thread. */
@Entity(tableName = "system_messages")
data class SystemMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
