package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlipNetE2eExportFormatterTest {
    @Test
    fun exportsOnlyFullHttpVerifiedIpv4ResolversAndIncludesEngineEvidence() {
        val result = SlipNetE2eExportFormatter.format(
            listOf(
                row(address = "1.1.1.1"),
                row(address = "8.8.8.8", engine = "VAYDNS", profileId = "vaydns-1", httpStatus = 204),
                row(address = "9.9.9.9", status = "HANDSHAKE_PASS", httpStatus = null),
                row(address = "4.2.2.1", httpStatus = 500),
                row(address = "2001:4860:4860::8888"),
                row(address = "256.1.1.1"),
                row(address = "4.4.4.4", port = 5353)
            )
        )

        assertEquals("1.1.1.1\n8.8.8.8\n", result.resolverListText)
        assertEquals(2, result.exportedCount)
        assertEquals(5, result.skippedCount)
        assertTrue(result.reportJson.contains("\"schema\": \"dns-sniperx-slipnet-e2e-v1\""))
        assertTrue(result.reportJson.contains("\"engine\": \"DNSTT\""))
        assertTrue(result.reportJson.contains("\"engine\": \"VAYDNS\""))
        assertTrue(result.reportJson.contains("\"strength\": \"FULL_HTTP\""))
        assertTrue(result.reportJson.contains("\"dnsttServerPublicKey\":"))
        assertTrue(result.reportJson.contains("\"network\""))
    }

    @Test
    fun duplicateResolverAndSameProfileIsExportedOnce() {
        val first = row(testedAt = 100)
        val later = row(testedAt = 200, elapsedMs = 1200)

        val result = SlipNetE2eExportFormatter.format(listOf(first, later))

        assertEquals("1.1.1.1\n", result.resolverListText)
        assertEquals(1, result.exportedCount)
        assertTrue(result.reportJson.contains("\"lastSuccessAt\": 200"))
    }

    @Test
    fun jsonEscapesProfileAndNetworkText() {
        val result = SlipNetE2eExportFormatter.format(
            listOf(row(profileName = "Test \"DNSTT\" \\ profile", networkLabel = "Wi-Fi\nHome"))
        )

        assertTrue(result.reportJson.contains("Test \\\"DNSTT\\\" \\\\ profile"))
        assertTrue(result.reportJson.contains("Wi-Fi\\nHome"))
        assertFalse(result.reportJson.contains("Wi-Fi\nHome"))
    }

    private fun row(
        address: String = "1.1.1.1",
        port: Int = 53,
        testedAt: Long = 100,
        engine: String = "DNSTT",
        profileId: String = "dnstt-profile",
        profileName: String = "Test DNSTT",
        status: String = "FULL_PASS",
        mode: String = "FULL",
        httpStatus: Int? = 204,
        elapsedMs: Long = 2500,
        networkLabel: String? = "Mobile"
    ) = SlipNetE2eExportRow(
        resolverAddress = address,
        resolverPort = port,
        testedAt = testedAt,
        networkKey = "CELLULAR:mci",
        networkTransport = "CELLULAR",
        networkLabel = networkLabel,
        operatorName = "MCI",
        providerId = "mci",
        profileId = profileId,
        profileName = profileName,
        tunnelProtocol = engine,
        serverHost = "tunnel.example.test",
        serverPort = 53,
        dnsttServerPublicKey = "a".repeat(64),
        slipstreamTlsServerName = null,
        verificationMode = mode,
        status = status,
        elapsedMs = elapsedMs,
        httpStatusCode = httpStatus
    )
}
