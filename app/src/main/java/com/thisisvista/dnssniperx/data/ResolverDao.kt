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

    /** Emits whenever any resolver row is inserted, deleted, or updated. */
    @Query("SELECT COUNT(*) FROM resolvers")
    fun observeResolverInvalidation(): Flow<Int>

    @Query("""
        SELECT * FROM resolvers
        WHERE udpStatus = 'PASS' OR tcpStatus = 'PASS'
        ORDER BY
            CASE
                WHEN udpStatus = 'PASS' AND tcpStatus = 'PASS' THEN
                    CASE WHEN udpLatencyMs IS NULL THEN tcpLatencyMs
                         WHEN tcpLatencyMs IS NULL THEN udpLatencyMs
                         WHEN udpLatencyMs < tcpLatencyMs THEN udpLatencyMs
                         ELSE tcpLatencyMs END
                WHEN udpStatus = 'PASS' THEN udpLatencyMs
                WHEN tcpStatus = 'PASS' THEN tcpLatencyMs
                ELSE 9223372036854775807
            END ASC,
            collectedAt DESC,
            address ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getWorkingExportBatch(limit: Int, offset: Int): List<ResolverEntity>

    /**
     * Loads a bounded resolver window. Filtering is performed by SQLite so the UI
     * never needs to collect the complete resolver table into memory.
     */
    @Query("""
        SELECT * FROM resolvers
        WHERE
            (:statusFilter = 'ALL'
                OR (:statusFilter = 'WORKING' AND (udpStatus = 'PASS' OR tcpStatus = 'PASS'))
                OR (:statusFilter = 'UDP' AND udpStatus = 'PASS')
                OR (:statusFilter = 'TCP' AND tcpStatus = 'PASS')
                OR (:statusFilter = 'UNTESTED' AND udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED')
                OR (:statusFilter = 'WARNINGS' AND COALESCE(note, '') LIKE '%WARNING:%')
                OR (:statusFilter = 'TIMEOUT' AND (udpStatus = 'TIMEOUT' OR tcpStatus = 'TIMEOUT'))
                OR (:statusFilter = 'DNS_ERROR' AND (udpStatus = 'DNS_ERROR' OR tcpStatus = 'DNS_ERROR'))
                OR (:statusFilter = 'NO_ANSWER' AND (udpStatus = 'NO_ANSWER' OR tcpStatus = 'NO_ANSWER'))
                OR (:statusFilter = 'TRUNCATED' AND (udpStatus = 'TRUNCATED' OR tcpStatus = 'TRUNCATED'))
                OR (:statusFilter = 'FAIL' AND (udpStatus = 'FAIL' OR tcpStatus = 'FAIL')))
            AND (:networkType = 'ALL' OR source LIKE 'current-network (' || :networkType || ';%')
            AND (:providerLabel = 'ALL' OR instr(source, '; user label: ' || :providerLabel || ')') > 0)
            AND (
                :searchTerm = '' OR
                lower(address) LIKE '%' || lower(:searchTerm) || '%' OR
                CAST(port AS TEXT) = :searchTerm OR
                lower(source) LIKE '%' || lower(:searchTerm) || '%' OR
                lower(COALESCE(note, '')) LIKE '%' || lower(:searchTerm) || '%'
            )
        ORDER BY
            CASE WHEN :fastestFirst = 1 THEN
                CASE
                    WHEN udpStatus = 'PASS' AND tcpStatus = 'PASS' THEN
                        CASE WHEN udpLatencyMs IS NULL THEN tcpLatencyMs
                             WHEN tcpLatencyMs IS NULL THEN udpLatencyMs
                             WHEN udpLatencyMs < tcpLatencyMs THEN udpLatencyMs
                             ELSE tcpLatencyMs END
                    WHEN udpStatus = 'PASS' THEN udpLatencyMs
                    WHEN tcpStatus = 'PASS' THEN tcpLatencyMs
                    ELSE 9223372036854775807
                END
            ELSE 0 END ASC,
            CASE
                WHEN udpStatus = 'PASS' AND tcpStatus = 'PASS' THEN 0
                WHEN udpStatus = 'PASS' OR tcpStatus = 'PASS' THEN 1
                WHEN udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED' THEN 2
                ELSE 3
            END ASC,
            collectedAt DESC,
            address ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getResolverPage(
        statusFilter: String,
        searchTerm: String,
        networkType: String,
        providerLabel: String,
        fastestFirst: Boolean,
        limit: Int,
        offset: Int
    ): List<ResolverEntity>

    @Query("""
        SELECT COUNT(*) FROM resolvers
        WHERE
            (:statusFilter = 'ALL'
                OR (:statusFilter = 'WORKING' AND (udpStatus = 'PASS' OR tcpStatus = 'PASS'))
                OR (:statusFilter = 'UDP' AND udpStatus = 'PASS')
                OR (:statusFilter = 'TCP' AND tcpStatus = 'PASS')
                OR (:statusFilter = 'UNTESTED' AND udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED')
                OR (:statusFilter = 'WARNINGS' AND COALESCE(note, '') LIKE '%WARNING:%')
                OR (:statusFilter = 'TIMEOUT' AND (udpStatus = 'TIMEOUT' OR tcpStatus = 'TIMEOUT'))
                OR (:statusFilter = 'DNS_ERROR' AND (udpStatus = 'DNS_ERROR' OR tcpStatus = 'DNS_ERROR'))
                OR (:statusFilter = 'NO_ANSWER' AND (udpStatus = 'NO_ANSWER' OR tcpStatus = 'NO_ANSWER'))
                OR (:statusFilter = 'TRUNCATED' AND (udpStatus = 'TRUNCATED' OR tcpStatus = 'TRUNCATED'))
                OR (:statusFilter = 'FAIL' AND (udpStatus = 'FAIL' OR tcpStatus = 'FAIL')))
            AND (:networkType = 'ALL' OR source LIKE 'current-network (' || :networkType || ';%')
            AND (:providerLabel = 'ALL' OR instr(source, '; user label: ' || :providerLabel || ')') > 0)
            AND (
                :searchTerm = '' OR
                lower(address) LIKE '%' || lower(:searchTerm) || '%' OR
                CAST(port AS TEXT) = :searchTerm OR
                lower(source) LIKE '%' || lower(:searchTerm) || '%' OR
                lower(COALESCE(note, '')) LIKE '%' || lower(:searchTerm) || '%'
            )
    """)
    suspend fun countResolverMatches(
        statusFilter: String,
        searchTerm: String,
        networkType: String,
        providerLabel: String
    ): Int

    @Query("""
        SELECT DISTINCT
            substr(source, length('current-network (') + 1,
                instr(source, ';') - length('current-network (') - 1)
        FROM resolvers
        WHERE source LIKE 'current-network (%'
        ORDER BY 1
    """)
    suspend fun getNetworkTypeFilters(): List<String>

    @Query("""
        SELECT DISTINCT
            substr(source, instr(source, '; user label: ') + length('; user label: '),
                length(source) - instr(source, '; user label: ') - length('; user label: '))
        FROM resolvers
        WHERE instr(source, '; user label: ') > 0
        ORDER BY 1
    """)
    suspend fun getProviderLabelFilters(): List<String>

    @Query("SELECT COUNT(*) FROM resolvers WHERE udpStatus = 'PASS' OR tcpStatus = 'PASS'")
    suspend fun countHealthyResolvers(): Int

    @Query("SELECT COUNT(*) FROM resolvers WHERE udpStatus = 'PASS'")
    suspend fun countUdpPassResolvers(): Int

    @Query("SELECT COUNT(*) FROM resolvers WHERE tcpStatus = 'PASS'")
    suspend fun countTcpPassResolvers(): Int

    @Query("SELECT COUNT(*) FROM resolvers WHERE udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED'")
    suspend fun countUntestedResolvers(): Int

    @Query("SELECT COUNT(*) FROM resolvers WHERE COALESCE(note, '') LIKE '%WARNING:%'")
    suspend fun countWarningResolvers(): Int

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

    @Query("""
        SELECT COUNT(*) FROM resolvers
        WHERE (
            (:retryUnsuccessful = 0 AND udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED')
            OR
            (:retryUnsuccessful = 1 AND
                (udpStatus != 'NOT_TESTED' OR tcpStatus != 'NOT_TESTED') AND
                (udpStatus != 'PASS' OR tcpStatus != 'PASS' OR COALESCE(note, '') LIKE '%WARNING:%'))
        )
    """)
    suspend fun countPendingScan(retryUnsuccessful: Boolean): Int

    @Query("""
        SELECT * FROM resolvers
        WHERE id > :afterId AND (
            (:retryUnsuccessful = 0 AND udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED')
            OR
            (:retryUnsuccessful = 1 AND
                (udpStatus != 'NOT_TESTED' OR tcpStatus != 'NOT_TESTED') AND
                (udpStatus != 'PASS' OR tcpStatus != 'PASS' OR COALESCE(note, '') LIKE '%WARNING:%'))
        )
        ORDER BY id ASC
        LIMIT :limit
    """)
    suspend fun getPendingScanBatch(
        retryUnsuccessful: Boolean,
        afterId: Long,
        limit: Int
    ): List<ResolverEntity>

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
