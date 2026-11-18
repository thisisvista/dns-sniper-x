package com.thisisvista.dnssniperx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ResolverDao {
    @Query("""
        SELECT * FROM resolvers
        ORDER BY
            CASE
                WHEN udpStatus = 'PASS' AND tcpStatus = 'PASS' THEN 0
                WHEN udpStatus = 'PASS' OR tcpStatus = 'PASS' THEN 1
                WHEN udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED' THEN 2
                ELSE 3
            END ASC,
            CASE
                WHEN udpLatencyMs IS NULL THEN tcpLatencyMs
                WHEN tcpLatencyMs IS NULL THEN udpLatencyMs
                WHEN udpLatencyMs < tcpLatencyMs THEN udpLatencyMs
                ELSE tcpLatencyMs
            END ASC,
            collectedAt DESC,
            address ASC
    """)
    fun observeAll(): Flow<List<ResolverEntity>>

    @Query("SELECT COUNT(*) FROM resolvers")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(resolvers: List<ResolverEntity>): List<Long>

    @Query("UPDATE resolvers SET udpStatus = :udpStatus, udpLatencyMs = :udpLatencyMs, tcpStatus = :tcpStatus, tcpLatencyMs = :tcpLatencyMs, lastTestedAt = :testedAt, status = :overallStatus, latencyMs = :bestLatency, note = :note WHERE id = :id")
    suspend fun updateScanResult(
        id: Long,
        udpStatus: String,
        udpLatencyMs: Long?,
        tcpStatus: String,
        tcpLatencyMs: Long?,
        testedAt: Long,
        overallStatus: String,
        bestLatency: Long?,
        note: String?
    )

    @Query("DELETE FROM resolvers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
