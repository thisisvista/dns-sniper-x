package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Fetches official RIPE NCC delegated stats and retains only Iranian IPv4 allocations. */
object IranPrefixRegistryClient {
    const val SOURCE_ID = "iran-ripe-delegated-prefixes"
    const val SOURCE_URL = "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_FILE_CHARS = 20_000_000
    private const val MAX_LINES = 300_000

    suspend fun fetchIranRanges(): List<IranPrefixDiscovery.Ipv4Range> = withContext(Dispatchers.IO) {
        val url = URL(SOURCE_URL)
        require(url.protocol == "https" && url.host == "ftp.ripe.net" && url.port in listOf(-1, 443)) {
            "RIPE source URL is not allowed"
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "DNS-SniperX/0.4 Android")
            setRequestProperty("Accept", "text/plain")
        }
        try {
            val status = connection.responseCode
            check(status == HttpURLConnection.HTTP_OK) { "RIPE source returned HTTP $status" }
            require(connection.contentLengthLong <= MAX_FILE_CHARS.toLong()) { "RIPE source exceeded the size limit" }

            val ranges = LinkedHashSet<IranPrefixDiscovery.Ipv4Range>()
            var charsRead = 0
            var lineCount = 0
            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    lineCount++
                    charsRead += line.length + 1
                    require(charsRead <= MAX_FILE_CHARS) { "RIPE source exceeded the size limit" }
                    require(lineCount <= MAX_LINES) { "RIPE source exceeded the line limit" }
                    IranPrefixDiscovery.parseDelegatedIpv4Line(line)?.let(ranges::add)
                }
            }
            ranges.sortedBy { it.start }
        } finally {
            connection.disconnect()
        }
    }
}
