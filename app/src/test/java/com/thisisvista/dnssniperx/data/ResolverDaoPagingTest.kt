package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResolverDaoPagingTest {
    private lateinit var database: ResolverDatabase
    private lateinit var dao: ResolverDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ResolverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.resolverDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun resolverPagesAreBoundedAndCoverEveryMatchingRow() = runBlocking {
        val entries = (0 until 205).map { index ->
            ResolverEntity(address = "10.1.${index / 254}.${index % 254 + 1}", collectedAt = index.toLong())
        }
        dao.insertIgnore(entries)

        val first = page(offset = 0)
        val second = page(offset = 100)
        val third = page(offset = 200)

        assertEquals(100, first.size)
        assertEquals(100, second.size)
        assertEquals(5, third.size)
        assertEquals(205, dao.countResolverMatches("ALL", "", "ALL", "ALL"))
        assertEquals(205, (first + second + third).map { it.id }.distinct().size)
    }

    @Test
    fun statusNetworkProviderAndSearchFiltersAreAppliedInSql() = runBlocking {
        dao.insertIgnore(
            listOf(
                ResolverEntity(
                    address = "1.1.1.1",
                    source = "current-network (Wi-Fi; user label: Home)",
                    udpStatus = "PASS",
                    udpLatencyMs = 12
                ),
                ResolverEntity(
                    address = "8.8.8.8",
                    source = "current-network (Mobile; user label: Cellular)",
                    tcpStatus = "PASS",
                    tcpLatencyMs = 20
                ),
                ResolverEntity(
                    address = "9.9.9.9",
                    source = "manual",
                    note = "WARNING: sample"
                )
            )
        )

        val homeWifi = dao.getResolverPage(
            statusFilter = "WORKING",
            searchTerm = "1.1.1.1",
            networkType = "Wi-Fi",
            providerLabel = "Home",
            fastestFirst = true,
            limit = 100,
            offset = 0
        )

        assertEquals(listOf("1.1.1.1"), homeWifi.map { it.address })
        assertEquals(1, dao.countResolverMatches("WORKING", "", "Wi-Fi", "Home"))
        assertEquals(1, dao.countResolverMatches("WARNINGS", "9.9.9.9", "ALL", "ALL"))
        assertEquals(listOf("Mobile", "Wi-Fi"), dao.getNetworkTypeFilters())
        assertEquals(listOf("Cellular", "Home"), dao.getProviderLabelFilters())
    }

    @Test
    fun slipNetExportQueryKeepsFastestCompatibleAddressOnlyOnce() = runBlocking {
        dao.insertIgnore(
            listOf(
                ResolverEntity(address = "1.1.1.1", port = 53, udpStatus = "PASS", udpLatencyMs = 30),
                ResolverEntity(address = "1.1.1.1", port = 53, tcpStatus = "PASS", tcpLatencyMs = 10),
                ResolverEntity(address = "8.8.8.8", port = 53, udpStatus = "PASS", udpLatencyMs = 20),
                ResolverEntity(address = "2001:db8::1", port = 53, udpStatus = "PASS", udpLatencyMs = 5),
                ResolverEntity(address = "9.9.9.9", port = 5353, udpStatus = "PASS", udpLatencyMs = 1)
            )
        )

        val exported = dao.getSlipNetExportBatch(limit = 500, offset = 0)

        assertEquals(listOf("8.8.8.8", "1.1.1.1"), exported.map { it.address })
        assertEquals(3, dao.countWorkingExportRows())
        assertEquals(2, dao.countSlipNetCompatibleCandidates())
    }

    @Test
    fun invalidationFlowEmitsWhenStatusChangesWithoutChangingRowCount() = runBlocking {
        dao.insertIgnore(listOf(ResolverEntity(address = "1.1.1.1")))
        val firstEmission = CompletableDeferred<Unit>()
        val emissions = async {
            withTimeout(5_000) {
                dao.observeResolverInvalidation()
                    .onEach { if (!firstEmission.isCompleted) firstEmission.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }

        firstEmission.await()
        dao.updateScanResult(
            id = 1L,
            udpStatus = "PASS",
            udpLatencyMs = 15,
            tcpStatus = "TIMEOUT",
            tcpLatencyMs = null,
            testedAt = 100L,
            overallStatus = "DNS_PASS",
            bestLatency = 15L,
            note = null
        )

        assertEquals(2, emissions.await().size)
    }

    private suspend fun page(offset: Int) = dao.getResolverPage(
        statusFilter = "ALL",
        searchTerm = "",
        networkType = "ALL",
        providerLabel = "ALL",
        fastestFirst = false,
        limit = 100,
        offset = offset
    )
}
