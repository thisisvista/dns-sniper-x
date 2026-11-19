package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "dot_history", indices = [Index(value = ["testedAt"])])
data class DotHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val hostname: String,
    val testedAt: Long,
    val status: String,
    val latencyMs: Long?,
    val detail: String
)
