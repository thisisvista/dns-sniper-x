package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
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
        val profile = testProfile()
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

        val result = coordinator.verify(
            resolverAddress = "10.20.30.40",
            resolverPort = 53,
            profile = profile,
            mode = E2eVerificationMode.FULL,
            network = testCellularNetwork()
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
            profile = testProfile(),
            mode = E2eVerificationMode.FULL,
            network = network
        )

        assertEquals(E2eStatus.NOT_CONFIGURED, result.status)
        val saved = dao.getResolverAttempts("10.20.30.41", 53).single()
        assertEquals("NOT_CONFIGURED", saved.status)
        assertEquals("WIFI", saved.networkKey)
    }

    @Test
    fun coordinatorPersistsCancellationAndStillPropagatesIt() = runBlocking {
        val cancelledEngine = object : SlipNetE2eEngine {
            override suspend fun verify(
                request: SlipNetE2eRequest,
                onPhase: (String) -> Unit
            ): SlipNetE2eResult {
                throw CancellationException("cancelled in test")
            }
        }
        val coordinator = E2eVerificationCoordinator(
            historyDao = dao,
            engines = mapOf(TunnelProtocol.DNSTT to cancelledEngine)
        )

        var cancellationPropagated = false
        try {
            coordinator.verify(
                resolverAddress = "10.20.30.42",
                resolverPort = 53,
                profile = testProfile(),
                mode = E2eVerificationMode.FULL,
                network = testCellularNetwork()
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertEquals(true, cancellationPropagated)
        val saved = dao.getResolverAttempts("10.20.30.42", 53).single()
        assertEquals("CANCELLED", saved.status)
        assertEquals("CELLULAR:mci", saved.networkKey)
    }

    @Test
    fun coordinatorEnforcesTotalTimeoutAndPersistsItSeparatelyFromCancellation() = runBlocking {
        val waitingEngine = object : SlipNetE2eEngine {
            override suspend fun verify(
                request: SlipNetE2eRequest,
                onPhase: (String) -> Unit
            ): SlipNetE2eResult {
                awaitCancellation()
            }
        }
        val coordinator = E2eVerificationCoordinator(
            historyDao = dao,
            engines = mapOf(TunnelProtocol.DNSTT to waitingEngine)
        )

        val result = coordinator.verify(
            resolverAddress = "10.20.30.43",
            resolverPort = 53,
            profile = testProfile(),
            mode = E2eVerificationMode.FULL,
            network = testCellularNetwork(),
            timeoutMs = 1_000
        )

        assertEquals(E2eStatus.TIMEOUT, result.status)
        val saved = dao.getResolverAttempts("10.20.30.43", 53).single()
        assertEquals("TIMEOUT", saved.status)
        assertEquals("CELLULAR:mci", saved.networkKey)
    }

    @Test
    fun slipNetExportQueryIncludesOnlyLatestSuccessfulFullHttpProof() = runBlocking {
        val profile = testProfile()
        database.resolverDao().saveTunnelProfile(TunnelProfileMapper.toEntity(profile))
        dao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = "10.20.30.50", resolverPort = 53, testedAt = 100,
                networkKey = "CELLULAR:mci", networkTransport = "CELLULAR",
                profileId = profile.id, profileName = profile.name,
                serverHost = profile.serverHost, serverPort = profile.serverPort,
                dnsttServerPublicKey = profile.dnsttServerPublicKey, tunnelProtocol = "DNSTT",
                verificationMode = "FULL", status = "FULL_PASS",
                phase = "COMPLETED", elapsedMs = 900, httpStatusCode = 204
            )
        )
        // A later failed retest must not erase the last known successful proof.
        dao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = "10.20.30.50", resolverPort = 53, testedAt = 200,
                networkKey = "WIFI", networkTransport = "WIFI",
                profileId = profile.id, tunnelProtocol = "DNSTT",
                verificationMode = "FULL", status = "FULL_FAIL",
                phase = "APPLICATION_REQUEST", elapsedMs = 15_000, httpStatusCode = 500
            )
        )
        // A handshake-only pass is weaker and must never enter the SlipNet FULL list.
        dao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = "10.20.30.51", resolverPort = 53, testedAt = 300,
                networkKey = "WIFI", networkTransport = "WIFI",
                profileId = profile.id, tunnelProtocol = "DNSTT",
                verificationMode = "HANDSHAKE", status = "HANDSHAKE_PASS",
                phase = "COMPLETED", elapsedMs = 300
            )
        )
        // A FULL_PASS label without a successful HTTP status is not exportable.
        dao.insertAttempt(
            E2eHistoryEntity(
                resolverAddress = "10.20.30.52", resolverPort = 53, testedAt = 400,
                networkKey = "WIFI", networkTransport = "WIFI",
                profileId = profile.id, tunnelProtocol = "DNSTT",
                verificationMode = "FULL", status = "FULL_PASS",
                phase = "COMPLETED", elapsedMs = 400, httpStatusCode = 500
            )
        )

        // Editing a profile after a pass must not rewrite the config that was tested.
        val editedProfile = profile.copy(
            serverHost = "new-server.example.test",
            dnsttServerPublicKey = "b".repeat(64)
        )
        val resolverDao = database.resolverDao()
        resolverDao.saveTunnelProfile(
            TunnelProfileMapper.toEntity(editedProfile, resolverDao.findTunnelProfile(profile.id))
        )

        val rows = dao.getSlipNetE2eExportBatch(limit = 100, offset = 0)

        assertEquals(1, rows.size)
        assertEquals("10.20.30.50", rows.single().resolverAddress)
        assertEquals(100L, rows.single().testedAt)
        assertEquals("DNSTT", rows.single().tunnelProtocol)
        assertEquals(204, rows.single().httpStatusCode)
        assertEquals("tunnel.example.test", rows.single().serverHost)
        assertEquals("a".repeat(64), rows.single().dnsttServerPublicKey)
        val formatted = SlipNetE2eExportFormatter.format(rows)
        assertEquals("10.20.30.50\n", formatted.resolverListText)
    }

    private fun testProfile() = TunnelProfile(
        id = "dnstt-profile",
        name = "Test DNSTT",
        protocol = TunnelProtocol.DNSTT,
        serverHost = "tunnel.example.test",
        serverPort = 53,
        dnsttServerPublicKey = "a".repeat(64)
    )

    private fun testCellularNetwork() = ActiveNetworkState(
        isConnected = true,
        transport = ActiveNetworkTransport.CELLULAR,
        operatorName = "MCI",
        providerId = "mci",
        providerName = "همراه اول"
    )
}
