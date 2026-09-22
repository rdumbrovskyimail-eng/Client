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

sealed interface CryptoResult {
    data class Success(val value: String) : CryptoResult
    data object Missing : CryptoResult
    data class Failure(
        val reason: CryptoFailureReason,
        val cause: Throwable? = null
    ) : CryptoResult
}

enum class CryptoFailureReason {
    KEYSTORE_UNAVAILABLE,
    KEY_ACCESS_FAILED,
    INVALID_CIPHERTEXT,
    AUTHENTICATION_FAILED,
    ENCRYPTION_FAILED,
    DECRYPTION_FAILED
}

@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gemini_client_keys_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MIN_CIPHERTEXT_LENGTH_BYTES =
            GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)
    }

    @Volatile
    private var keyStore: KeyStore? = null

    @Synchronized
    private fun getKeyStore(): KeyStore {
        keyStore?.let { return it }

        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
        keyStore = store
        return store
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val store = getKeyStore()

        if (store.containsAlias(KEY_ALIAS)) {
            val entry = store.getEntry(KEY_ALIAS, null)
            return (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw IllegalStateException("Android Keystore alias has unexpected entry type")
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
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

    fun encrypt(plainText: String): CryptoResult {
        if (plainText.isBlank()) {
            return CryptoResult.Success("")
        }

        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            if (iv.size != GCM_IV_LENGTH_BYTES) {
                return CryptoResult.Failure(
                    CryptoFailureReason.ENCRYPTION_FAILED,
                    IllegalStateException("Unexpected GCM IV length: ${iv.size}")
                )
            }

            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)

            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            CryptoResult.Success(
                Base64.encodeToString(combined, Base64.NO_WRAP)
            )
        } catch (e: java.security.KeyStoreException) {
            keyStore = null
            CryptoResult.Failure(CryptoFailureReason.KEYSTORE_UNAVAILABLE, e)
        } catch (e: java.io.IOException) {
            keyStore = null
            CryptoResult.Failure(CryptoFailureReason.KEYSTORE_UNAVAILABLE, e)
        } catch (e: java.security.GeneralSecurityException) {
            CryptoResult.Failure(CryptoFailureReason.ENCRYPTION_FAILED, e)
        } catch (e: RuntimeException) {
            CryptoResult.Failure(CryptoFailureReason.KEY_ACCESS_FAILED, e)
        }
    }

    fun decrypt(encryptedText: String): CryptoResult {
        if (encryptedText.isBlank()) {
            return CryptoResult.Missing
        }

        return try {
            val combined = try {
                Base64.decode(encryptedText, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                return CryptoResult.Failure(
                    CryptoFailureReason.INVALID_CIPHERTEXT,
                    e
                )
            }

            if (combined.size < MIN_CIPHERTEXT_LENGTH_BYTES) {
                return CryptoResult.Failure(
                    CryptoFailureReason.INVALID_CIPHERTEXT
                )
            }

            val iv = combined.copyOfRange(
                0,
                GCM_IV_LENGTH_BYTES
            )
            val cipherText = combined.copyOfRange(
                GCM_IV_LENGTH_BYTES,
                combined.size
            )

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )

            CryptoResult.Success(
                String(
                    cipher.doFinal(cipherText),
                    Charsets.UTF_8
                )
            )
        } catch (e: AEADBadTagException) {
            CryptoResult.Failure(
                CryptoFailureReason.AUTHENTICATION_FAILED,
                e
            )
        } catch (e: java.security.KeyStoreException) {
            keyStore = null
            CryptoResult.Failure(CryptoFailureReason.KEYSTORE_UNAVAILABLE, e)
        } catch (e: java.io.IOException) {
            keyStore = null
            CryptoResult.Failure(CryptoFailureReason.KEYSTORE_UNAVAILABLE, e)
        } catch (e: java.security.GeneralSecurityException) {
            CryptoResult.Failure(CryptoFailureReason.DECRYPTION_FAILED, e)
        } catch (e: RuntimeException) {
            CryptoResult.Failure(CryptoFailureReason.KEY_ACCESS_FAILED, e)
        }
    }
}