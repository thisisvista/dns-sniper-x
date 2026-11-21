package com.thisisvista.dnssniperx.data

import org.junit.Assert.*
import org.junit.Test

class IranPrefixDiscoveryTest {
    @Test fun parsesOnlyIranIpv4AllocatedOrAssignedRows() {
        val input = """
            2|ripencc|20261101|0|0|0
            registry|cc|type|start|value|date|status
            ripencc|IR|ipv4|5.160.0.0|65536|20200101|allocated
            ripencc|IR|ipv6|2001:db8::|32|20200101|allocated
            ripencc|US|ipv4|8.8.8.0|256|20200101|allocated
            ripencc|IR|ipv4|5.161.0.0|256|20200101|available
        """.trimIndent()
        assertEquals(listOf(IranPrefixDiscovery.Ipv4Range(
            IranPrefixDiscovery.parseIpv4("5.160.0.0")!!,
            IranPrefixDiscovery.parseIpv4("5.160.255.255")!!
        )), IranPrefixDiscovery.parseDelegatedIpv4(input))
    }

    @Test fun convertsArbitraryRangeToExactMinimalCidrCover() {
        val start = IranPrefixDiscovery.parseIpv4("192.0.2.0")!!
        val end = IranPrefixDiscovery.parseIpv4("192.0.2.7")!!
        val cidrs = IranPrefixDiscovery.toCidrs(IranPrefixDiscovery.Ipv4Range(start, end))
        assertEquals(listOf("192.0.2.0/29"), cidrs.map { it.asText() })
        assertEquals(8L, cidrs.sumOf { it.size })
    }

    @Test fun convertsNonAlignedRangeWithoutGapsOrOverlaps() {
        val start = IranPrefixDiscovery.parseIpv4("5.160.0.3")!!
        val end = IranPrefixDiscovery.parseIpv4("5.160.0.17")!!
        val cidrs = IranPrefixDiscovery.toCidrs(IranPrefixDiscovery.Ipv4Range(start, end))
        assertEquals(15L, cidrs.sumOf { it.size })
        assertEquals(start, cidrs.first().network)
        assertEquals(end, cidrs.last().endInclusive)
        assertTrue(cidrs.zipWithNext().all { (a, b) -> a.endInclusive + 1 == b.network })
    }

    @Test fun parsesCidrAndNormalizesNetworkAddress() {
        assertEquals("5.160.0.0/16", IranPrefixDiscovery.parseCidr("5.160.9.9/16")?.asText())
        assertNull(IranPrefixDiscovery.parseCidr("999.1.1.1/24"))
        assertNull(IranPrefixDiscovery.parseCidr("5.1.1.1/33"))
    }

    @Test fun samplesAreDeterministicBoundedAndResumable() {
        val prefix = IranPrefixDiscovery.parseCidr("5.160.0.0/24")!!
        val first = IranPrefixDiscovery.sampleBatch(listOf(prefix), 10, 0, 4)
        val second = IranPrefixDiscovery.sampleBatch(listOf(prefix), 10, first.nextOffset, 6)
        val again = IranPrefixDiscovery.sampleBatch(listOf(prefix), 10, 0, 4)
        assertEquals(first.candidates.map { it.address }, again.candidates.map { it.address })
        assertEquals(4, first.candidates.size)
        assertEquals(6, second.candidates.size)
        assertEquals(10L, second.nextOffset)
        assertTrue(second.complete)
        assertEquals(10, (first.candidates + second.candidates).map { it.address }.distinct().size)
    }

    @Test fun skipsPrivateAndSpecialUsePrefixes() {
        val prefixes = listOf("10.0.0.0/8", "192.168.0.0/16", "198.51.100.0/24", "5.160.0.0/24")
            .mapNotNull(IranPrefixDiscovery::parseCidr)
        val batch = IranPrefixDiscovery.sampleBatch(prefixes, 5)
        assertTrue(batch.candidates.isNotEmpty())
        assertTrue(batch.candidates.all { it.address.startsWith("5.160.0.") })
    }

    @Test fun rejectsInvalidSamplingParameters() {
        val prefix = IranPrefixDiscovery.parseCidr("5.160.0.0/24")!!
        assertThrows(IllegalArgumentException::class.java) { IranPrefixDiscovery.sampleBatch(listOf(prefix), 100) }
        assertThrows(IllegalArgumentException::class.java) { IranPrefixDiscovery.sampleBatch(listOf(prefix), batchSize = 20_000) }
    }
}
