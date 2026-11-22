package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

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
        onPhase: (String) -> Unit = {},
        onPhaseChanged: (E2ePhase) -> Unit = {}
    ): SlipNetE2eResult {
        require(resolverAddress.isNotBlank()) { "Resolver address is required" }
        require(resolverPort in 1..65535) { "Resolver port must be between 1 and 65535" }
        require(profile.isValid()) { profile.validate().joinToString("; ") }
        require(timeoutMs in 1_000..120_000) { "E2E timeout must be between 1 and 120 seconds" }

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
        val currentPhase = AtomicReference(E2ePhase.NOT_STARTED)
        val engine = engines[profile.protocol]
        val rawResult = if (engine == null) {
            SlipNetE2eResult.notConfigured(
                "No ${profile.protocol.name} E2E adapter is installed in this build"
            )
        } else {
            try {
                withTimeout(timeoutMs) {
                    engine.verify(
                        request = request,
                        onPhase = onPhase,
                        onPhaseChanged = { phase ->
                            currentPhase.set(phase)
                            onPhaseChanged(phase)
                        }
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                SlipNetE2eResult(
                    status = E2eStatus.TIMEOUT,
                    phase = currentPhase.get(),
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                    errorMessage = "E2E verification exceeded the ${timeoutMs} ms total time limit"
                )
            } catch (cancelled: CancellationException) {
                // Cancellation is evidence too. Persist it even though the caller
                // must still receive the cancellation signal and stop its UI job.
                withContext(NonCancellable) {
                    historyDao.insertAttempt(
                        historyRecord(
                            resolverAddress = resolverAddress,
                            resolverPort = resolverPort,
                            profile = profile,
                            mode = mode,
                            network = network,
                            result = SlipNetE2eResult(
                                status = E2eStatus.CANCELLED,
                                phase = currentPhase.get(),
                                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                                errorMessage = "E2E verification was cancelled"
                            )
                        )
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                SlipNetE2eResult(
                    status = if (mode == E2eVerificationMode.FULL) E2eStatus.FULL_FAIL else E2eStatus.HANDSHAKE_FAIL,
                    phase = currentPhase.get(),
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                    errorMessage = failure.message ?: failure.javaClass.simpleName
                )
            }
        }
        val acceptedStatus = E2eResultGate.accept(mode, rawResult)
        val result = rawResult.copy(status = acceptedStatus)

        historyDao.insertAttempt(
            historyRecord(
                resolverAddress = resolverAddress,
                resolverPort = resolverPort,
                profile = profile,
                mode = mode,
                network = network,
                result = result
            )
        )
        return result
    }

    private fun historyRecord(
        resolverAddress: String,
        resolverPort: Int,
        profile: TunnelProfile,
        mode: E2eVerificationMode,
        network: ActiveNetworkState,
        result: SlipNetE2eResult
    ) = E2eHistoryEntity(
        resolverAddress = resolverAddress,
        resolverPort = resolverPort,
        networkKey = networkKey(network),
        networkTransport = network.transport.name,
        networkLabel = network.providerName ?: network.operatorName,
        operatorName = network.operatorName,
        providerId = network.providerId,
        profileId = profile.id,
        profileName = profile.name,
        serverHost = profile.serverHost,
        serverPort = profile.serverPort,
        dnsttServerPublicKey = profile.dnsttServerPublicKey,
        slipstreamTlsServerName = profile.slipstreamTlsServerName,
        tunnelProtocol = profile.protocol.name,
        verificationMode = mode.name,
        status = result.status.name,
        phase = result.phase.name,
        elapsedMs = result.elapsedMs,
        httpStatusCode = result.httpStatusCode,
        errorMessage = result.errorMessage
    )

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
