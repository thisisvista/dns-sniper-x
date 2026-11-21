package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

data class ProbeResult(val status: String, val latencyMs: Long? = null, val detail: String? = null)
data class ResolverScanResult(val resolverId: Long, val udp: ProbeResult, val tcp: ProbeResult)

object DnsProbe {
    private const val TIMEOUT_MS = 1_800
    private const val DEFAULT_PARALLEL_RESOLVERS = 4
    private const val MAX_PARALLEL_RESOLVERS = 8
    private const val MAX_BATCH_SIZE = 500
    private const val TEST_NAME = "example.com"
    private const val NXDOMAIN_TEST_SUFFIX = "dns-sniperx-check.invalid"

    /**
     * Scans at a conservative default concurrency. Callers may request a faster
     * mode, but the engine never exceeds the hard safety cap.
     */
    internal fun normalizeParallelism(requested: Int): Int =
        requested.coerceIn(1, MAX_PARALLEL_RESOLVERS)

    suspend fun scan(
        resolvers: List<ResolverEntity>,
        parallelism: Int = DEFAULT_PARALLEL_RESOLVERS,
        onResult: suspend (ResolverScanResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(normalizeParallelism(parallelism))
        coroutineScope {
            resolvers.take(MAX_BATCH_SIZE).map { resolver ->
                async {
                    semaphore.withPermit {
                        // Let an active resolver finish and save its result before honoring pause.
                        // This prevents completed probes from being lost when a batch is cancelled.
                        withContext(NonCancellable) {
                            val result = ResolverScanResult(
                                resolver.id,
                                probe(resolver, useTcp = false),
                                probe(resolver, useTcp = true)
                            )
                            onResult(result)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun probe(resolver: ResolverEntity, useTcp: Boolean): ProbeResult {
        val started = System.nanoTime()
        return try {
            val address = InetAddress.getByName(resolver.address)
            val transactionId = Random.nextInt(0, 65_536)
            val query = DnsPacket.buildQuery(TEST_NAME, transactionId, includeEdns = true)
            val response = if (useTcp) tcpQuery(address, resolver.port, query)
                else udpQuery(address, resolver.port, query)
            val validation = DnsPacket.validateResponse(query, response)

            // Some older DNS servers reject EDNS with FORMERR or NOTIMP.
            // Retry once without OPT so a usable legacy resolver is not discarded.
            val baseResult = if (validation.status == "DNS_ERROR" &&
                (validation.responseCode == 1 || validation.responseCode == 4 || validation.responseCode == 16)
            ) {
                val legacyQuery = DnsPacket.buildQuery(TEST_NAME, Random.nextInt(0, 65_536), includeEdns = false)
                val legacyResponse = if (useTcp) tcpQuery(address, resolver.port, legacyQuery)
                    else udpQuery(address, resolver.port, legacyQuery)
                val legacyValidation = DnsPacket.validateResponse(legacyQuery, legacyResponse)
                val fallbackDetail = if (legacyValidation.status == "PASS") {
                    "EDNS was rejected (RCODE ${validation.responseCode}); legacy DNS query succeeded. ${legacyValidation.detail}"
                } else {
                    "EDNS was rejected (RCODE ${validation.responseCode}); legacy retry: ${legacyValidation.detail}"
                }
                ProbeResult(
                    legacyValidation.status,
                    (System.nanoTime() - started) / 1_000_000,
                    fallbackDetail
                )
            } else {
                ProbeResult(validation.status, (System.nanoTime() - started) / 1_000_000, validation.detail)
            }
            // Extended diagnostics add extra DNS traffic. Skip them when the
            // baseline transport check did not receive a valid answer.
            if (!shouldRunExtendedDiagnostics(baseResult.status)) {
                baseResult
            } else {
                val withEdnsDiagnostic = addEdnsPayloadDiagnostic(address, resolver.port, useTcp, baseResult)
                val withNxDomainDiagnostic = addNxDomainDiagnostic(address, resolver.port, useTcp, withEdnsDiagnostic)
                addRecursionDiagnostic(address, resolver.port, useTcp, withNxDomainDiagnostic)
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult("TIMEOUT", detail = "No DNS response before timeout")
        } catch (exception: Exception) {
            ProbeResult("FAIL", detail = exception.message?.take(120) ?: "Network error")
        }
    }

    /**
     * Compares DNSSEC-sized UDP answers with two EDNS payload advertisements.
     * This is diagnostic only: a large response can be affected by path MTU,
     * fragmentation, filtering, or the resolver's own response policy.
     */
    private fun addEdnsPayloadDiagnostic(
        address: InetAddress,
        port: Int,
        useTcp: Boolean,
        base: ProbeResult
    ): ProbeResult {
        if (useTcp) return base
        fun attempt(payloadSize: Int): DnsPacket.ResponseInfo? = try {
            val query = DnsPacket.buildQuery(
                "org",
                Random.nextInt(0, 65_536),
                includeEdns = true,
                requestDnssec = true,
                queryType = 48, // DNSKEY
                udpPayloadSize = payloadSize
            )
            val response = udpQuery(address, port, query)
            DnsPacket.validateResponse(query, response)
        } catch (_: Exception) {
            null
        }

        val standard = attempt(1232)
        val large = attempt(4096)
        val diagnostic = when {
            standard?.status == "PASS" && large?.status == "PASS" ->
                "EDNS payload check: DNSSEC-sized UDP answer succeeded at 1232 and 4096 bytes"
            standard?.status == "TRUNCATED" && large?.status == "PASS" ->
                "EDNS payload check: 1232-byte response truncated; 4096-byte request succeeded"
            standard?.status == "PASS" && large?.status == "TRUNCATED" ->
                "EDNS payload check: 1232-byte request succeeded; 4096-byte response was truncated"
            standard == null && large == null ->
                "EDNS payload check unavailable (DNSKEY query timed out or failed)"
            else -> {
                val a = standard?.let { "1232=" + it.status + "/RCODE" + it.responseCode } ?: "1232=unavailable"
                val b = large?.let { "4096=" + it.status + "/RCODE" + it.responseCode } ?: "4096=unavailable"
                "EDNS payload check inconclusive (" + a + "; " + b + ")"
            }
        }
        return appendDiagnostic(base, diagnostic)
    }

    /**
     * Records the resolver's RA/AA flags. RA advertises recursion availability;
     * it is not proof that arbitrary recursive lookups succeed.
     */
    private fun addRecursionDiagnostic(
        address: InetAddress, port: Int, useTcp: Boolean, base: ProbeResult
    ): ProbeResult {
        return try {
            val query = DnsPacket.buildQuery(TEST_NAME, Random.nextInt(0, 65_536), includeEdns = false)
            val response = if (useTcp) tcpQuery(address, port, query) else udpQuery(address, port, query)
            val info = DnsPacket.validateResponse(query, response)
            val diagnostic = when {
                info.recursionAvailable -> "Recursion advertised (RA flag set; recursive resolution is not independently proven)"
                info.authoritativeAnswer -> "Authoritative answer (AA flag set); recursive service not confirmed"
                else -> "RA flag not set; recursive service not confirmed"
            }
            appendDiagnostic(base, diagnostic)
        } catch (exception: Exception) {
            appendDiagnostic(base, "Recursion flag check unavailable: ${exception.message?.take(70) ?: "network error"}")
        }
    }

    /**
     * A random name below the reserved .invalid TLD should not resolve.
     * An unexpected positive answer is a warning signal for wildcard DNS or
     * possible response interception, not proof of malicious behavior.
     */
    private fun addNxDomainDiagnostic(
        address: InetAddress, port: Int, useTcp: Boolean, base: ProbeResult
    ): ProbeResult {
        return try {
            fun randomInvalidQuery(): DnsPacket.ResponseInfo {
                val label = "x${Random.nextLong().toString(16)}"
                val query = DnsPacket.buildQuery("$label.$NXDOMAIN_TEST_SUFFIX", Random.nextInt(0, 65_536), includeEdns = false)
                val response = if (useTcp) tcpQuery(address, port, query) else udpQuery(address, port, query)
                return DnsPacket.validateResponse(query, response)
            }
            val first = randomInvalidQuery()
            val second = if (first.status == "PASS") { try { randomInvalidQuery() } catch (_: Exception) { null } } else null
            val third = if (second?.status == "PASS") { try { randomInvalidQuery() } catch (_: Exception) { null } } else null
            val diagnostic = classifyNxDomainResponses(first, second, third)
            appendDiagnostic(base, diagnostic)
        } catch (exception: Exception) {
            val diagnostic = "NXDOMAIN check unavailable: ${exception.message?.take(80) ?: "network error"}"
            appendDiagnostic(base, diagnostic)
        }
    }


    internal fun shouldRunExtendedDiagnostics(status: String): Boolean = status == "PASS"

    internal fun appendDiagnostic(base: ProbeResult, diagnostic: String, maxLength: Int = 360): ProbeResult {
        val entries = listOfNotNull(
            base.detail?.takeIf { it.isNotBlank() },
            diagnostic.takeIf { it.isNotBlank() }
        )
        val prioritized = entries.filter { it.contains("WARNING:", ignoreCase = true) } +
            entries.filterNot { it.contains("WARNING:", ignoreCase = true) }
        return base.copy(detail = prioritized.joinToString("; ").take(maxLength).ifBlank { null })
    }

    internal fun buildDiagnosticNote(udpDetail: String?, tcpDetail: String?): String? {
        val entries = listOfNotNull(
            udpDetail?.takeIf { it.isNotBlank() }?.let { "UDP: $it" },
            tcpDetail?.takeIf { it.isNotBlank() }?.let { "TCP: $it" }
        )
        if (entries.isEmpty()) return null
        // Keep anomaly warnings first so the UI warning filter and retry action
        // cannot lose them when the combined diagnostic is shortened.
        val prioritized = entries.filter { it.contains("WARNING:", ignoreCase = true) } +
            entries.filterNot { it.contains("WARNING:", ignoreCase = true) }
        return prioritized.joinToString("; ").take(240).ifBlank { null }
    }

    internal fun classifyNxDomainResponses(
        first: DnsPacket.ResponseInfo,
        second: DnsPacket.ResponseInfo?,
        third: DnsPacket.ResponseInfo? = null
    ): String = when {
        first.responseCode == 3 -> "NXDOMAIN check: expected non-existent-domain response"
        first.status == "PASS" && second?.status == "PASS" && third?.status == "PASS" ->
            "WARNING: three random reserved .invalid names unexpectedly resolved; possible wildcard DNS or response interception"
        first.status == "PASS" && second?.responseCode == 3 ->
            "NXDOMAIN check inconclusive: random-name responses were inconsistent"
        first.status == "PASS" && second?.status == "PASS" && third?.responseCode == 3 ->
            "NXDOMAIN check inconclusive: third random-name response was NXDOMAIN"
        first.status == "PASS" && (second == null || (second.status == "PASS" && third == null)) ->
            "NXDOMAIN check inconclusive: confirmation queries were unavailable"
        first.status == "PASS" -> "NXDOMAIN check inconclusive: random-name responses were not consistently positive"
        first.responseCode == 0 -> "NXDOMAIN check inconclusive: NOERROR without an address"
        else -> "NXDOMAIN check inconclusive: RCODE ${first.responseCode}"
    }
    private fun udpQuery(address: InetAddress, port: Int, query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            // Connect the UDP socket to the selected resolver. The OS then
            // filters datagrams from other source addresses/ports, preventing
            // unrelated or spoofed packets from being accepted as DNS replies.
            socket.connect(InetSocketAddress(address, port))
            socket.send(DatagramPacket(query, query.size))
            val packet = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(packet)
            return packet.data.copyOf(packet.length)
        }
    }

    private fun tcpQuery(address: InetAddress, port: Int, query: ByteArray): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            if (length < 12 || length > 4096) error("Invalid DNS response length")
            return ByteArray(length).also { input.readFully(it) }
        }
    }
}
