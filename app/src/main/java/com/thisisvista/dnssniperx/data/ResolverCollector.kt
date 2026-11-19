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
    private const val MAX_REDIRECTS = 3
    private val allowedSourceHosts = setOf("public-dns.info", "raw.githubusercontent.com")

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

    internal fun isAllowedSourceUrl(address: String): Boolean = runCatching {
        val url = URL(address)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.lowercase() in allowedSourceHosts &&
            (url.port == -1 || url.port == 443) &&
            url.userInfo == null
    }.getOrDefault(false)

    /**
     * Reads incrementally so an oversized single line cannot be fully allocated
     * by BufferedReader.readLine() before the response-size limit is checked.
     * If the line cap is reached, one extra read distinguishes an exact-size
     * response from a silently truncated oversized response.
     */
    internal fun readBoundedContent(
        reader: BufferedReader,
        maxChars: Int = MAX_CHARS_PER_SOURCE,
        maxLines: Int = MAX_LINES_PER_SOURCE
    ): String {
        require(maxChars >= 0 && maxLines >= 0)
        val builder = StringBuilder(minOf(maxChars, 16_384))
        var lineCount = 0
        while (lineCount < maxLines) {
            val next = reader.read()
            if (next == -1) break
            if (builder.length >= maxChars) error("Source exceeded the size limit")
            val character = next.toChar()
            builder.append(character)
            if (character == '\n') lineCount++
        }
        if (lineCount >= maxLines && reader.read() != -1) {
            error("Source exceeded the line limit")
        }
        return builder.toString()
    }

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
            val entriesByEndpoint = linkedMapOf<Pair<String, Int>, ResolverEntity>()
            fetched.flatMap { it.second }.forEach { entry ->
                val key = entry.address.lowercase() to entry.port
                val existing = entriesByEndpoint[key]
                if (existing == null) {
                    entriesByEndpoint[key] = entry
                } else {
                    val mergedSources = (existing.additionalSources.lineSequence() +
                        sequenceOf(entry.source) +
                        entry.additionalSources.lineSequence())
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && it != existing.source }
                        .distinct()
                        .toList()
                    entriesByEndpoint[key] = existing.copy(additionalSources = mergedSources.joinToString("\n"))
                }
            }
            CollectionSummary(results) to entriesByEndpoint.values.toList()
        }

    private fun download(address: String): String {
        require(isAllowedSourceUrl(address)) { "Source URL is not on the HTTPS allowlist" }
        var currentUrl = address

        for (redirectCount in 0..MAX_REDIRECTS) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DNS-SniperX/0.3 Android")
                setRequestProperty("Accept", "text/plain,text/csv,*/*")
            }

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        return readBoundedContent(reader)
                    }
                }

                if (status in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) error("Too many source redirects")
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect response did not include a location")
                    val redirectedUrl = URL(URL(currentUrl), location).toString()
                    require(isAllowedSourceUrl(redirectedUrl)) {
                        "Source redirect target is not on the HTTPS allowlist"
                    }
                    currentUrl = redirectedUrl
                } else {
                    error("HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Unable to download source")
    }
}
