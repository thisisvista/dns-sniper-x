package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverListParserTest {
    @Test fun parsesIpv4AndCustomPort() {
        val result = ResolverListParser.parse("1.1.1.1\n8.8.8.8:5353")
        assertEquals(2, result.size)
        assertEquals(53, result[0].port)
        assertEquals(5353, result[1].port)
    }

    @Test fun parsesBracketedIpv6AndHostname() {
        val result = ResolverListParser.parse("[2001:db8::1]:5353\nresolver.example")
        assertEquals(2, result.size)
        assertEquals(5353, result[0].port)
        assertEquals("resolver.example", result[1].address)
    }

    @Test fun rejectsMalformedIpv6Addresses() {
        val result = ResolverListParser.parse("[::::]:53\n[1:2:3]:53\n2001:db8::1")
        assertEquals(listOf("2001:db8::1"), result.map { it.address })
    }

    @Test fun rejectsInvalidPortsForIpv4AndIpv6Endpoints() {
        val result = ResolverListParser.parse(
            "1.1.1.1:0\n1.1.1.1:65536\n1.1.1.1:not-a-port\n" +
                "[2001:db8::1]:0\n[2001:db8::1]:65536\n[2001:db8::1]:abc"
        )
        assertTrue(result.isEmpty())
    }

    @Test fun ignoresCommentsInvalidEntriesAndDuplicates() {
        val result = ResolverListParser.parse("# public list\n1.1.1.1\n1.1.1.1\n999.1.1.1\nnot a server")
        assertEquals(1, result.size)
        assertTrue(result.all { it.status == "NOT_TESTED" })
    }

    @Test fun parsesQuotedCsvAddressesAndUtf8Bom() {
        val result = ResolverListParser.parse("\uFEFF\"1.2.3.4\",53,IR\n\"8.8.4.4\",53,US")
        assertEquals(listOf("1.2.3.4", "8.8.4.4"), result.map { it.address })
    }

    @Test fun ignoresCsvHeaderAndKeepsIpv6() {
        val result = ResolverListParser.parse("ip,port,country\n2001:4860:4860::8888,53,US")
        assertEquals(1, result.size)
        assertEquals("2001:4860:4860::8888", result.single().address)
    }

    @Test fun parsesCustomPortsFromCsvForIpv4AndIpv6() {
        val result = ResolverListParser.parse(
            "1.2.3.4,5353,IR\n2001:4860:4860::8888,5354,US"
        )
        assertEquals(2, result.size)
        assertEquals("1.2.3.4", result[0].address)
        assertEquals(5353, result[0].port)
        assertEquals("2001:4860:4860::8888", result[1].address)
        assertEquals(5354, result[1].port)
    }

    @Test fun defaultsCsvPortTo53WhenSecondColumnIsCountryCode() {
        val result = ResolverListParser.parse("1.2.3.4,IR\n2001:4860:4860::8888,US")
        assertEquals(2, result.size)
        assertTrue(result.all { it.port == 53 })
    }

    @Test fun rejectsInvalidNumericCsvPorts() {
        val result = ResolverListParser.parse(
            "1.2.3.4,0,IR\n8.8.8.8,65536,US"
        )
        assertTrue(result.isEmpty())
    }

    @Test fun importsWhiteDnsResolverCsvRowsAsUntestedCandidates() {
        // Header and fields mirror WhiteDNS internal/dnsscan/report.go writeCSV.
        val result = ResolverListParser.parse(
            "ip,status,color,score,responded,udp,tcp,preferred_transport,fallback_transport," +
                "udp_poisoned,tcp_poisoned,transport_disagreement,injection_observed,ra,aa,tc,rd," +
                "rcodes,qd_records,an_records,edns,txt_pass,ns_records,ar_records,poisoned,poison_ip," +
                "hijacked,hijack_confidence,hijack_reason,hijack_udp,hijack_tcp,hijack_checks," +
                "hijack_anomalies,hijack_rcodes,hijack_ip,tunnel_ready,latency_ms,nearby,reason\n" +
                "1.1.1.1,valid,green,6,true,true,true,udp,,false,false,false,false,true,false,false,true," +
                "NOERROR,1,1,true,true,0,1,false,,false,none,,false,false,0,0,, ,true,12,false,clean\n" +
                "8.8.8.8,invalid,red,1,false,false,false,,,false,false,false,false,false,false,false,true," +
                "TIMEOUT,1,0,false,false,0,0,false,,false,none,,false,false,0,0,, ,false,0,false,timeout"
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.map { it.address })
        assertTrue(result.all { it.port == 53 })
        assertTrue(result.all { it.status == "NOT_TESTED" })
        assertTrue(result.all { it.source == "manual" })
    }

    @Test fun importsWhiteDnsDetailedTxtReportLinesAsUntestedCandidates() {
        val result = ResolverListParser.parse(
            "WhiteDNS Android DNS Results\n" +
                "[PASSED] 1\n" +
                "1.1.1.1  status=valid  score=6/6  latency=12ms  tunnel=true\n" +
                "8.8.8.8  status=invalid  score=1/6  tunnel=false"
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.map { it.address })
        assertTrue(result.all { it.port == 53 })
        assertTrue(result.all { it.status == "NOT_TESTED" })
    }

    @Test fun importsIpv6FromWhiteDnsDetailedTxtReport() {
        val result = ResolverListParser.parse(
            "2001:4860:4860::8888  status=valid  score=6/6  tunnel=true"
        )

        assertEquals(1, result.size)
        assertEquals("2001:4860:4860::8888", result.single().address)
        assertEquals(53, result.single().port)
        assertEquals("NOT_TESTED", result.single().status)
    }

    @Test fun importsWhiteDnsPassedRawTxtLinesAsUntestedCandidates() {
        val result = ResolverListParser.parse(
            "1.1.1.1\n8.8.8.8\n1.1.1.1",
            source = "whitedns-passed-raw"
        )

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.map { it.address })
        assertTrue(result.all { it.port == 53 })
        assertTrue(result.all { it.status == "NOT_TESTED" })
        assertTrue(result.all { it.source == "whitedns-passed-raw" })
    }

    @Test fun acceptsFullyQualifiedHostnamesWithTrailingDot() {
        val result = ResolverListParser.parse(
            "resolver.example.\ndns.google.,53,US"
        )
        assertEquals(2, result.size)
        assertEquals("resolver.example.", result[0].address)
        assertEquals("dns.google.", result[1].address)
        assertTrue(result.all { it.port == 53 })
    }

    @Test fun parsesCustomPortsForHostnamesAndPreservesExplicitEndpointPorts() {
        val result = ResolverListParser.parse(
            "resolver.example,5353,US\n1.2.3.4:5354,53,IR"
        )
        assertEquals(2, result.size)
        assertEquals("resolver.example", result[0].address)
        assertEquals(5353, result[0].port)
        assertEquals("1.2.3.4", result[1].address)
        assertEquals(5354, result[1].port)
    }
}
