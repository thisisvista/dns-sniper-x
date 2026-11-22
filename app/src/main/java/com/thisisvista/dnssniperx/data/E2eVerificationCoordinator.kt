package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.CancellationException

/**
 * Runs a selected tunnel adapter against one resolver and persists immutable
 * evidence for that attempt. Resolver/network/provider identities are kept
 * separate: a successful route on a mobile network is not resolver ownership.
 */
class E2eVerificationCoordinator(
    private val historyDao: E2eHistoryDao,
    private val engines: Map<TunnelProtocol, SlipNetE2eEngine>
) {
    suspend fun verify(
        resolverAddress: String,
        resolverPort: Int,
        profile: TunnelProfile,
        mode: E2eVerificationMode,
        network: ActiveNetworkState,
        timeoutMs: Long = 15_000,
        verificationUrl: String = "http://www.gstatic.com/generate_204",
        expectedHttpStatusCode: Int? = 204,
        onPhase: (String) -> Unit = {}
    ): SlipNetE2eResult {
        require(resolverAddress.isNotBlank()) { "Resolver address is required" }
        require(resolverPort in 1..65535) { "Resolver port must be between 1 and 65535" }
        require(profile.isValid()) { profile.validate().joinToString("; ") }

        if (!network.isConnected || network.transport == ActiveNetworkTransport.NONE) {
            return SlipNetE2eResult(
                status = E2eStatus.NOT_TESTED,
                phase = E2ePhase.NOT_STARTED,
                elapsedMs = 0,
                errorMessage = "No active network is available for E2E verification"
            )
        }

        val request = SlipNetE2eRequest(
            resolverAddress = resolverAddress,
            resolverPort = resolverPort,
            profile = profile,
            mode = mode,
            timeoutMs = timeoutMs,
            verificationUrl = verificationUrl,
            expectedHttpStatusCode = expectedHttpStatusCode
        )
        val startedAt = System.nanoTime()
        val engine = engines[profile.protocol]
        val rawResult = if (engine == null) {
            SlipNetE2eResult.notConfigured(
                "No ${profile.protocol.name} E2E adapter is installed in this build"
            )
        } else {
            try {
                engine.verify(request, onPhase)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SlipNetE2eResult(
                    status = if (mode == E2eVerificationMode.FULL) E2eStatus.FULL_FAIL else E2eStatus.HANDSHAKE_FAIL,
                    phase = E2ePhase.NOT_STARTED,
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                    errorMessage = failure.message ?: failure.javaClass.simpleName
                )
            }
        }
        val acceptedStatus = E2eResultGate.accept(mode, rawResult)
        val result = rawResult.copy(status = acceptedStatus)

        historyDao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = resolverAddress,
                resolverPort = resolverPort,
                networkKey = networkKey(network),
                networkTransport = network.transport.name,
                networkLabel = network.providerName ?: network.operatorName,
                operatorName = network.operatorName,
                providerId = network.providerId,
                profileId = profile.id,
                tunnelProtocol = profile.protocol.name,
                verificationMode = mode.name,
                status = result.status.name,
                phase = result.phase.name,
                elapsedMs = result.elapsedMs,
                httpStatusCode = result.httpStatusCode,
                errorMessage = result.errorMessage
            )
        )
        return result
    }

    private fun networkKey(network: ActiveNetworkState): String {
        val operator = network.operatorName
            ?.trim()
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.isNotEmpty() }
        return when (network.transport) {
            ActiveNetworkTransport.CELLULAR -> "CELLULAR:${operator ?: "unknown"}"
            else -> network.transport.name
        }
    }
}
