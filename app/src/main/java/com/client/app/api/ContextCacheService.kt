// >>> FILE: app/src/main/java/com/client/app/api/ContextCacheService.kt
package com.client.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
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
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        val KEY_CACHED_CONTENT_ID = stringPreferencesKey("cached_content_id")
        val KEY_CACHED_CONTENT_HASH = stringPreferencesKey("cached_content_hash")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MIN_TOKENS_FOR_CACHE = 32768
        private const val CACHE_TTL_SECONDS = 3600
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cacheMutex = Mutex()

    private fun generateCacheFingerprint(model: String, prompt: String): String {
        val input = "model:$model\u0000prompt:$prompt"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun getOrCreateCache(
        apiKey: String,
        systemPrompt: String,
        modelName: String = "gemini-2.5-flash"
    ): String? = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
        val cleanApiKey = apiKey.trim()
        if (cleanApiKey.isBlank() || systemPrompt.isBlank()) return@withContext null

        val normalizedModel =
            modelName
                .trim()
                .removePrefix("publishers/google/models/")
                .removePrefix("models/")

        // Gemini Live models cannot be used with the cachedContents API.
        // Never let a Live session silently fall back to an incompatible
        // cached-content contract.
        if (normalizedModel.contains("-live")) {
            logger.w(
                "ContextCacheService: cachedContents не поддерживается для Live-модели '$normalizedModel'; кэш пропущен"
            )
            return@withContext null
        }

        val estimatedTokens = systemPrompt.length / 4
        if (estimatedTokens < MIN_TOKENS_FOR_CACHE) {
            return@withContext null
        }

        val promptHash = generateCacheFingerprint(normalizedModel, systemPrompt)
        val prefs = dataStore.data.first()
        val existingId = prefs[KEY_CACHED_CONTENT_ID]
        val existingHash = prefs[KEY_CACHED_CONTENT_HASH]

        if (!existingId.isNullOrBlank() && existingHash == promptHash) {
            return@withContext existingId
        }

        if (!existingId.isNullOrBlank()) {
            deleteCache(cleanApiKey, existingId)
        }

        val cleanModel = "models/$normalizedModel"
        val payload = buildJsonObject {
            put("model", cleanModel)
            put("displayName", "gemini_voice_session_cache")
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("ttl", "${CACHE_TTL_SECONDS}s")
        }.toString()

        val url = "$BASE_URL/cachedContents"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", cleanApiKey)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val root = json.parseToJsonElement(body).jsonObject
                    val cacheName = root["name"]?.jsonPrimitive?.contentOrNull
                    if (!cacheName.isNullOrBlank()) {
                        dataStore.edit {
                            it[KEY_CACHED_CONTENT_ID] = cacheName
                            it[KEY_CACHED_CONTENT_HASH] = promptHash
                        }
                        logger.d("ContextCacheService: Создан KV-кэш: $cacheName")
                        return@withContext cacheName
                    }
                } else {
                    if (response.code == 404 || response.code == 410) {
                        dataStore.edit {
                            it.remove(KEY_CACHED_CONTENT_ID)
                            it.remove(KEY_CACHED_CONTENT_HASH)
                        }
                    }
                    logger.w("ContextCacheService: Сервер отклонил кэш (${response.code})")
                }
            }
        }.onFailure {
            logger.e("ContextCacheService: Сбой сети при создании кэша", it)
        }

        null
        }
    }

    suspend fun deleteCache(apiKey: String, cacheId: String) = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        if (cleanApiKey.isBlank() || cacheId.isBlank()) return@withContext
        val cleanId = cacheId.removePrefix("cachedContents/")
        val url = "$BASE_URL/cachedContents/$cleanId"

        runCatching {
            httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", cleanApiKey)
                    .delete()
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) {
                    dataStore.edit {
                        it.remove(KEY_CACHED_CONTENT_ID)
                        it.remove(KEY_CACHED_CONTENT_HASH)
                    }
                }
            }
        }
    }
}