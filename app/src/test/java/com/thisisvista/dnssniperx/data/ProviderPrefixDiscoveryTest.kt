package com.thisisvista.dnssniperx.data

import org.junit.Assert.*
import org.junit.Test

class ProviderPrefixDiscoveryTest {
    @Test fun intersectsAnnouncedPrefixWithIranAllocationsOnly() {
        val announced = IranPrefixDiscovery.parseCidr("5.160.0.0/16")!!
        val iran = listOf(
            IranPrefixDiscovery.Ipv4Range(
                IranPrefixDiscovery.parseIpv4("5.160.0.0")!!,
                IranPrefixDiscovery.parseIpv4("5.160.0.255")!!
            ),
            IranPrefixDiscovery.Ipv4Range(
                IranPrefixDiscovery.parseIpv4("8.8.8.0")!!,
                IranPrefixDiscovery.parseIpv4("8.8.8.255")!!
            )
        )
        val result = ProviderPrefixDiscovery.intersectWithIran(announced, iran)
        assertEquals(listOf("5.160.0.0/24"), result.map { it.asText() })
    }

    @Test fun samplesKeepProviderAndAsnAttributionAsCandidateEvidence() {
        val prefix = IranPrefixDiscovery.parseCidr("5.160.0.0/24")!!
        val rows = listOf(ProviderPrefixDiscovery.ProviderPrefix("irancell", 44244, prefix))
        val batch = ProviderPrefixDiscovery.sampleBatch(rows, 8, 0, 3)
        assertEquals(3, batch.candidates.size)
        assertTrue(batch.candidates.all {
            it.providerId == "irancell" && it.asn == 44244 &&
                it.ownershipStatus == OwnershipStatus.CANDIDATE
        })
        assertEquals(3L, batch.nextOffset)
    }

    @Test fun samplesAreResumableAndDoNotMixProviders() {
        val a = IranPrefixDiscovery.parseCidr("5.160.0.0/24")!!
        val b = IranPrefixDiscovery.parseCidr("5.161.0.0/24")!!
        val prefixes = listOf(
            ProviderPrefixDiscovery.ProviderPrefix("irancell", 44244, a),
            ProviderPrefixDiscovery.ProviderPrefix("mci", 197207, b)
        )
        val first = ProviderPrefixDiscovery.sampleBatch(prefixes, 5, 0, 4)
        val second = ProviderPrefixDiscovery.sampleBatch(prefixes, 5, first.nextOffset, 6)
        assertEquals(4, first.candidates.size)
        assertEquals(6, second.candidates.size)
        assertEquals(setOf("irancell"), first.candidates.map { it.providerId }.toSet())
        assertEquals(setOf("irancell", "mci"), second.candidates.map { it.providerId }.toSet())
        assertTrue(second.complete)
    }
}
