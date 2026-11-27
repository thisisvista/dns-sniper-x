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

    /** Bounded manual-only result list for the Manual workspace. */
    @Query("""
        SELECT * FROM resolvers
        WHERE source IN ('manual', 'txt-import')
           OR additionalSources LIKE '%manual%'
           OR additionalSources LIKE '%txt-import%'
        ORDER BY collectedAt DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getManualResolverPage(limit: Int, offset: Int): List<ResolverEntity>

    @Query("""
        SELECT COUNT(*) FROM resolvers
        WHERE source IN ('manual', 'txt-import')
           OR additionalSources LIKE '%manual%'
           OR additionalSources LIKE '%txt-import%'
    """)
    suspend fun countManualResolvers(): Int


    /** Emits whenever any resolver row is inserted, deleted, or updated. */
    @Query("SELECT COUNT(*) FROM resolvers")
    fun observeResolverInvalidation(): Flow<Int>

    /**
     * Returns one best compatible IPv4 resolver per address. Duplicate selection
     * happens in SQLite, avoiding an unbounded in-memory address set during export.
     */
    @Query("""
        SELECT candidate.* FROM resolvers AS candidate
        WHERE candidate.port = 53
          AND (candidate.udpStatus = 'PASS' OR candidate.tcpStatus = 'PASS')
          AND (
              (length(candidate.address) - length(replace(candidate.address, '.', ''))) = 3
          )
          AND NOT EXISTS (
              SELECT 1 FROM resolvers AS better
              WHERE better.address = candidate.address
                AND better.port = 53
                AND (better.udpStatus = 'PASS' OR better.tcpStatus = 'PASS')
                AND (
                    CASE
                        WHEN better.udpStatus = 'PASS' AND better.tcpStatus = 'PASS' THEN
                            CASE WHEN better.udpLatencyMs IS NULL THEN better.tcpLatencyMs
                                 WHEN better.tcpLatencyMs IS NULL THEN better.udpLatencyMs
                                 WHEN better.udpLatencyMs < better.tcpLatencyMs THEN better.udpLatencyMs
                                 ELSE better.tcpLatencyMs END
                        WHEN better.udpStatus = 'PASS' THEN better.udpLatencyMs
                        WHEN better.tcpStatus = 'PASS' THEN better.tcpLatencyMs
                        ELSE 9223372036854775807
                    END
                    <
                    CASE
                        WHEN candidate.udpStatus = 'PASS' AND candidate.tcpStatus = 'PASS' THEN
                            CASE WHEN candidate.udpLatencyMs IS NULL THEN candidate.tcpLatencyMs
                                 WHEN candidate.tcpLatencyMs IS NULL THEN candidate.udpLatencyMs
                                 WHEN candidate.udpLatencyMs < candidate.tcpLatencyMs THEN candidate.udpLatencyMs
                                 ELSE candidate.tcpLatencyMs END
                        WHEN candidate.udpStatus = 'PASS' THEN candidate.udpLatencyMs
                        WHEN candidate.tcpStatus = 'PASS' THEN candidate.tcpLatencyMs
                        ELSE 9223372036854775807
                    END
                    OR (
                        CASE
                            WHEN better.udpStatus = 'PASS' AND better.tcpStatus = 'PASS' THEN
                                CASE WHEN better.udpLatencyMs IS NULL THEN better.tcpLatencyMs
                                     WHEN better.tcpLatencyMs IS NULL THEN better.udpLatencyMs
                                     WHEN better.udpLatencyMs < better.tcpLatencyMs THEN better.udpLatencyMs
                                     ELSE better.tcpLatencyMs END
                            WHEN better.udpStatus = 'PASS' THEN better.udpLatencyMs
                            WHEN better.tcpStatus = 'PASS' THEN better.tcpLatencyMs
                            ELSE 9223372036854775807
                        END
                        =
                        CASE
                            WHEN candidate.udpStatus = 'PASS' AND candidate.tcpStatus = 'PASS' THEN
                                CASE WHEN candidate.udpLatencyMs IS NULL THEN candidate.tcpLatencyMs
                                     WHEN candidate.tcpLatencyMs IS NULL THEN candidate.udpLatencyMs
                                     WHEN candidate.udpLatencyMs < candidate.tcpLatencyMs THEN candidate.udpLatencyMs
                                     ELSE candidate.tcpLatencyMs END
                            WHEN candidate.udpStatus = 'PASS' THEN candidate.udpLatencyMs
                            WHEN candidate.tcpStatus = 'PASS' THEN candidate.tcpLatencyMs
                            ELSE 9223372036854775807
                        END
                        AND better.id < candidate.id
                    )
                )
          )
        ORDER BY
            CASE
                WHEN candidate.udpStatus = 'PASS' AND candidate.tcpStatus = 'PASS' THEN
                    CASE WHEN candidate.udpLatencyMs IS NULL THEN candidate.tcpLatencyMs
                         WHEN candidate.tcpLatencyMs IS NULL THEN candidate.udpLatencyMs
                         WHEN candidate.udpLatencyMs < candidate.tcpLatencyMs THEN candidate.udpLatencyMs
                         ELSE candidate.tcpLatencyMs END
                WHEN candidate.udpStatus = 'PASS' THEN candidate.udpLatencyMs
                WHEN candidate.tcpStatus = 'PASS' THEN candidate.tcpLatencyMs
                ELSE 9223372036854775807
            END ASC,
            candidate.collectedAt DESC,
            candidate.address ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSlipNetExportBatch(limit: Int, offset: Int): List<ResolverEntity>

    @Query("""
        SELECT COUNT(*) FROM resolvers
        WHERE udpStatus = 'PASS' OR tcpStatus = 'PASS'
    """)
    suspend fun countWorkingExportRows(): Int

    @Query("""
        SELECT COUNT(DISTINCT address) FROM resolvers
        WHERE port = 53
          AND (udpStatus = 'PASS' OR tcpStatus = 'PASS')
          AND (length(address) - length(replace(address, '.', ''))) = 3
          AND address NOT GLOB '*[^0-9.]*'
          AND address NOT LIKE '.%'
          AND address NOT LIKE '%.'
          AND address NOT LIKE '%..%'
          AND length(substr(address, 1, instr(address, '.') - 1)) BETWEEN 1 AND 3
          AND CAST(substr(address, 1, instr(address, '.') - 1) AS INTEGER) BETWEEN 0 AND 255
          AND length(substr(substr(address, instr(address, '.') + 1), 1, instr(substr(address, instr(address, '.') + 1), '.') - 1)) BETWEEN 1 AND 3
          AND CAST(substr(substr(address, instr(address, '.') + 1), 1, instr(substr(address, instr(address, '.') + 1), '.') - 1) AS INTEGER) BETWEEN 0 AND 255
          AND length(substr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), 1, instr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), '.') - 1)) BETWEEN 1 AND 3
          AND CAST(substr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), 1, instr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), '.') - 1) AS INTEGER) BETWEEN 0 AND 255
          AND length(substr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), instr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), '.') + 1)) BETWEEN 1 AND 3
          AND CAST(substr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), instr(substr(substr(address, instr(address, '.') + 1), instr(substr(address, instr(address, '.') + 1), '.') + 1), '.') + 1) AS INTEGER) BETWEEN 0 AND 255
          AND address NOT GLOB '*[^0-9.]*'
          AND address NOT LIKE '.%'
          AND address NOT LIKE '%.'
          AND address NOT LIKE '%..%'
    """)
    suspend fun countSlipNetCompatibleCandidates(): Int

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
          AND (
            :sourceFilter = 'ALL'
            OR (:sourceFilter = 'MANUAL' AND (
                source IN ('manual', 'txt-import')
                OR additionalSources LIKE '%manual%'
                OR additionalSources LIKE '%txt-import%'
            ))
            OR (:sourceFilter = 'PUBLIC' AND (
                source IN (
                    'iran-public-dns-directory',
                    'public-dns-info',
                    'trickest-resolvers',
                    'global-public-dns-directory',
                    'provider-asn-prefix-sample'
                )
                OR additionalSources LIKE '%provider-asn-prefix-sample%'
            ))
          )
    """)
    suspend fun countPendingScan(retryUnsuccessful: Boolean, sourceFilter: String): Int

    @Query("""
        SELECT * FROM resolvers
        WHERE id > :afterId AND (
            (:retryUnsuccessful = 0 AND udpStatus = 'NOT_TESTED' AND tcpStatus = 'NOT_TESTED')
            OR
            (:retryUnsuccessful = 1 AND
                (udpStatus != 'NOT_TESTED' OR tcpStatus != 'NOT_TESTED') AND
                (udpStatus != 'PASS' OR tcpStatus != 'PASS' OR COALESCE(note, '') LIKE '%WARNING:%'))
        )
          AND (
            :sourceFilter = 'ALL'
            OR (:sourceFilter = 'MANUAL' AND (
                source IN ('manual', 'txt-import')
                OR additionalSources LIKE '%manual%'
                OR additionalSources LIKE '%txt-import%'
            ))
            OR (:sourceFilter = 'PUBLIC' AND (
                source IN (
                    'iran-public-dns-directory',
                    'public-dns-info',
                    'trickest-resolvers',
                    'global-public-dns-directory',
                    'provider-asn-prefix-sample'
                )
                OR additionalSources LIKE '%provider-asn-prefix-sample%'
            ))
          )
        ORDER BY id ASC
        LIMIT :limit
    """)
    suspend fun getPendingScanBatch(
        retryUnsuccessful: Boolean,
        sourceFilter: String,
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

    /**
     * Persists the scan history row and current resolver status atomically.
     * A failed write cannot leave history and resolver state out of sync.
     */
    @Transaction
    suspend fun saveScanResult(
        history: ScanHistoryEntity,
        resolverId: Long,
        udpStatus: String,
        udpLatencyMs: Long?,
        tcpStatus: String,
        tcpLatencyMs: Long?,
        testedAt: Long,
        overallStatus: String,
        bestLatency: Long?,
        note: String?
    ) {
        insertHistory(history)
        updateScanResult(
            id = resolverId,
            udpStatus = udpStatus,
            udpLatencyMs = udpLatencyMs,
            tcpStatus = tcpStatus,
            tcpLatencyMs = tcpLatencyMs,
            testedAt = testedAt,
            overallStatus = overallStatus,
            bestLatency = bestLatency,
            note = note
        )
    }

    @Query("SELECT * FROM resolvers WHERE address = :address AND port = :port LIMIT 1")
    suspend fun findByEndpoint(address: String, port: Int): ResolverEntity?

    @Query("UPDATE resolvers SET additionalSources = :sources WHERE id = :id")
    suspend fun updateAdditionalSources(id: Long, sources: String)

    @Transaction
    suspend fun insertOrMergeSources(resolvers: List<ResolverEntity>): Int {
        if (resolvers.isEmpty()) return 0

        // Insert the bounded collection batch in one DAO call instead of invoking
        // Room once per row. IGNORE returns -1 for endpoints already in the table
        // (including duplicates earlier in this same batch).
        val insertedIds = insertIgnore(resolvers)
        var insertedCount = 0
        resolvers.forEachIndexed { index, resolver ->
            val insertedId = insertedIds.getOrNull(index) ?: -1L
            if (insertedId != -1L) {
                insertedCount++
                return@forEachIndexed
            }
            val existing = findByEndpoint(resolver.address, resolver.port) ?: return@forEachIndexed
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

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT 500")
    fun observeRecentAppLogs(): Flow<List<AppLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppLog(entry: AppLogEntity)

    @Query("DELETE FROM app_logs WHERE id NOT IN (SELECT id FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT 1000)")
    suspend fun pruneAppLogs()

    @Transaction
    suspend fun addAppLog(entry: AppLogEntity) {
        insertAppLog(entry)
        pruneAppLogs()
    }

    @Query("SELECT * FROM app_logs ORDER BY timestamp ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun getAppLogExportBatch(limit: Int, offset: Int): List<AppLogEntity>

    @Query("DELETE FROM app_logs")
    suspend fun clearAppLogs()

    @Query("DELETE FROM resolvers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM provider_catalog WHERE enabled = 1 ORDER BY providerType ASC, displayName COLLATE NOCASE ASC")
    fun observeProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider_catalog WHERE providerId = :providerId LIMIT 1")
    suspend fun findProvider(providerId: String): ProviderEntity?

    @Query("SELECT * FROM resolver_sources WHERE enabled = 1 ORDER BY sourceType ASC, displayName COLLATE NOCASE ASC")
    fun observeResolverSources(): Flow<List<ResolverSourceEntity>>

    @Query("SELECT * FROM resolver_sources WHERE providerId = :providerId AND enabled = 1 ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getSourcesForProvider(providerId: String): List<ResolverSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResolverSource(source: ResolverSourceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResolverEvidence(evidence: ResolverEvidenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResolverEvidenceRows(evidence: List<ResolverEvidenceEntity>)

    /** Persist candidate rows, their RIPEstat sources and evidence as one atomic batch. */
    @Transaction
    suspend fun persistProviderDiscoveryBatch(
        resolvers: List<ResolverEntity>,
        sources: List<ResolverSourceEntity>,
        evidence: List<ResolverEvidenceEntity>,
        state: ProviderDiscoveryStateEntity? = null
    ): Int {
        val inserted = insertOrMergeSources(resolvers)
        sources.forEach { upsertResolverSource(it) }
        if (evidence.isNotEmpty()) insertResolverEvidenceRows(evidence)
        if (state != null) upsertProviderDiscoveryState(state)
        return inserted
    }

    @Query("SELECT * FROM provider_discovery_state WHERE selectionKey = :selectionKey LIMIT 1")
    suspend fun getProviderDiscoveryState(selectionKey: String): ProviderDiscoveryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderDiscoveryState(state: ProviderDiscoveryStateEntity)

    @Query("DELETE FROM provider_discovery_state WHERE selectionKey = :selectionKey")
    suspend fun deleteProviderDiscoveryState(selectionKey: String)

    @Query("DELETE FROM provider_discovery_state")
    suspend fun deleteAllProviderDiscoveryStates()

    @Query("SELECT * FROM resolver_evidence WHERE address = :address AND port = :port ORDER BY collectedAt DESC, id DESC")
    suspend fun getResolverEvidence(address: String, port: Int): List<ResolverEvidenceEntity>

    @Query("SELECT * FROM resolver_evidence WHERE providerId = :providerId ORDER BY collectedAt DESC, id DESC LIMIT :limit")
    suspend fun getEvidenceForProvider(providerId: String, limit: Int): List<ResolverEvidenceEntity>

    @Query("SELECT COUNT(*) FROM resolver_evidence WHERE providerId = :providerId AND ownershipStatus = 'VERIFIED'")
    suspend fun countVerifiedEvidenceForProvider(providerId: String): Int

    @Query("SELECT COUNT(*) FROM resolver_evidence WHERE providerId = :providerId AND ownershipStatus = 'UNVERIFIED'")
    suspend fun countUnverifiedEvidenceForProvider(providerId: String): Int

}
