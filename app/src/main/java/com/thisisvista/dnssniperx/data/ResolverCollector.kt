package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ResolverSource(val id: String, val url: String, val description: String)
data class SourceFetchResult(val source: String, val found: Int, val inserted: Int, val error: String? = null)
data class CollectionSummary(val results: List<SourceFetchResult>) {
    val found: Int get() = results.sumOf { it.found }
    val failedSources: Int get() = results.count { it.error != null }
}

/** Downloads candidate resolvers from a small, reviewed HTTPS allowlist. */
object ResolverCollector {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_LINES_PER_SOURCE = 25_000
    private const val MAX_CHARS_PER_SOURCE = 4_000_000

    // Regional entries are listed first so their regional provenance is retained
    // when the same resolver also appears in a global source.
    val sources = listOf(
        ResolverSource(
            "iran-public-dns-directory",
            "https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/by-country/ir.txt",
            "Iran public DNS directory"
        ),
        ResolverSource(
            "public-dns-info",
            "https://public-dns.info/nameservers.csv",
            "Public DNS Info"
        ),
        ResolverSource(
            "trickest-resolvers",
            "https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt",
            "Trickest Resolvers"
        ),
        ResolverSource(
            "global-public-dns-directory",
            "https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/global/all.txt",
            "Global public DNS directory"
        )
    )

    suspend fun downloadAndParse(): Pair<CollectionSummary, List<ResolverEntity>> =
        withContext(Dispatchers.IO) {
            // Fetch the reviewed sources concurrently so one slow or unreachable
            // host does not delay every other source. awaitAll preserves source order.
            val fetched = sources.map { source ->
                async {
                    try {
                        val content = download(source.url)
                        val parsed = ResolverListParser.parse(content, source = source.id)
                            .take(MAX_LINES_PER_SOURCE)
                        SourceFetchResult(source.description, parsed.size, 0) to parsed
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        SourceFetchResult(
                            source.description, 0, 0,
                            exception.message?.take(160) ?: "Download failed"
                        ) to emptyList()
                    }
                }
            }.awaitAll()

            val results = fetched.map { it.first }
            val entries = fetched.flatMap { it.second }
            CollectionSummary(results) to entries.distinctBy { it.address.lowercase() to it.port }
        }

    private fun download(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DNS-SniperX/0.3 Android")
            setRequestProperty("Accept", "text/plain,text/csv,*/*")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP $status")

            val builder = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var lineCount = 0
                while (lineCount < MAX_LINES_PER_SOURCE) {
                    val line = reader.readLine() ?: break
                    if (builder.length + line.length > MAX_CHARS_PER_SOURCE) error("Source exceeded the size limit")
                    builder.append(line).append('\n')
                    lineCount++
                }
            }
            return builder.toString()
        } finally {
            connection.disconnect()
        }
    }
}
