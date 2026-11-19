package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Non-secret tunnel profile metadata. Credentials must remain in TunnelSecretStore. */
@Entity(tableName = "tunnel_profiles")
data class TunnelProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val serverHost: String,
    val serverPort: Int,
    val dnsttServerPublicKey: String?,
    val slipstreamTlsServerName: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
