package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProbeTest {
    @Test fun onePositiveAnswerIsInconclusive() {
        val first = DnsPacket.ResponseInfo("PASS", "positive", 0)
        assertTrue(DnsProbe.classifyNxDomainResponses(first, null).contains("inconclusive"))
    }
    @Test fun twoPositiveAnswersRaiseWarning() {
        val positive = DnsPacket.ResponseInfo("PASS", "positive", 0)
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, positive).startsWith("WARNING:"))
    }
    @Test fun expectedNxDomainIsRecognized() {
        val nx = DnsPacket.ResponseInfo("DNS_ERROR", "NXDOMAIN", 3)
        assertTrue(DnsProbe.classifyNxDomainResponses(nx, null).contains("expected non-existent-domain"))
    }
    @Test fun inconsistentResponsesAreInconclusive() {
        val positive = DnsPacket.ResponseInfo("PASS", "positive", 0)
        val nx = DnsPacket.ResponseInfo("DNS_ERROR", "NXDOMAIN", 3)
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, nx).contains("inconsistent"))
    }
}