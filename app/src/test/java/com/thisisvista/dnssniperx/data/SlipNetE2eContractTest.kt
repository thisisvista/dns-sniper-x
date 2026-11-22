package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlipNetE2eContractTest {
    private val profile = TunnelProfile(
        id = "test-profile",
        name = "Test",
        protocol = TunnelProtocol.DNSTT,
        serverHost = "tunnel.example",
        serverPort = 443,
        dnsttServerPublicKey = "0123456789abcdef"
    )

    @Test
    fun handshakePassCannotBePromotedToFullPass() {
        val result = SlipNetE2eResult(
            status = E2eStatus.HANDSHAKE_PASS,
            phase = E2ePhase.COMPLETED,
            elapsedMs = 1200
        )

        assertEquals(
            E2eStatus.HANDSHAKE_PASS,
            E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result)
        )
        assertEquals(
            E2eStatus.FULL_FAIL,
            E2eResultGate.accept(E2eVerificationMode.FULL, result)
        )
    }

    @Test
    fun unavailableEngineRemainsNotConfiguredInsteadOfBeingReportedAsFailure() {
        val result = SlipNetE2eResult.notConfigured()

        assertFalse(result.isPass)
        assertEquals(
            E2eStatus.NOT_CONFIGURED,
            E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result)
        )
        assertEquals(
            E2eStatus.NOT_CONFIGURED,
            E2eResultGate.accept(E2eVerificationMode.FULL, result)
        )
    }

    @Test
    fun unattemptedVerificationRemainsNotTested() {
        val result = SlipNetE2eResult(
            status = E2eStatus.NOT_TESTED,
            phase = E2ePhase.NOT_STARTED,
            elapsedMs = 0
        )

        assertEquals(
            E2eStatus.NOT_TESTED,
            E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result)
        )
        assertEquals(
            E2eStatus.NOT_TESTED,
            E2eResultGate.accept(E2eVerificationMode.FULL, result)
        )
    }

    @Test
    fun dnsOrTransportPassCannotBeRepresentedAsE2ePass() {
        val result = SlipNetE2eResult(
            status = E2eStatus.NOT_CONFIGURED,
            phase = E2ePhase.NOT_STARTED,
            elapsedMs = 0
        )

        assertFalse(result.isPass)
        assertEquals(E2eStatus.NOT_CONFIGURED,
            E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result))
        assertEquals(E2eStatus.NOT_CONFIGURED,
            E2eResultGate.accept(E2eVerificationMode.FULL, result))
    }

    @Test
    fun fullPassIsAcceptedOnlyForFullVerificationAndValidHttpStatus() {
        val result = SlipNetE2eResult(
            status = E2eStatus.FULL_PASS,
            phase = E2ePhase.COMPLETED,
            elapsedMs = 2500,
            httpStatusCode = 204
        )

        assertEquals(E2eStatus.HANDSHAKE_PASS,
            E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result))
        assertEquals(E2eStatus.FULL_PASS,
            E2eResultGate.accept(E2eVerificationMode.FULL, result))
    }

    @Test
    fun fullPassWithoutSuccessfulHttpProofIsRejected() {
        val result = SlipNetE2eResult(
            status = E2eStatus.FULL_PASS,
            phase = E2ePhase.COMPLETED,
            elapsedMs = 2500,
            httpStatusCode = 500
        )

        assertEquals(E2eStatus.HANDSHAKE_FAIL,
            E2eResultGate.accept(E2eVerificationMode.HANDSHAKE, result))
        assertEquals(E2eStatus.FULL_FAIL,
            E2eResultGate.accept(E2eVerificationMode.FULL, result))
    }

    @Test
    fun incompletePhaseCannotPassEvenIfEngineClaimsSuccess() {
        val result = SlipNetE2eResult(
            status = E2eStatus.FULL_PASS,
            phase = E2ePhase.APPLICATION_REQUEST,
            elapsedMs = 2500,
            httpStatusCode = 204
        )

        assertEquals(E2eStatus.FULL_FAIL,
            E2eResultGate.accept(E2eVerificationMode.FULL, result))
    }

    @Test
    fun requestRejectsInvalidResolverAndTimeout() {
        val request = SlipNetE2eRequest(
            resolverAddress = "",
            resolverPort = 70000,
            profile = profile,
            mode = E2eVerificationMode.FULL,
            timeoutMs = 100
        )

        assertEquals(3, request.validate().size)
    }
}
