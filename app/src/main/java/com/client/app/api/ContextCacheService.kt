package com.client.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextCacheService @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger
) {
    companion object {
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta"

        val KEY_CACHED_CONTENT_ID =
            stringPreferencesKey("cached_content_id")

        val KEY_CACHED_CONTENT_HASH =
            stringPreferencesKey("cached_content_hash")

        private val KEY_CACHED_CONTENT_OWNER =
            stringPreferencesKey("cached_content_owner")

        private val KEY_CACHED_CONTENT_EXPIRES_AT =
            longPreferencesKey("cached_content_expires_at")

        private val JSON_MEDIA =
            "application/json; charset=utf-8".toMediaType()

        private const val MIN_TOKENS_FOR_CACHE =
            32768

        private const val CACHE_TTL_SECONDS =
            3600L

        private const val CACHE_TTL_MILLIS =
            CACHE_TTL_SECONDS * 1000L
    }

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val cacheMutex =
        Mutex()

    private fun generateSha256(value: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun generateCacheFingerprint(
        model: String,
        prompt: String
    ): String {
        val input =
            "model:$model\u0000prompt:$prompt"

        return generateSha256(input)
    }

    private fun generateOwnerFingerprint(
        apiKey: String
    ): String {
        return generateSha256(apiKey)
    }

    suspend fun getOrCreateCache(
        apiKey: String,
        systemPrompt: String,
        modelName: String = "gemini-2.5-flash"
    ): String? =
        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                val cleanApiKey =
                    apiKey.trim()

                if (
                    cleanApiKey.isBlank() ||
                    systemPrompt.isBlank()
                ) {
                    return@withContext null
                }

                val normalizedModel =
                    modelName
                        .trim()
                        .removePrefix("publishers/google/models/")
                        .removePrefix("models/")

                // Gemini Live models cannot be used with the cachedContents API.
                // Do not silently create or reuse an incompatible cached-content
                // object for a Live session.
                if (normalizedModel.contains("-live")) {
                    logger.w(
                        "ContextCacheService: cachedContents не поддерживается " +
                            "для Live-модели '$normalizedModel'; кэш пропущен"
                    )
                    return@withContext null
                }

                val estimatedTokens =
                    systemPrompt.length / 4

                if (estimatedTokens < MIN_TOKENS_FOR_CACHE) {
                    return@withContext null
                }

                val promptHash =
                    generateCacheFingerprint(
                        normalizedModel,
                        systemPrompt
                    )

                val ownerFingerprint =
                    generateOwnerFingerprint(
                        cleanApiKey
                    )

                val now =
                    System.currentTimeMillis()

                val prefs =
                    dataStore.data.first()

                val existingId =
                    prefs[KEY_CACHED_CONTENT_ID]

                val existingHash =
                    prefs[KEY_CACHED_CONTENT_HASH]

                val existingOwner =
                    prefs[KEY_CACHED_CONTENT_OWNER]

                val existingExpiresAt =
                    prefs[KEY_CACHED_CONTENT_EXPIRES_AT] ?: 0L

                val existingIsUsable =
                    !existingId.isNullOrBlank() &&
                        existingHash == promptHash &&
                        existingOwner == ownerFingerprint &&
                        existingExpiresAt > now

                if (existingIsUsable) {
                    return@withContext existingId
                }

                // Either the cache is absent, belongs to another API key,
                // has a different prompt/model, or is locally expired.
                if (!existingId.isNullOrBlank()) {
                    deleteCache(
                        cleanApiKey,
                        existingId
                    )
                } else {
                    clearLocalCacheState()
                }

                val cleanModel =
                    "models/$normalizedModel"

                val payload =
                    buildJsonObject {
                        put(
                            "model",
                            cleanModel
                        )

                        put(
                            "displayName",
                            "gemini_voice_session_cache"
                        )

                        put(
                            "systemInstruction",
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "text",
                                                    systemPrompt
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )

                        put(
                            "ttl",
                            "${CACHE_TTL_SECONDS}s"
                        )
                    }.toString()

                val url =
                    "$BASE_URL/cachedContents"

                val request =
                    Request.Builder()
                        .url(url)
                        .header(
                            "Content-Type",
                            "application/json"
                        )
                        .header(
                            "x-goog-api-key",
                            cleanApiKey
                        )
                        .post(
                            payload.toRequestBody(JSON_MEDIA)
                        )
                        .build()

                runCatching {
                    httpClient
                        .newCall(request)
                        .execute()
                        .use { response ->

                            val body =
                                response.body
                                    ?.string()
                                    .orEmpty()

                            if (response.isSuccessful) {
                                val root =
                                    json.parseToJsonElement(body)
                                        .jsonObject

                                val cacheName =
                                    root["name"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull

                                if (!cacheName.isNullOrBlank()) {
                                    val expiresAt =
                                        now + CACHE_TTL_MILLIS

                                    dataStore.edit {
                                        it[KEY_CACHED_CONTENT_ID] =
                                            cacheName

                                        it[KEY_CACHED_CONTENT_HASH] =
                                            promptHash

                                        it[KEY_CACHED_CONTENT_OWNER] =
                                            ownerFingerprint

                                        it[KEY_CACHED_CONTENT_EXPIRES_AT] =
                                            expiresAt
                                    }

                                    logger.d(
                                        "ContextCacheService: Создан KV-кэш: $cacheName"
                                    )

                                    return@withContext cacheName
                                }

                                logger.w(
                                    "ContextCacheService: успешный ответ создания " +
                                        "кэша не содержит поля name"
                                )
                            } else {
                                // 404/410 mean that a previously referenced
                                // server-side cache object is no longer usable.
                                // 401/403 are also deliberately not persisted as
                                // a valid local cache state.
                                if (
                                    response.code == 401 ||
                                    response.code == 403 ||
                                    response.code == 404 ||
                                    response.code == 410
                                ) {
                                    clearLocalCacheState()
                                }

                                logger.w(
                                    "ContextCacheService: Сервер отклонил кэш (${response.code})"
                                )
                            }
                        }
                }.onFailure {
                    logger.e(
                        "ContextCacheService: Сбой сети при создании кэша",
                        it
                    )
                }

                null
            }
        }

    suspend fun deleteCache(
        apiKey: String,
        cacheId: String
    ) =
        withContext(Dispatchers.IO) {
            val cleanApiKey =
                apiKey.trim()

            if (
                cleanApiKey.isBlank() ||
                cacheId.isBlank()
            ) {
                return@withContext
            }

            val cleanId =
                cacheId
                    .removePrefix("cachedContents/")
                    .trim('/')

            if (cleanId.isBlank()) {
                clearLocalCacheState()
                return@withContext
            }

            val url =
                "$BASE_URL/cachedContents/$cleanId"

            runCatching {
                httpClient
                    .newCall(
                        Request.Builder()
                            .url(url)
                            .header(
                                "x-goog-api-key",
                                cleanApiKey
                            )
                            .delete()
                            .build()
                    )
                    .execute()
                    .use { response ->

                        when {
                            response.isSuccessful ||
                                response.code == 404 ||
                                response.code == 410 -> {
                                clearLocalCacheState()
                            }

                            response.code == 401 ||
                                response.code == 403 -> {
                                // The local reference must never be reused under
                                // an authentication/authorization failure.
                                clearLocalCacheState()

                                logger.w(
                                    "ContextCacheService: удаление кэша отклонено (${response.code})"
                                )
                            }

                            else -> {
                                logger.w(
                                    "ContextCacheService: не удалось удалить кэш (${response.code})"
                                )
                            }
                        }
                    }
            }.onFailure {
                logger.w(
                    "ContextCacheService: ошибка удаления кэша: ${it.message}"
                )
            }
        }

    private suspend fun clearLocalCacheState() {
        dataStore.edit {
            it.remove(KEY_CACHED_CONTENT_ID)
            it.remove(KEY_CACHED_CONTENT_HASH)
            it.remove(KEY_CACHED_CONTENT_OWNER)
            it.remove(KEY_CACHED_CONTENT_EXPIRES_AT)
        }
    }
}