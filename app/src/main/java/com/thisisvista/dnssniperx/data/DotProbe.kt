package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

data class DotProvider(val name: String, val hostname: String, val port: Int = 853)
data class DotProbeResult(val provider: String, val hostname: String, val status: String, val latencyMs: Long?, val detail: String)

object DotProbe {
    val providers = listOf(
        DotProvider("Cloudflare", "one.one.one.one"),
        DotProvider("Google", "dns.google"),
        DotProvider("Quad9", "dns.quad9.net"),
        DotProvider("AdGuard", "dns.adguard-dns.com")
    )
    private const val TIMEOUT_MS = 4_000
    private const val MAX_RESPONSE_BYTES = 4_096

    suspend fun testAll(): List<DotProbeResult> = withContext(Dispatchers.IO) {
        providers.map { test(it) }
    }

    private fun test(provider: DotProvider): DotProbeResult {
        val started = System.nanoTime()
        var rawSocket: Socket? = null
        var tlsSocket: SSLSocket? = null
        return try {
            require(provider in providers && provider.port == 853) { "DoT endpoint is not in the built-in allowlist" }
            val query = DnsPacket.buildQuery("example.com", Random.nextInt(0, 65_536))
            rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(provider.hostname, provider.port), TIMEOUT_MS)
            rawSocket.soTimeout = TIMEOUT_MS
            tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, provider.hostname, provider.port, true) as SSLSocket
            tlsSocket.soTimeout = TIMEOUT_MS
            val parameters = tlsSocket.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            tlsSocket.sslParameters = parameters
            tlsSocket.startHandshake()

            val output = DataOutputStream(tlsSocket.outputStream)
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(tlsSocket.inputStream)
            val length = input.readUnsignedShort()
            require(length in 12..MAX_RESPONSE_BYTES) { "Invalid DoT DNS response length" }
            val response = ByteArray(length)
            input.readFully(response)
            val validation = DnsPacket.validateResponse(query, response)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            DotProbeResult(provider.name, provider.hostname, validation.status, elapsed, validation.detail)
        } catch (_: SocketTimeoutException) {
            DotProbeResult(provider.name, provider.hostname, "TIMEOUT", null, "DNS-over-TLS request timed out")
        } catch (exception: Exception) {
            DotProbeResult(provider.name, provider.hostname, "FAIL", null, (exception.message ?: "TLS or DNS response error").take(180))
        } finally {
            try { tlsSocket?.close() } catch (_: Exception) { }
            try { if (tlsSocket == null) rawSocket?.close() } catch (_: Exception) { }
        }
    }
}
