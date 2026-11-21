package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "doh_history", indices = [Index(value = ["testedAt"])])
data class DohHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val endpoint: String,
    val testedAt: Long,
    val status: String,
    val latencyMs: Long?,
    val detail: String
)
