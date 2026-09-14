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
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun generateCacheFingerprint(model: String, prompt: String): String {
        val input = "model:$model\u0000prompt:$prompt"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun getOrCreateCache(
        apiKey: String,
        systemPrompt: String,
        modelName: String = "gemini-3.1-flash-live-preview"
    ): String? = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        if (cleanApiKey.isBlank() || systemPrompt.isBlank()) return@withContext null

        val estimatedTokens = systemPrompt.length / 4
        if (estimatedTokens < MIN_TOKENS_FOR_CACHE) {
            return@withContext null
        }

        val promptHash = generateCacheFingerprint(modelName, systemPrompt)
        val prefs = dataStore.data.first()
        val existingId = prefs[KEY_CACHED_CONTENT_ID]
        val existingHash = prefs[KEY_CACHED_CONTENT_HASH]

        if (!existingId.isNullOrBlank() && existingHash == promptHash) {
            return@withContext existingId
        }

        if (!existingId.isNullOrBlank()) {
            deleteCache(cleanApiKey, existingId)
        }

        val cleanModel = if (modelName.startsWith("models/")) modelName else "models/$modelName"
        val payload = buildJsonObject {
            put("model", cleanModel)
            put("displayName", "gemini_voice_session_cache")
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("ttl", "14400s")
        }.toString()

        val url = "$BASE_URL/cachedContents?key=$cleanApiKey"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
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
                    logger.w("ContextCacheService: Сервер отклонил кэш (${response.code}): $body")
                }
            }
        }.onFailure {
            logger.e("ContextCacheService: Сбой сети при создании кэша", it)
        }

        null
    }

    suspend fun deleteCache(apiKey: String, cacheId: String) = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        if (cleanApiKey.isBlank() || cacheId.isBlank()) return@withContext
        val cleanId = cacheId.removePrefix("cachedContents/")
        val url = "$BASE_URL/cachedContents/$cleanId?key=$cleanApiKey"

        runCatching {
            httpClient.newCall(Request.Builder().url(url).delete().build()).execute().use { response ->
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