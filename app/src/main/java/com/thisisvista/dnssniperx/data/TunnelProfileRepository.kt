package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Coordinates non-secret Room metadata with the Keystore-backed secret vault.
 * Secret bytes are never passed to Room or included in a profile model.
 */
class TunnelProfileRepository(
    private val dao: ResolverDao,
    private val secretStore: TunnelSecretStore
) {
    fun observeProfiles(): Flow<List<TunnelProfile>> =
        dao.observeTunnelProfiles().map { rows ->
            rows.mapNotNull(TunnelProfileMapper::toModel)
        }

    suspend fun findProfile(profileId: String): TunnelProfile? {
        require(profileId.isNotBlank()) { "Profile ID is required" }
        return dao.findTunnelProfile(profileId)?.let(TunnelProfileMapper::toModel)
    }

    suspend fun saveProfile(profile: TunnelProfile): TunnelProfile {
        val existing = dao.findTunnelProfile(profile.id)
        val entity = TunnelProfileMapper.toEntity(profile, existing)
        dao.saveTunnelProfile(entity)
        return requireNotNull(TunnelProfileMapper.toModel(entity)) {
            "Saved tunnel profile could not be restored"
        }
    }

    /** Remove encrypted credentials before metadata. */
    suspend fun deleteProfile(profileId: String) {
        require(profileId.isNotBlank()) { "Profile ID is required" }
        secretStore.deleteProfile(profileId)
        dao.deleteTunnelProfile(profileId)
    }

    suspend fun putSecret(profileId: String, secretName: String, secret: ByteArray) {
        requireProfileExists(profileId)
        secretStore.put(profileId, secretName, secret)
    }

    suspend fun getSecret(profileId: String, secretName: String): ByteArray? {
        requireProfileExists(profileId)
        return secretStore.get(profileId, secretName)
    }

    suspend fun deleteSecret(profileId: String, secretName: String) {
        requireProfileExists(profileId)
        secretStore.delete(profileId, secretName)
    }

    private suspend fun requireProfileExists(profileId: String) {
        require(profileId.isNotBlank()) { "Profile ID is required" }
        require(dao.findTunnelProfile(profileId) != null) { "Tunnel profile does not exist" }
    }
}
