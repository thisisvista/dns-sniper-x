package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
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
}
