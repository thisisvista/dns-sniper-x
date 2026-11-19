package com.thisisvista.dnssniperx.data

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverCollectorTest {
    @Test fun sourceFetchResultCarriesStableSourceProvenance() {
        val retrievedAt = 1_800_000_000_000L
        val result = SourceFetchResult(
            source = "Iran public DNS directory",
            found = 42,
            inserted = 0,
            sourceId = "iran-public-dns-directory",
            sourceUrl = "https://raw.githubusercontent.com/example/iran.txt",
            retrievedAt = retrievedAt
        )
        assertEquals("iran-public-dns-directory", result.sourceId)
        assertEquals("https://raw.githubusercontent.com/example/iran.txt", result.sourceUrl)
        assertEquals(retrievedAt, result.retrievedAt)
        assertEquals(42, result.found)
    }

    @Test fun failedSourceFetchStillCarriesItsConfiguredUrl() {
        val result = SourceFetchResult(
            source = "Public DNS Info",
            found = 0,
            inserted = 0,
            error = "timeout",
            sourceId = "public-dns-info",
            sourceUrl = "https://public-dns.info/nameservers.csv"
        )
        assertEquals("public-dns-info", result.sourceId)
        assertEquals("https://public-dns.info/nameservers.csv", result.sourceUrl)
        assertEquals("timeout", result.error)
    }

    @Test fun includesRegionalIranAndGlobalPublicDirectories() {
        val sources = ResolverCollector.sources.associateBy { it.id }
        assertTrue(sources.containsKey("iran-public-dns-directory"))
        assertTrue(sources.containsKey("global-public-dns-directory"))
        assertTrue(sources.getValue("iran-public-dns-directory").url.endsWith("/resolvers/by-country/ir.txt"))
        assertTrue(sources.getValue("global-public-dns-directory").url.endsWith("/resolvers/global/all.txt"))
    }

    @Test fun regionalSourceIsPrioritizedForProvenance() {
        assertEquals("iran-public-dns-directory", ResolverCollector.sources.first().id)
    }

    @Test fun sourceUrlPolicyAllowsOnlyReviewedHttpsHosts() {
        assertTrue(ResolverCollector.isAllowedSourceUrl("https://public-dns.info/nameservers.csv"))
        assertTrue(ResolverCollector.isAllowedSourceUrl("https://raw.githubusercontent.com/example/list.txt"))
        assertFalse(ResolverCollector.isAllowedSourceUrl("http://raw.githubusercontent.com/example/list.txt"))
        assertFalse(ResolverCollector.isAllowedSourceUrl("https://evil.example/list.txt"))
        assertFalse(ResolverCollector.isAllowedSourceUrl("https://raw.githubusercontent.com.evil.example/list.txt"))
        assertFalse(ResolverCollector.isAllowedSourceUrl("https://user@raw.githubusercontent.com/example/list.txt"))
        assertFalse(ResolverCollector.isAllowedSourceUrl("https://raw.githubusercontent.com:8443/example/list.txt"))
    }

    @Test fun boundedReaderRejectsOversizedSingleLineBeforeAccumulatingIt() {
        val reader = BufferedReader(StringReader("0123456789abcdef"))
        val error = runCatching {
            ResolverCollector.readBoundedContent(reader, maxChars = 8, maxLines = 10)
        }.exceptionOrNull()
        assertEquals("Source exceeded the size limit", error?.message)
    }

    @Test fun boundedReaderRejectsContentBeyondLineLimit() {
        val reader = BufferedReader(StringReader("one\ntwo\nthree\n"))
        val error = runCatching {
            ResolverCollector.readBoundedContent(reader, maxChars = 100, maxLines = 2)
        }.exceptionOrNull()
        assertEquals("Source exceeded the line limit", error?.message)
    }

    @Test fun boundedReaderAcceptsContentAtExactLineLimit() {
        val reader = BufferedReader(StringReader("one\ntwo\n"))
        assertEquals("one\ntwo\n", ResolverCollector.readBoundedContent(reader, maxChars = 100, maxLines = 2))
    }

    @Test fun boundedReaderAcceptsContentAtExactCharacterLimit() {
        val reader = BufferedReader(StringReader("abc\n"))
        assertEquals("abc\n", ResolverCollector.readBoundedContent(reader, maxChars = 4, maxLines = 10))
    }
}
