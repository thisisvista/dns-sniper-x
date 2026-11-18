package com.thisisvista.dnssniperx.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "resolvers", indices = [Index(value = ["address", "port"], unique = true)])
data class ResolverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val port: Int = 53,
    val source: String = "manual",
    val collectedAt: Long = System.currentTimeMillis(),
    val status: String = "NOT_TESTED",
    val lastTestedAt: Long? = null,
    val latencyMs: Long? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "'NOT_TESTED'") val udpStatus: String = "NOT_TESTED",
    val udpLatencyMs: Long? = null,
    @ColumnInfo(defaultValue = "'NOT_TESTED'") val tcpStatus: String = "NOT_TESTED",
    val tcpLatencyMs: Long? = null
)
