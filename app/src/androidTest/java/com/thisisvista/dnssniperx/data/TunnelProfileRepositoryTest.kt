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
        val otherProfileId = "repository-other-${UUID.randomUUID()}"
        val privateCredential = "private-test-credential".toByteArray(Charsets.UTF_8)
        val otherCredential = "other-profile-credential".toByteArray(Charsets.UTF_8)
        val profile = TunnelProfile(
            id = profileId,
            name = "Test DNSTT",
            protocol = TunnelProtocol.DNSTT,
            serverHost = "tunnel.example",
            serverPort = 5300,
            dnsttServerPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )

        try {
            val saved = repository.saveProfile(profile)
            assertEquals(profile, saved)
            assertEquals(profile, repository.findProfile(profileId))

            repository.putSecret(profileId, "private-key", privateCredential)
            repository.putSecret(profileId, "second-secret", "second-secret-value".toByteArray(Charsets.UTF_8))
            val otherProfile = profile.copy(
                id = otherProfileId,
                name = "Other DNSTT"
            )
            repository.saveProfile(otherProfile)
            repository.putSecret(otherProfileId, "private-key", otherCredential)
            assertArrayEquals(privateCredential, repository.getSecret(profileId, "private-key"))
            assertArrayEquals(otherCredential, repository.getSecret(otherProfileId, "private-key"))

            val persistedMetadata = database.resolverDao().findTunnelProfile(profileId)
            assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", persistedMetadata?.dnsttServerPublicKey)
            assertFalse(persistedMetadata.toString().contains("private-test-credential"))

            val missingProfileResult = runCatching {
                repository.putSecret("missing-$profileId", "private-key", privateCredential)
            }
            assertTrue(missingProfileResult.isFailure)

            repository.deleteProfile(profileId)
            assertNull(repository.findProfile(profileId))
            assertNull(secretStore.get(profileId, "private-key"))
            assertNull(secretStore.get(profileId, "second-secret"))
            assertEquals(otherProfile, repository.findProfile(otherProfileId))
            assertArrayEquals(otherCredential, repository.getSecret(otherProfileId, "private-key"))
        } finally {
            secretStore.deleteProfile(profileId)
            secretStore.deleteProfile(otherProfileId)
            database.close()
            privateCredential.fill(0)
            otherCredential.fill(0)
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
