// >>> FILE: app/src/main/java/com/client/app/util/CryptoManager.kt
package com.client.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gemini_client_keys_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MIN_CIPHERTEXT_LENGTH_BYTES = 28
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""

        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    // E-19: Возврат пустой строки при сбое дешифрования во избежание передачи шифротекста в API
    fun decrypt(encryptedText: String): String {
        if (encryptedText.isBlank()) return ""

        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size < MIN_CIPHERTEXT_LENGTH_BYTES) return ""

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}