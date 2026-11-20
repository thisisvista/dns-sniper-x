package com.thisisvista.dnssniperx.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TunnelProfileRepositoryTest {
    @Test
    fun profileMetadataAndSecretsUseSeparateStorageAndDeleteTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ResolverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val secretStore = InMemoryTunnelSecretStore()
        val repository = TunnelProfileRepository(database.resolverDao(), secretStore)
        val profileId = "repository-${UUID.randomUUID()}"
        val privateCredential = "private-test-credential".toByteArray(Charsets.UTF_8)
        val profile = TunnelProfile(
            id = profileId,
            name = "Test DNSTT",
            protocol = TunnelProtocol.DNSTT,
            serverHost = "tunnel.example",
            serverPort = 5300,
            dnsttServerPublicKey = "public-noise-key"
        )

        try {
            val saved = repository.saveProfile(profile)
            assertEquals(profile, saved)
            assertEquals(profile, repository.findProfile(profileId))

            repository.putSecret(profileId, "private-key", privateCredential)
            assertArrayEquals(privateCredential, repository.getSecret(profileId, "private-key"))

            val persistedMetadata = database.resolverDao().findTunnelProfile(profileId)
            assertEquals("public-noise-key", persistedMetadata?.dnsttServerPublicKey)
            assertFalse(persistedMetadata.toString().contains("private-test-credential"))

            val missingProfileResult = runCatching {
                repository.putSecret("missing-$profileId", "private-key", privateCredential)
            }
            assertTrue(missingProfileResult.isFailure)

            repository.deleteProfile(profileId)
            assertNull(repository.findProfile(profileId))
            assertNull(secretStore.get(profileId, "private-key"))
        } finally {
            secretStore.deleteProfile(profileId)
            database.close()
            privateCredential.fill(0)
        }
    }


    @Test
    fun observeProfilesEmitsValidPersistedProfilesAndSkipsUnknownProtocols() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ResolverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = TunnelProfileRepository(database.resolverDao(), InMemoryTunnelSecretStore())
        val validProfile = TunnelProfile(
            id = "observed-${UUID.randomUUID()}",
            name = "Observed Slipstream",
            protocol = TunnelProtocol.SLIPSTREAM,
            serverHost = "slip.example",
            serverPort = 443,
            slipstreamTlsServerName = "slip.example"
        )

        try {
            repository.saveProfile(validProfile)
            database.resolverDao().saveTunnelProfile(
                TunnelProfileEntity(
                    id = "future-${UUID.randomUUID()}",
                    name = "Future protocol",
                    protocol = "FUTURE_PROTOCOL",
                    serverHost = "future.example",
                    serverPort = 443,
                    dnsttServerPublicKey = null,
                    slipstreamTlsServerName = null
                )
            )

            val observed = repository.observeProfiles().first()
            assertEquals(listOf(validProfile), observed)
        } finally {
            database.close()
        }
    }

    private class InMemoryTunnelSecretStore : TunnelSecretStore {
        private val values = mutableMapOf<Pair<String, String>, ByteArray>()

        override fun put(profileId: String, secretName: String, secret: ByteArray) {
            require(secret.isNotEmpty())
            values[profileId to secretName] = secret.copyOf()
        }

        override fun get(profileId: String, secretName: String): ByteArray? =
            values[profileId to secretName]?.copyOf()

        override fun delete(profileId: String, secretName: String) {
            values.remove(profileId to secretName)?.fill(0)
        }

        override fun deleteProfile(profileId: String) {
            values.keys.filter { it.first == profileId }.forEach { key ->
                values.remove(key)?.fill(0)
            }
        }
    }
}
