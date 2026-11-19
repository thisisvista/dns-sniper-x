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

/** Converts validated profile metadata to and from Room without handling credentials. */
object TunnelProfileMapper {
    fun toEntity(
        profile: TunnelProfile,
        existing: TunnelProfileEntity? = null,
        now: Long = System.currentTimeMillis()
    ): TunnelProfileEntity {
        require(profile.isValid()) { profile.validate().joinToString("; ") }
        require(existing == null || existing.id == profile.id) {
            "Existing profile ID does not match"
        }
        return TunnelProfileEntity(
            id = profile.id,
            name = profile.name.trim(),
            protocol = profile.protocol.name,
            serverHost = profile.serverHost.trim(),
            serverPort = profile.serverPort,
            dnsttServerPublicKey = profile.dnsttServerPublicKey,
            slipstreamTlsServerName = profile.slipstreamTlsServerName,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
    }

    /** Returns null for rows written by an unsupported future protocol version. */
    fun toModel(entity: TunnelProfileEntity): TunnelProfile? {
        val protocol = runCatching { TunnelProtocol.valueOf(entity.protocol) }.getOrNull()
            ?: return null
        return TunnelProfile(
            id = entity.id,
            name = entity.name,
            protocol = protocol,
            serverHost = entity.serverHost,
            serverPort = entity.serverPort,
            dnsttServerPublicKey = entity.dnsttServerPublicKey,
            slipstreamTlsServerName = entity.slipstreamTlsServerName
        ).takeIf { it.isValid() }
    }
}
