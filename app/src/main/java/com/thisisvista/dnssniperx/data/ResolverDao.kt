package com.thisisvista.dnssniperx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ResolverDao {
    @Query("SELECT * FROM tunnel_profiles ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeTunnelProfiles(): Flow<List<TunnelProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTunnelProfile(profile: TunnelProfileEntity)

    @Query("SELECT * FROM tunnel_profiles WHERE id = :profileId LIMIT 1")
    suspend fun findTunnelProfile(profileId: String): TunnelProfileEntity?

    @Query("DELETE FROM tunnel_profiles WHERE id = :profileId")
    suspend fun deleteTunnelProfile(profileId: String)

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
                WHEN udpStatus = 'PASS' AND tcpStatus = 'PASS' THEN
                    CASE
                        WHEN udpLatencyMs IS NULL THEN tcpLatencyMs
                        WHEN tcpLatencyMs IS NULL THEN udpLatencyMs
                        WHEN udpLatencyMs < tcpLatencyMs THEN udpLatencyMs
                        ELSE tcpLatencyMs
                    END
                WHEN udpStatus = 'PASS' THEN udpLatencyMs
                WHEN tcpStatus = 'PASS' THEN tcpLatencyMs
                ELSE NULL
            END ASC,
            collectedAt DESC,
            address ASC
    """)
    fun observeAll(): Flow<List<ResolverEntity>>

    @Query("SELECT COUNT(*) FROM resolvers")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM scan_history ORDER BY testedAt DESC, id DESC LIMIT 100")
    fun observeRecentHistory(): Flow<List<ScanHistoryEntity>>

    @Query("SELECT * FROM source_collection_history ORDER BY retrievedAt DESC, id DESC LIMIT 100")
    fun observeRecentSourceCollectionHistory(): Flow<List<SourceCollectionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceCollectionHistoryRows(entries: List<SourceCollectionHistoryEntity>)

    @Query("DELETE FROM source_collection_history WHERE id NOT IN (SELECT id FROM source_collection_history ORDER BY retrievedAt DESC, id DESC LIMIT 100)")
    suspend fun pruneSourceCollectionHistory()

    @Transaction
    suspend fun saveSourceCollectionHistory(entries: List<SourceCollectionHistoryEntity>) {
        if (entries.isNotEmpty()) insertSourceCollectionHistoryRows(entries)
        pruneSourceCollectionHistory()
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(entry: ScanHistoryEntity)

    @Query("SELECT * FROM doh_history ORDER BY testedAt DESC, id DESC LIMIT 100")
    fun observeRecentDohHistory(): Flow<List<DohHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDohHistory(entries: List<DohHistoryEntity>)

    @Query("SELECT * FROM dot_history ORDER BY testedAt DESC, id DESC LIMIT 100")
    fun observeRecentDotHistory(): Flow<List<DotHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDotHistory(entries: List<DotHistoryEntity>)

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

    @Query("SELECT * FROM resolvers WHERE address = :address AND port = :port LIMIT 1")
    suspend fun findByEndpoint(address: String, port: Int): ResolverEntity?

    @Query("UPDATE resolvers SET additionalSources = :sources WHERE id = :id")
    suspend fun updateAdditionalSources(id: Long, sources: String)

    @Transaction
    suspend fun insertOrMergeSources(resolvers: List<ResolverEntity>): Int {
        var insertedCount = 0
        for (resolver in resolvers) {
            val insertedId = insertIgnore(listOf(resolver)).firstOrNull() ?: -1L
            if (insertedId != -1L) {
                insertedCount++
                continue
            }
            val existing = findByEndpoint(resolver.address, resolver.port) ?: continue
            val mergedSources = (existing.additionalSources.lineSequence() +
                sequenceOf(resolver.source) +
                resolver.additionalSources.lineSequence())
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != existing.source }
                .distinct()
                .toList()
            val mergedText = mergedSources.joinToString("\n")
            if (mergedText != existing.additionalSources) {
                updateAdditionalSources(existing.id, mergedText)
            }
        }
        return insertedCount
    }

    @Query("DELETE FROM resolvers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
