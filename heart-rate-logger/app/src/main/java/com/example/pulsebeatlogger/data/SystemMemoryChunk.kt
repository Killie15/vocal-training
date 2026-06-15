package com.example.pulsebeatlogger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A distilled memory chunk — The System reads these on every response. */
@Entity(tableName = "system_memory_chunks")
data class SystemMemoryChunk(
    @PrimaryKey val id: String,
    /** Short summary The System uses for recall */
    val content: String,
    /** exchange | goal | log | preference | insight */
    val category: String = "exchange",
    /** Lowercase keywords for retrieval matching */
    val keywords: String = "",
    val userSnippet: String = "",
    val systemSnippet: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
