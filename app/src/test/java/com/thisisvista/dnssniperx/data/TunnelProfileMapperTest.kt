package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TunnelProfileMapperTest {
    @Test fun mapsDnsttMetadataWithoutAddingSecretFields() {
        val profile = TunnelProfile(
            id = "profile-1",
            name = "  Home DNSTT  ",
            protocol = TunnelProtocol.DNSTT,
            serverHost = " tunnel.example ",
            serverPort = 5300,
            dnsttServerPublicKey = "public-noise-key"
        )

        val entity = TunnelProfileMapper.toEntity(profile, now = 100L)
        val restored = TunnelProfileMapper.toModel(entity)

        assertEquals("Home DNSTT", entity.name)
        assertEquals("tunnel.example", entity.serverHost)
        assertEquals("DNSTT", entity.protocol)
        assertEquals(100L, entity.createdAt)
        assertEquals(100L, entity.updatedAt)
        assertEquals(profile.id, restored?.id)
        assertEquals(profile.dnsttServerPublicKey, restored?.dnsttServerPublicKey)
    }

    @Test fun updatePreservesCreationTimeAndAdvancesUpdateTime() {
        val profile = TunnelProfile(
            id = "profile-2",
            name = "Slipstream",
            protocol = TunnelProtocol.SLIPSTREAM,
            serverHost = "edge.example",
            serverPort = 443,
            slipstreamTlsServerName = "edge.example"
        )
        val existing = TunnelProfileMapper.toEntity(profile, now = 10L)

        val updated = TunnelProfileMapper.toEntity(
            profile.copy(name = "Updated"),
            existing = existing,
            now = 25L
        )

        assertEquals(10L, updated.createdAt)
        assertEquals(25L, updated.updatedAt)
        assertEquals("Updated", updated.name)
    }

    @Test fun rejectsInvalidProfilesAndMismatchedExistingRows() {
        val valid = TunnelProfile(
            id = "profile-3",
            name = "DNSTT",
            protocol = TunnelProtocol.DNSTT,
            serverHost = "edge.example",
            serverPort = 5300,
            dnsttServerPublicKey = "public-key"
        )
        val invalid = valid.copy(serverPort = 0)
        try {
            TunnelProfileMapper.toEntity(invalid)
            throw AssertionError("Expected invalid profile to be rejected")
        } catch (_: IllegalArgumentException) { }

        val other = TunnelProfileMapper.toEntity(valid.copy(id = "other"))
        try {
            TunnelProfileMapper.toEntity(valid, existing = other)
            throw AssertionError("Expected mismatched row to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun ignoresUnknownProtocolAndInvalidStoredRows() {
        val valid = TunnelProfile(
            id = "profile-4",
            name = "DNSTT",
            protocol = TunnelProtocol.DNSTT,
            serverHost = "edge.example",
            serverPort = 5300,
            dnsttServerPublicKey = "public-key"
        )
        val entity = TunnelProfileMapper.toEntity(valid)
        assertNull(TunnelProfileMapper.toModel(entity.copy(protocol = "FUTURE_PROTOCOL")))
        assertNull(TunnelProfileMapper.toModel(entity.copy(serverPort = 70000)))
        assertNotNull(TunnelProfileMapper.toModel(entity))
    }
}
