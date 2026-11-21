package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class DohProbeTest {
    @Test
    fun builtInProvidersUseUniqueHttpsDnsMessageEndpoints() {
        assertEquals(4, DohProbe.providers.size)
        assertEquals(4, DohProbe.providers.map { it.endpoint }.distinct().size)
        DohProbe.providers.forEach { provider ->
            val url = URL(provider.endpoint)
            assertEquals("https", url.protocol)
            assertTrue(url.path.endsWith("/dns-query"))
        }
    }

    @Test
    fun providerCatalogueContainsExpectedPublicServices() {
        assertEquals(
            listOf("Cloudflare", "Google", "Quad9", "AdGuard"),
            DohProbe.providers.map { it.name }
        )
    }
}
