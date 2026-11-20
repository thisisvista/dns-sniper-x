package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ResolverSource(val id: String, val url: String, val description: String)
data class SourceFetchResult(
    val source: String,
    val found: Int,
    val inserted: Int,
    val error: String? = null,
    val sourceId: String = source,
    val sourceUrl: String = "",
    val retrievedAt: Long = System.currentTimeMillis()
)
data class CollectionSummary(val results: List<SourceFetchResult>) {
    val found: Int get() = results.sumOf { it.found }
    val inserted: Int get() = results.sumOf { it.inserted }
    val failedSources: Int get() = results.count { it.error != null }
}

/** Downloads candidate resolvers from a small, reviewed HTTPS allowlist. */
object ResolverCollector {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_LINES_PER_SOURCE = 25_000
    private const val MAX_CHARS_PER_SOURCE = 4_000_000
    private const val MAX_LINE_CHARS = 16_384
    const val INSERT_BATCH_SIZE = 500
    private const val MAX_REDIRECTS = 3
    private val allowedSourceHosts = setOf("public-dns.info", "raw.githubusercontent.com")

    // Regional entries are processed first so their provenance wins on duplicates.
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
     * Streams source lines and persists bounded batches through [onBatch].
     * Only one source and at most INSERT_BATCH_SIZE parsed rows are retained at a time.
     */
    suspend fun collectAndStore(
        onBatch: suspend (List<ResolverEntity>) -> Int,
        onProgress: suspend (sourceName: String, found: Int, inserted: Int) -> Unit = { _, _, _ -> }
    ): CollectionSummary = withContext(Dispatchers.IO) {
        val results = mutableListOf<SourceFetchResult>()
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            val retrievedAt = System.currentTimeMillis()
            var found = 0
            var inserted = 0
            val batch = ArrayList<ResolverEntity>(INSERT_BATCH_SIZE)
            var sourceError: String? = null

            suspend fun flushBatch() {
                if (batch.isNotEmpty()) {
                    inserted += onBatch(batch.toList())
                    batch.clear()
                    onProgress(source.description, found, inserted)
                }
            }

            try {
                downloadLines(source.url) { line ->
                    val normalizedLine = line.removePrefix("\uFEFF").trimEnd('\r')
                    val parsed = ResolverListParser.parse(normalizedLine, source = source.id)
                    if (parsed.isNotEmpty()) {
                        found += parsed.size
                        batch.addAll(parsed)
                        if (batch.size >= INSERT_BATCH_SIZE) flushBatch()
                    }
                }
                flushBatch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                // Keep already received rows even if a source fails partway through.
                flushBatch()
                sourceError = exception.message?.take(160) ?: "Download failed"
            }

            results += SourceFetchResult(
                source = source.description,
                found = found,
                inserted = inserted,
                error = sourceError,
                sourceId = source.id,
                sourceUrl = source.url,
                retrievedAt = retrievedAt
            )
        }
        CollectionSummary(results)
    }

    /** Kept for bounded-reader unit tests and defensive compatibility. */
    internal fun readBoundedContent(
        reader: BufferedReader,
        maxChars: Int = MAX_CHARS_PER_SOURCE,
        maxLines: Int = MAX_LINES_PER_SOURCE
    ): String {
        require(maxChars >= 0 && maxLines >= 0)
        val builder = StringBuilder(minOf(maxChars, 16_384))
        var lineCount = 0
        while (true) {
            val next = reader.read()
            if (next == -1) break
            if (builder.length >= maxChars) error("Source exceeded the size limit")
            val character = next.toChar()
            builder.append(character)
            if (character == '\n') {
                lineCount++
                if (lineCount > maxLines) error("Source exceeded the line limit")
            }
        }
        if (builder.isNotEmpty() && builder.last() != '\n') {
            lineCount++
            if (lineCount > maxLines) error("Source exceeded the line limit")
        }
        return builder.toString()
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun downloadLines(address: String, onLine: suspend (String) -> Unit) {
        require(isAllowedSourceUrl(address)) { "Source URL is not on the HTTPS allowlist" }
        var currentUrl = address

        for (redirectCount in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DNS-SniperX/0.3 Android")
                setRequestProperty("Accept", "text/plain,text/csv,*/*")
            }
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = false
            ) { cause ->
                if (cause is CancellationException) connection.disconnect()
            }

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        streamBoundedLines(reader, onLine)
                    }
                    return
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
                cancellationHandle?.dispose()
                connection.disconnect()
            }
        }
        error("Unable to download source")
    }

    private suspend fun streamBoundedLines(
        reader: BufferedReader,
        onLine: suspend (String) -> Unit
    ) {
        val line = StringBuilder()
        var totalChars = 0
        var lineCount = 0
        while (true) {
            val next = reader.read()
            if (next == -1) {
                if (line.isNotEmpty()) {
                    lineCount++
                    if (lineCount > MAX_LINES_PER_SOURCE) error("Source exceeded the line limit")
                    onLine(line.toString())
                }
                break
            }
            totalChars++
            if ((totalChars and 0xFF) == 0) currentCoroutineContext().ensureActive()
            if (totalChars > MAX_CHARS_PER_SOURCE) error("Source exceeded the size limit")
            val character = next.toChar()
            if (character == '\n') {
                lineCount++
                if (lineCount > MAX_LINES_PER_SOURCE) error("Source exceeded the line limit")
                onLine(line.toString())
                line.setLength(0)
            } else {
                if (line.length >= MAX_LINE_CHARS) error("Source line exceeded the size limit")
                line.append(character)
            }
        }
    }
}
