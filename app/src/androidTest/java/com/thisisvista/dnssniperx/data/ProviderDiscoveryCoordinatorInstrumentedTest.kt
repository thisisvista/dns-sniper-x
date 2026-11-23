package com.thisisvista.dnssniperx.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Database-only instrumentation coverage. Deliberately does not launch MainActivity:
 * this verifies persistence/reset behavior without involving Activity lifecycle teardown.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDiscoveryCoordinatorInstrumentedTest {
    private val dao by lazy {
        ResolverDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).resolverDao()
    }

    @Before
    fun clearLocalDatabase() {
        ResolverDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).clearAllTables()
    }

    @Test
    fun resettingCollectionProgressPreservesDnsAndScanResults() = runBlocking {
        val endpoint = "203.0.113.53"
        dao.insertOrMergeSources(
            listOf(
                ResolverEntity(
                    address = endpoint,
                    source = "unit-test-source",
                    status = "PASS",
                    udpStatus = "PASS",
                    udpLatencyMs = 24,
                    tcpStatus = "FAIL",
                    tcpLatencyMs = null,
                    lastTestedAt = 1_800_000_000_000
                )
            )
        )
        dao.upsertProviderDiscoveryState(
            ProviderDiscoveryStateEntity(
                selectionKey = "mci,irancell",
                providerIds = "mci,irancell",
                nextOffset = 500,
                totalCandidates = 2_000,
                complete = false,
                updatedAt = 1_800_000_000_000,
                prefixSnapshotJson = "test-snapshot",
                snapshotFetchedAt = 1_800_000_000_000,
                iranAllocationRanges = 42
            )
        )

        ProviderDiscoveryCoordinator(dao).resetAll()

        assertEquals(1, dao.observeCount().first())
        val saved = dao.findByEndpoint(endpoint, 53)
        assertNotNull(saved)
        assertEquals("PASS", saved?.udpStatus)
        assertEquals("FAIL", saved?.tcpStatus)
        assertEquals(null, dao.getProviderDiscoveryState("mci,irancell"))
    }
}
