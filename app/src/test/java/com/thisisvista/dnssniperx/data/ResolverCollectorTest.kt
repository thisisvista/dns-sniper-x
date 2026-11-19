package com.thisisvista.dnssniperx.data

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverCollectorTest {
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

    @Test fun boundedReaderStopsAtLineLimit() {
        val reader = BufferedReader(StringReader("one\ntwo\nthree\n"))
        assertEquals("one\ntwo\n", ResolverCollector.readBoundedContent(reader, maxChars = 100, maxLines = 2))
    }

    @Test fun boundedReaderAcceptsContentAtExactCharacterLimit() {
        val reader = BufferedReader(StringReader("abc\n"))
        assertEquals("abc\n", ResolverCollector.readBoundedContent(reader, maxChars = 4, maxLines = 10))
    }
}
