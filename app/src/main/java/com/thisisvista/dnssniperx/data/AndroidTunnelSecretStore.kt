package com.thisisvista.dnssniperx.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores tunnel credentials encrypted with an AES-256-GCM key held by Android Keystore.
 * Only ciphertext is written to app-private preferences. The key is non-exportable and
 * is not backed up. Callers must clear their input/output byte arrays when no longer needed.
 */
interface TunnelSecretStore {
    fun put(profileId: String, secretName: String, secret: ByteArray)
    fun get(profileId: String, secretName: String): ByteArray?
    fun delete(profileId: String, secretName: String)
    fun deleteProfile(profileId: String)
}

class AndroidKeystoreTunnelSecretStore(context: Context) : TunnelSecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE
    )

    @Synchronized
    override fun put(profileId: String, secretName: String, secret: ByteArray) {
        validateIdentifiers(profileId, secretName)
        require(secret.isNotEmpty()) { "Secret must not be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData(profileId, secretName))
        val packed = cipher.iv + cipher.doFinal(secret)
        preferences.edit()
            .putString(storageKey(profileId, secretName), Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    override fun get(profileId: String, secretName: String): ByteArray? {
        validateIdentifiers(profileId, secretName)
        val encoded = preferences.getString(storageKey(profileId, secretName), null) ?: return null
        val packed = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Stored tunnel secret is corrupted", error)
        }
        require(packed.size > IV_LENGTH) { "Stored tunnel secret is corrupted" }
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(associatedData(profileId, secretName))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    override fun delete(profileId: String, secretName: String) {
        validateIdentifiers(profileId, secretName)
        preferences.edit().remove(storageKey(profileId, secretName)).apply()
    }

    @Synchronized
    override fun deleteProfile(profileId: String) {
        require(profileId.isNotBlank()) { "Profile ID is required" }
        val prefix = storageKeyPrefix(profileId)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun associatedData(profileId: String, secretName: String): ByteArray =
        "$profileId\u0000$secretName".toByteArray(Charsets.UTF_8)

    private fun storageKey(profileId: String, secretName: String): String =
        storageKeyPrefix(profileId) + encodeIdentifier(secretName)

    private fun storageKeyPrefix(profileId: String): String =
        "secret." + encodeIdentifier(profileId) + "."

    private fun validateIdentifiers(profileId: String, secretName: String) {
        require(profileId.isNotBlank()) { "Profile ID is required" }
        require(secretName.isNotBlank()) { "Secret name is required" }
    }

    private fun encodeIdentifier(value: String): String =
        Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private companion object {
        const val PREFERENCES_NAME = "tunnel_secret_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dns_sniperx_tunnel_secret_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
