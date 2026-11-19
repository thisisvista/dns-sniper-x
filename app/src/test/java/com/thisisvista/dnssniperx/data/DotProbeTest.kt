package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotProbeTest {
    @Test fun builtInProvidersUseTlsDnsPort() {
        assertEquals(4, DotProbe.providers.size)
        assertEquals(4, DotProbe.providers.map { it.hostname }.distinct().size)
        DotProbe.providers.forEach {
            assertTrue(it.hostname.isNotBlank())
            assertEquals(853, it.port)
        }
    }
    @Test fun providerCatalogueContainsExpectedServices() {
        assertEquals(listOf("Cloudflare", "Google", "Quad9", "AdGuard"), DotProbe.providers.map { it.name })
    }
}
