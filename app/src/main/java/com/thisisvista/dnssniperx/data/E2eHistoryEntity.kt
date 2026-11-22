package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable evidence for one end-to-end tunnel attempt.
 *
 * Network identity is deliberately stored independently from resolver ownership:
 * a resolver succeeding on a cellular network does not prove the resolver belongs
 * to that operator. Multiple attempts on different networks remain separate rows.
 */
@Entity(
    tableName = "e2e_history",
    indices = [
        Index(value = ["resolverAddress", "resolverPort", "testedAt"]),
        Index(value = ["networkKey", "testedAt"]),
        Index(value = ["status", "testedAt"])
    ]
)
data class E2eHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val resolverAddress: String,
    val resolverPort: Int,
    val testedAt: Long = System.currentTimeMillis(),
    /** Stable descriptive key such as WIFI or CELLULAR:mci; not an Android Network ID. */
    val networkKey: String,
    val networkTransport: String,
    val networkLabel: String? = null,
    val operatorName: String? = null,
    /** Contextual provider match only; never proof of resolver ownership. */
    val providerId: String? = null,
    val profileId: String,
    /** Snapshot of the exact tested profile; old rows may have empty defaults. */
    val profileName: String = "",
    val serverHost: String = "",
    val serverPort: Int = 0,
    val dnsttServerPublicKey: String? = null,
    val slipstreamTlsServerName: String? = null,
    val tunnelProtocol: String,
    val verificationMode: String,
    val status: String,
    val phase: String,
    val elapsedMs: Long,
    val httpStatusCode: Int? = null,
    val errorMessage: String? = null
)
