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

    @Test
    fun coordinatorRunsSelectedEngineAndPersistsNetworkSpecificEvidence() = runBlocking {
        val profile = TunnelProfile(
            id = "dnstt-profile",
            name = "Test DNSTT",
            protocol = TunnelProtocol.DNSTT,
            serverHost = "tunnel.example.test",
            serverPort = 5300,
            dnsttServerPublicKey = "a".repeat(64)
        )
        val fakeEngine = object : SlipNetE2eEngine {
            override suspend fun verify(
                request: SlipNetE2eRequest,
                onPhase: (String) -> Unit
            ): SlipNetE2eResult {
                assertEquals("10.20.30.40", request.resolverAddress)
                assertEquals(TunnelProtocol.DNSTT, request.profile.protocol)
                onPhase("HTTP verified")
                return SlipNetE2eResult(
                    status = E2eStatus.FULL_PASS,
                    phase = E2ePhase.COMPLETED,
                    elapsedMs = 321,
                    httpStatusCode = 204
                )
            }
        }
        val coordinator = E2eVerificationCoordinator(
            historyDao = dao,
            engines = mapOf(TunnelProtocol.DNSTT to fakeEngine)
        )
        val network = ActiveNetworkState(
            isConnected = true,
            transport = ActiveNetworkTransport.CELLULAR,
            operatorName = "MCI",
            providerId = "mci",
            providerName = "همراه اول"
        )

        val result = coordinator.verify(
            resolverAddress = "10.20.30.40",
            resolverPort = 53,
            profile = profile,
            mode = E2eVerificationMode.FULL,
            network = network
        )

        assertEquals(E2eStatus.FULL_PASS, result.status)
        val saved = dao.getResolverAttempts("10.20.30.40", 53).single()
        assertEquals("CELLULAR:mci", saved.networkKey)
        assertEquals("mci", saved.providerId)
        assertEquals("DNSTT", saved.tunnelProtocol)
        assertEquals("FULL_PASS", saved.status)
        assertEquals(204, saved.httpStatusCode)
    }

    @Test
    fun coordinatorRecordsUnavailableAdapterWithoutClaimingFailureOrSuccess() = runBlocking {
        val profile = TunnelProfile(
            id = "dnstt-profile",
            name = "Test DNSTT",
            protocol = TunnelProtocol.DNSTT,
            serverHost = "tunnel.example.test",
            serverPort = 5300,
            dnsttServerPublicKey = "b".repeat(64)
        )
        val coordinator = E2eVerificationCoordinator(
            historyDao = dao,
            engines = emptyMap()
        )
        val network = ActiveNetworkState(
            isConnected = true,
            transport = ActiveNetworkTransport.WIFI
        )

        val result = coordinator.verify(
            resolverAddress = "10.20.30.41",
            resolverPort = 53,
            profile = profile,
            mode = E2eVerificationMode.FULL,
            network = network
        )

        assertEquals(E2eStatus.NOT_CONFIGURED, result.status)
        val saved = dao.getResolverAttempts("10.20.30.41", 53).single()
        assertEquals("NOT_CONFIGURED", saved.status)
        assertEquals("WIFI", saved.networkKey)
    }

}
