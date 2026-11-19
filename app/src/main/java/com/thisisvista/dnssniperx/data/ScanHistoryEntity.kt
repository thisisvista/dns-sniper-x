package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_history",
    indices = [Index(value = ["testedAt"])]
)
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val resolverAddress: String,
    val resolverPort: Int,
    val testedAt: Long,
    val udpStatus: String,
    val udpLatencyMs: Long?,
    val tcpStatus: String,
    val tcpLatencyMs: Long?,
    val scanMode: String
)
