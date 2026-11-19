package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SlipNetExportFormatterTest {
    @Test
    fun exportsOnlyWorkingIpv4ResolversOnPort53OnePerLine() {
        val result = SlipNetExportFormatter.format(
            listOf(
                ResolverEntity(address = "1.1.1.1", udpStatus = "PASS", udpLatencyMs = 12),
                ResolverEntity(address = "8.8.8.8", port = 5353, tcpStatus = "PASS", tcpLatencyMs = 20),
                ResolverEntity(address = "2001:4860:4860::8888", udpStatus = "PASS", udpLatencyMs = 5),
                ResolverEntity(address = "9.9.9.9", udpStatus = "TIMEOUT"),
                ResolverEntity(address = "4.2.2.1", tcpStatus = "PASS", tcpLatencyMs = 30)
            )
        )

        assertEquals("1.1.1.1\n4.2.2.1\n", result.text)
        assertEquals(2, result.exportedCount)
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun removesDuplicateAddressesAndSortsBySuccessfulLatency() {
        val result = SlipNetExportFormatter.format(
            listOf(
                ResolverEntity(address = "1.1.1.1", udpStatus = "PASS", udpLatencyMs = 50),
                ResolverEntity(address = "8.8.8.8", tcpStatus = "PASS", tcpLatencyMs = 10),
                ResolverEntity(address = "1.1.1.1", tcpStatus = "PASS", tcpLatencyMs = 20)
            )
        )

        assertEquals("8.8.8.8\n1.1.1.1\n", result.text)
        assertEquals(2, result.exportedCount)
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun rejectsInvalidIpv4LiteralsAndReturnsEmptyForNoCompatibleEntries() {
        val result = SlipNetExportFormatter.format(
            listOf(
                ResolverEntity(address = "256.1.1.1", udpStatus = "PASS"),
                ResolverEntity(address = "01.2.3.4", udpStatus = "PASS"),
                ResolverEntity(address = "resolver.example", udpStatus = "PASS")
            )
        )

        assertEquals("", result.text)
        assertEquals(0, result.exportedCount)
        assertEquals(3, result.skippedCount)
    }
}
