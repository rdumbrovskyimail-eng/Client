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
        private const val MIN_CIPHERTEXT_LENGTH_BYTES = 28 // 12 байт IV + 16 байт GCM tag
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
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

    /**
     * Шифрует строку с использованием аппаратного ключа AES-256-GCM.
     * Возвращает Base64(IV + CipherText). Для пустых строк возвращает "".
     */
    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""

        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            plainText
        }
    }

    /**
     * Расшифровывает строку из Base64(IV + CipherText).
     * Если расшифровка не удалась (например, сохранён старый незашифрованный ключ),
     * возвращает исходную строку как есть (graceful migration).
     */
    fun decrypt(encryptedText: String): String {
        if (encryptedText.isBlank()) return ""

        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size < MIN_CIPHERTEXT_LENGTH_BYTES) {
                return encryptedText
            }

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val decrypted = cipher.doFinal(cipherText)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            encryptedText
        }
    }
}