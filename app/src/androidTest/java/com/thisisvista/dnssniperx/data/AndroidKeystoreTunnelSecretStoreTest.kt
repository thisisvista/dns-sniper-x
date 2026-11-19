package com.thisisvista.dnssniperx.data

import android.content.Context
import android.security.keystore.KeyStoreException
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreTunnelSecretStoreTest {
    @Test
    fun keystoreVaultEncryptsAuthenticatesDeletesAndRejectsLostKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidKeystoreTunnelSecretStore(context)
        val profileId = "instrumentation-${UUID.randomUUID()}"
        val secretName = "slipstream-token"
        val secret = "test-only-secret-value".toByteArray(Charsets.UTF_8)
        val preferences = context.getSharedPreferences("tunnel_secret_vault", Context.MODE_PRIVATE)
        val storageKey = "secret." + encodeIdentifier(profileId) + "." + encodeIdentifier(secretName)

        try {
            store.put(profileId, secretName, secret)
            val storedCiphertext = preferences.getString(storageKey, null)
            assertNotNull(storedCiphertext)
            assertArrayEquals(secret, store.get(profileId, secretName))

            // The profile ID and secret name are authenticated as AES-GCM AAD.
            assertThrows(AEADBadTagException::class.java) {
                store.get(profileId, "different-secret-name")
            }
            assertThrows(AEADBadTagException::class.java) {
                store.get("${profileId}-other", secretName)
            }

            // Flip a ciphertext bit while preserving its IV and encoded storage shape.
            val packed = Base64.decode(storedCiphertext, Base64.NO_WRAP)
            packed[packed.lastIndex] = (packed.last().toInt() xor 0x01).toByte()
            preferences.edit()
                .putString(storageKey, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
            assertThrows(AEADBadTagException::class.java) {
                store.get(profileId, secretName)
            }

            store.put(profileId, secretName, secret)
            store.delete(profileId, secretName)
            assertNull(store.get(profileId, secretName))

            store.put(profileId, secretName, secret)
            store.deleteProfile(profileId)
            assertNull(store.get(profileId, secretName))

            // Simulate key loss/invalidation. Existing ciphertext must not decrypt.
            store.put(profileId, secretName, secret)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("dns_sniperx_tunnel_secret_key_v1")
            assertThrows(GeneralSecurityException::class.java) {
                store.get(profileId, secretName)
            }
        } finally {
            preferences.edit().remove(storageKey).commit()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry("dns_sniperx_tunnel_secret_key_v1")
                }
            }
            secret.fill(0)
        }
    }

    private fun encodeIdentifier(value: String): String =
        Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
