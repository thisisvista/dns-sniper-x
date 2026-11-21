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
    fun insertOrMergeSourcesInsertsBatchAndPreservesDuplicateProvenance() = runBlocking {
        val inserted = dao.insertOrMergeSources(
            listOf(
                ResolverEntity(address = "1.1.1.1", source = "iran-directory"),
                ResolverEntity(address = "1.1.1.1", source = "global-directory"),
                ResolverEntity(address = "1.1.1.1", source = "public-dns-info"),
                ResolverEntity(address = "8.8.8.8", source = "global-directory")
            )
        )

        assertEquals(2, inserted)
        val rows = page(offset = 0)
        val iranResolver = rows.single { it.address == "1.1.1.1" }
        assertEquals("iran-directory", iranResolver.source)
        assertEquals("global-directory\npublic-dns-info", iranResolver.additionalSources)
        assertEquals(2, rows.size)
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
    fun scanCursorContinuesAcrossBatchesAsCompletedRowsLeavePendingSet() = runBlocking {
        dao.insertIgnore(
            (0 until 25).map { index ->
                ResolverEntity(address = "10.20.${index / 250}.${index % 250 + 1}")
            }
        )

        val firstBatch = dao.getPendingScanBatch(retryUnsuccessful = false, afterId = 0L, limit = 10)
        assertEquals(10, firstBatch.size)
        firstBatch.forEach { resolver ->
            dao.updateScanResult(
                id = resolver.id,
                udpStatus = "PASS",
                udpLatencyMs = 15L,
                tcpStatus = "PASS",
                tcpLatencyMs = 20L,
                testedAt = 1_800_000_000_000L,
                overallStatus = "DNS_PASS",
                bestLatency = 15L,
                note = null
            )
        }

        val secondBatch = dao.getPendingScanBatch(
            retryUnsuccessful = false,
            afterId = firstBatch.last().id,
            limit = 10
        )
        val thirdBatch = dao.getPendingScanBatch(
            retryUnsuccessful = false,
            afterId = secondBatch.last().id,
            limit = 10
        )

        assertEquals(10, secondBatch.size)
        assertEquals(5, thirdBatch.size)
        assertEquals(25, (firstBatch + secondBatch + thirdBatch).map { it.id }.distinct().size)
        assertEquals(15, dao.countPendingScan(retryUnsuccessful = false))
    }

    @Test
    fun slipNetExportQueryIncludesOnlyPort53IPv4Resolvers() = runBlocking {
        dao.insertIgnore(
            listOf(
                ResolverEntity(address = "1.1.1.1", port = 53, udpStatus = "PASS", udpLatencyMs = 30),
                ResolverEntity(address = "8.8.8.8", port = 53, udpStatus = "PASS", udpLatencyMs = 20),
                ResolverEntity(address = "2001:db8::1", port = 53, udpStatus = "PASS", udpLatencyMs = 5),
                ResolverEntity(address = "9.9.9.9", port = 5353, udpStatus = "PASS", udpLatencyMs = 1)
            )
        )

        val exported = dao.getSlipNetExportBatch(limit = 500, offset = 0)

        assertEquals(listOf("8.8.8.8", "1.1.1.1"), exported.map { it.address })
        assertEquals(4, dao.countWorkingExportRows())
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
