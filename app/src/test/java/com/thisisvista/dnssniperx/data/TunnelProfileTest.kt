package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProfileTest {
    private val validKey = "0123456789abcdef".repeat(4)

    @Test fun validDnsttProfilePasses() {
        val profile = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 5300, dnsttServerPublicKey = validKey)
        assertTrue(profile.validate().toString(), profile.isValid())
    }

    @Test fun dnsttRequiresPublicKey() {
        val profile = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 53)
        assertTrue(profile.validate().contains("DNSTT server public key is required"))
    }

    @Test fun dnsttRejectsShortOrNonHexPublicKey() {
        val shortKey = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 53,
            dnsttServerPublicKey = "public-key")
        val nonHexKey = TunnelProfile("d2", "Test", TunnelProtocol.DNSTT, "t.example.net", 53,
            dnsttServerPublicKey = "z".repeat(64))

        assertFalse(shortKey.isValid())
        assertFalse(nonHexKey.isValid())
        assertTrue(shortKey.validate().contains(
            "DNSTT public key must be exactly 32 bytes encoded as 64 hexadecimal characters"
        ))
    }

    @Test fun slipstreamRequiresTlsName() {
        val profile = TunnelProfile("s1", "Test", TunnelProtocol.SLIPSTREAM, "192.0.2.10", 443)
        assertTrue(profile.validate().contains("Slipstream TLS server name is required"))
    }

    @Test fun protocolSpecificFieldsCannotBeMixed() {
        val profile = TunnelProfile("s1", "Test", TunnelProtocol.SLIPSTREAM, "192.0.2.10", 443,
            dnsttServerPublicKey = validKey, slipstreamTlsServerName = "t.example.net")
        assertFalse(profile.isValid())
    }

    @Test fun invalidPortIsRejected() {
        val profile = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 70000,
            dnsttServerPublicKey = validKey)
        assertTrue(profile.validate().contains("Server port must be between 1 and 65535"))
    }
}
