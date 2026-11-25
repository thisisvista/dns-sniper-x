package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DnsttHandshakeEvidenceTest {
    @Test
    fun runningClientWithoutHandshakeSignalIsNotReportedAsHandshakePass() {
        val result = DnsttMobileE2eEngine().handshakeStateOnlyResult(125)

        assertEquals(E2eStatus.NOT_TESTED, result.status)
        assertEquals(E2ePhase.HANDSHAKE, result.phase)
        assertFalse(result.isPass)
        assertEquals(E2eStatus.NOT_TESTED, E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result))
        assertNotNull(result.errorMessage)
    }
}
