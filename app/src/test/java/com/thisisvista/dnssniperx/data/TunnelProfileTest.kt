package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProfileTest {
    @Test fun validDnsttProfilePasses() {
        val profile = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 5300, dnsttServerPublicKey = "public-key")
        assertTrue(profile.validate().toString(), profile.isValid())
    }

    @Test fun dnsttRequiresPublicKey() {
        val profile = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 53)
        assertTrue(profile.validate().contains("DNSTT server public key is required"))
    }

    @Test fun slipstreamRequiresTlsName() {
        val profile = TunnelProfile("s1", "Test", TunnelProtocol.SLIPSTREAM, "192.0.2.10", 443)
        assertTrue(profile.validate().contains("Slipstream TLS server name is required"))
    }

    @Test fun protocolSpecificFieldsCannotBeMixed() {
        val profile = TunnelProfile("s1", "Test", TunnelProtocol.SLIPSTREAM, "192.0.2.10", 443,
            dnsttServerPublicKey = "not-for-slipstream", slipstreamTlsServerName = "t.example.net")
        assertFalse(profile.isValid())
    }

    @Test fun invalidPortIsRejected() {
        val profile = TunnelProfile("d1", "Test", TunnelProtocol.DNSTT, "t.example.net", 70000,
            dnsttServerPublicKey = "public-key")
        assertTrue(profile.validate().contains("Server port must be between 1 and 65535"))
    }
}
