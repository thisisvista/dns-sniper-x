package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class E2eHistoryDaoTest {
    private lateinit var database: ResolverDatabase
    private lateinit var dao: E2eHistoryDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ResolverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.e2eHistoryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun sameResolverAttemptsOnDifferentNetworksRemainIndependent() = runBlocking {
        val wifiId = dao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = "10.20.30.40",
                resolverPort = 53,
                testedAt = 100L,
                networkKey = "WIFI",
                networkTransport = "WIFI",
                networkLabel = "Home Wi-Fi",
                profileId = "profile-1",
                tunnelProtocol = "DNSTT",
                verificationMode = "FULL",
                status = "FULL_FAIL",
                phase = "APPLICATION_REQUEST",
                elapsedMs = 12_000,
                errorMessage = "Timed out"
            )
        )
        val cellularId = dao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = "10.20.30.40",
                resolverPort = 53,
                testedAt = 200L,
                networkKey = "CELLULAR:mci",
                networkTransport = "CELLULAR",
                operatorName = "MCI",
                providerId = "mci",
                profileId = "profile-1",
                tunnelProtocol = "DNSTT",
                verificationMode = "FULL",
                status = "FULL_PASS",
                phase = "COMPLETED",
                elapsedMs = 2_300,
                httpStatusCode = 204
            )
        )

        val all = dao.getResolverAttempts("10.20.30.40", 53)
        assertEquals(2, all.size)
        assertEquals(listOf("CELLULAR:mci", "WIFI"), all.map { it.networkKey })
        assertNotEquals(wifiId, cellularId)
        assertEquals(1, dao.observeNetworkAttempts("WIFI").first().size)
        assertEquals(1, dao.observeNetworkAttempts("CELLULAR:mci").first().size)
        assertEquals(1, dao.observeStatusCount("FULL_PASS").first())
        assertEquals(2, dao.observeAttemptCount().first())
    }
}
