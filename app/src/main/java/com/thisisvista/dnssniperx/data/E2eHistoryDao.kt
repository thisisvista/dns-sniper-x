package com.thisisvista.dnssniperx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Read/write access to immutable, per-attempt end-to-end tunnel evidence. */
@Dao
interface E2eHistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: E2eHistoryEntity): Long

    @Query("""
        SELECT * FROM e2e_history
        WHERE resolverAddress = :address AND resolverPort = :port
        ORDER BY testedAt DESC, id DESC
        LIMIT :limit
    """)
    fun observeResolverAttempts(address: String, port: Int, limit: Int = 100): Flow<List<E2eHistoryEntity>>

    @Query("""
        SELECT * FROM e2e_history
        WHERE resolverAddress = :address AND resolverPort = :port
        ORDER BY testedAt DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getResolverAttempts(address: String, port: Int, limit: Int = 100): List<E2eHistoryEntity>

    @Query("""
        SELECT * FROM e2e_history
        WHERE networkKey = :networkKey
        ORDER BY testedAt DESC, id DESC
        LIMIT :limit
    """)
    fun observeNetworkAttempts(networkKey: String, limit: Int = 100): Flow<List<E2eHistoryEntity>>

    @Query("SELECT COUNT(*) FROM e2e_history")
    fun observeAttemptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM e2e_history WHERE status = :status")
    fun observeStatusCount(status: String): Flow<Int>

    /** True only when this exact DNSTT profile already has an HTTP-verified pass for the resolver. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM e2e_history
            WHERE resolverAddress = :address
              AND resolverPort = :port
              AND profileId = :profileId
              AND serverHost = :serverHost
              AND dnsttServerPublicKey = :publicKey
              AND tunnelProtocol = 'DNSTT'
              AND verificationMode = 'FULL'
              AND status = 'FULL_PASS'
              AND httpStatusCode = 204
        )
    """)
    suspend fun hasFullPassForProfile(
        address: String,
        port: Int,
        profileId: String,
        serverHost: String,
        publicKey: String
    ): Boolean

    /**
     * SlipNet import candidates: latest successful FULL E2E proof per resolver,
     * engine profile and endpoint. Failed later retests do not erase last success.
     * Only actual application-level HTTP verification qualifies.
     */
    @Query("""
        SELECT
            e.resolverAddress AS resolverAddress,
            e.resolverPort AS resolverPort,
            e.testedAt AS testedAt,
            e.networkKey AS networkKey,
            e.networkTransport AS networkTransport,
            e.networkLabel AS networkLabel,
            e.operatorName AS operatorName,
            e.providerId AS providerId,
            e.profileId AS profileId,
            e.profileName AS profileName,
            e.tunnelProtocol AS tunnelProtocol,
            e.serverHost AS serverHost,
            e.serverPort AS serverPort,
            e.dnsttServerPublicKey AS dnsttServerPublicKey,
            e.slipstreamTlsServerName AS slipstreamTlsServerName,
            e.verificationMode AS verificationMode,
            e.status AS status,
            e.elapsedMs AS elapsedMs,
            e.httpStatusCode AS httpStatusCode
        FROM e2e_history AS e
        WHERE e.resolverPort = 53
          AND e.verificationMode = 'FULL'
          AND e.status = 'FULL_PASS'
          AND e.httpStatusCode BETWEEN 200 AND 399
          AND e.tunnelProtocol IN ('DNSTT', 'VAYDNS')
          AND e.profileName != ''
          AND e.serverHost != ''
          AND NOT EXISTS (
              SELECT 1 FROM e2e_history AS newer
              WHERE newer.resolverAddress = e.resolverAddress
                AND newer.resolverPort = e.resolverPort
                AND newer.profileId = e.profileId
                AND newer.tunnelProtocol = e.tunnelProtocol
                AND newer.verificationMode = 'FULL'
                AND newer.status = 'FULL_PASS'
                AND newer.httpStatusCode BETWEEN 200 AND 399
                AND (
                    newer.testedAt > e.testedAt
                    OR (newer.testedAt = e.testedAt AND newer.id > e.id)
                )
          )
        ORDER BY e.testedAt DESC, e.resolverAddress ASC, e.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSlipNetE2eExportBatch(limit: Int, offset: Int): List<SlipNetE2eExportRow>
}
