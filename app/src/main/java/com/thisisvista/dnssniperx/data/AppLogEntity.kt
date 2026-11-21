package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_logs", indices = [Index(value = ["timestamp"])])
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val module: String,
    val message: String,
    val details: String? = null,
    val stage: String? = null,
    val identifier: String? = null,
    val networkType: String = "Unknown",
    val appVersion: String = "unknown"
)
