package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProbeTest {
    @Test fun onePositiveAnswerIsInconclusive() {
        val positive = DnsPacket.ResponseInfo("PASS", "positive", 0)
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, null).contains("inconclusive"))
    }

    @Test fun twoPositiveAnswersAreStillInconclusive() {
        val positive = DnsPacket.ResponseInfo("PASS", "positive", 0)
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, positive).contains("inconclusive"))
    }

    @Test fun threePositiveAnswersRaiseWarning() {
        val positive = DnsPacket.ResponseInfo("PASS", "positive", 0)
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, positive, positive).startsWith("WARNING:"))
    }

    @Test fun expectedNxDomainIsRecognized() {
        val nx = DnsPacket.ResponseInfo("DNS_ERROR", "NXDOMAIN", 3)
        assertTrue(DnsProbe.classifyNxDomainResponses(nx, null).contains("expected non-existent-domain"))
    }

    @Test fun inconsistentResponsesAreInconclusive() {
        val positive = DnsPacket.ResponseInfo("PASS", "positive", 0)
        val nx = DnsPacket.ResponseInfo("DNS_ERROR", "NXDOMAIN", 3)
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, nx).contains("inconsistent"))
        assertTrue(DnsProbe.classifyNxDomainResponses(positive, positive, nx).contains("inconclusive"))
    }
}
