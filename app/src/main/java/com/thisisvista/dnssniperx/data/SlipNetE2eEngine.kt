package com.thisisvista.dnssniperx.data

/**
 * Boundary between SniperX's discovery/database pipeline and the real SlipNet
 * tunnel verification engine.
 *
 * Implementations must start the selected native/Go tunnel client. DNS replies,
 * UDP/TCP reachability, EDNS probes, and tunnel-shaped DNS probes are not E2E.
 */
interface SlipNetE2eEngine {
    suspend fun verify(
        request: SlipNetE2eRequest,
        onPhase: (String) -> Unit = {}
    ): SlipNetE2eResult
}

enum class E2eVerificationMode {
    /** Real tunnel handshake only. Does not claim that arbitrary HTTP traffic works. */
    HANDSHAKE,
    /** Real tunnel plus an application-level request routed through the tunnel. */
    FULL
}

enum class E2ePhase {
    NOT_STARTED,
    TUNNEL_SETUP,
    HANDSHAKE,
    APPLICATION_REQUEST,
    COMPLETED
}

enum class E2eStatus {
    NOT_CONFIGURED,
    NOT_TESTED,
    HANDSHAKE_PASS,
    HANDSHAKE_FAIL,
    FULL_PASS,
    FULL_FAIL
}

data class SlipNetE2eRequest(
    val resolverAddress: String,
    val resolverPort: Int,
    val profile: TunnelProfile,
    val mode: E2eVerificationMode,
    val timeoutMs: Long = 15_000,
    val verificationUrl: String = "http://www.gstatic.com/generate_204"
) {
    fun validate(): List<String> = buildList {
        if (resolverAddress.isBlank() || resolverAddress.any(Char::isWhitespace)) {
            add("Resolver address is required")
        }
        if (resolverPort !in 1..65535) add("Resolver port must be between 1 and 65535")
        addAll(profile.validate())
        if (timeoutMs !in 1_000..120_000) add("E2E timeout must be between 1 and 120 seconds")
        if (mode == E2eVerificationMode.FULL &&
            (!verificationUrl.startsWith("http://") && !verificationUrl.startsWith("https://"))
        ) add("Full verification requires an HTTP or HTTPS URL")
    }
}

data class SlipNetE2eResult(
    val status: E2eStatus,
    val phase: E2ePhase,
    val elapsedMs: Long,
    val errorMessage: String? = null,
    val httpStatusCode: Int? = null
) {
    val isPass: Boolean
        get() = status == E2eStatus.HANDSHAKE_PASS || status == E2eStatus.FULL_PASS

    companion object {
        fun notConfigured(reason: String = "SlipNet tunnel engine is not integrated"): SlipNetE2eResult =
            SlipNetE2eResult(
                status = E2eStatus.NOT_CONFIGURED,
                phase = E2ePhase.NOT_STARTED,
                elapsedMs = 0,
                errorMessage = reason
            )
    }
}

/**
 * Central result gate. A result from a weaker verification mode can never be
 * promoted to a stronger status (e.g. handshake-only cannot become FULL_PASS).
 */
object E2eResultGate {
    fun accept(
        requestedMode: E2eVerificationMode,
        result: SlipNetE2eResult
    ): E2eStatus {
        if (!result.isPass || result.phase != E2ePhase.COMPLETED) {
            return when (requestedMode) {
                E2eVerificationMode.HANDSHAKE -> E2eStatus.HANDSHAKE_FAIL
                E2eVerificationMode.FULL -> E2eStatus.FULL_FAIL
            }
        }
        return when (requestedMode) {
            E2eVerificationMode.HANDSHAKE ->
                if (result.status == E2eStatus.HANDSHAKE_PASS ||
                    result.status == E2eStatus.FULL_PASS
                ) E2eStatus.HANDSHAKE_PASS else E2eStatus.HANDSHAKE_FAIL
            E2eVerificationMode.FULL ->
                if (result.status == E2eStatus.FULL_PASS) E2eStatus.FULL_PASS
                else E2eStatus.FULL_FAIL
        }
    }
}
