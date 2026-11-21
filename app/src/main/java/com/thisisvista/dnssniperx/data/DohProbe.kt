package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

data class DohProvider(val name: String, val endpoint: String)
data class DohProbeResult(
    val provider: String,
    val endpoint: String,
    val status: String,
    val latencyMs: Long?,
    val detail: String
)

object DohProbe {
    val providers = listOf(
        DohProvider("Cloudflare", "https://cloudflare-dns.com/dns-query"),
        DohProvider("Google", "https://dns.google/dns-query"),
        DohProvider("Quad9", "https://dns.quad9.net/dns-query"),
        DohProvider("AdGuard", "https://dns.adguard-dns.com/dns-query")
    )

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val MAX_RESPONSE_BYTES = 4_096
    private val transactionIds = AtomicInteger(Random.nextInt(0, 65_536))

    suspend fun testAll(): List<DohProbeResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            // Four built-in providers can be checked concurrently; awaitAll preserves display order.
            providers.map { provider -> async { test(provider) } }.awaitAll()
        }
    }

    private fun test(provider: DohProvider): DohProbeResult {
        val started = System.nanoTime()
        var connection: HttpsURLConnection? = null
        return try {
            val url = URL(provider.endpoint)
            require(url.protocol == "https" && providers.any { it.endpoint == provider.endpoint }) {
                "DoH endpoint is not in the built-in HTTPS allowlist"
            }
            val query = DnsPacket.buildQuery(
                "example.com",
                transactionIds.getAndUpdate { (it + 1) and 0xffff }
            )
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("Content-Type", "application/dns-message")
                setFixedLengthStreamingMode(query.size)
            }
            connection.outputStream.use { it.write(query) }
            val httpStatus = connection.responseCode
            if (httpStatus != HttpURLConnection.HTTP_OK) {
                error("HTTPS endpoint returned HTTP $httpStatus")
            }
            val contentType = connection.contentType?.substringBefore(';')?.trim()
            val contentTypeLabel = contentType ?: "missing"
            require(contentType.equals("application/dns-message", ignoreCase = true)) {
                "Unexpected DoH content type: $contentTypeLabel"
            }
            require(connection.contentLengthLong <= MAX_RESPONSE_BYTES) {
                "DoH response exceeds $MAX_RESPONSE_BYTES bytes"
            }
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_RESPONSE_BYTES) {
                        "DoH response exceeds $MAX_RESPONSE_BYTES bytes"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val validation = DnsPacket.validateResponse(query, response)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            DohProbeResult(provider.name, provider.endpoint, validation.status, elapsed, validation.detail)
        } catch (exception: SocketTimeoutException) {
            DohProbeResult(provider.name, provider.endpoint, "TIMEOUT", null, "HTTPS request timed out")
        } catch (exception: Exception) {
            DohProbeResult(
                provider.name,
                provider.endpoint,
                "FAIL",
                null,
                (exception.message ?: "HTTPS or DNS response error").take(180)
            )
        } finally {
            connection?.disconnect()
        }
    }
}
