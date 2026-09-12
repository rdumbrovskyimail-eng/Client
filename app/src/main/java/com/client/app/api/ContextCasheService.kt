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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextCacheService @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger
) {
    companion object {
        private const val BASE_URL = "https://aiplatform.googleapis.com/v1"
        val KEY_CACHED_CONTENT_ID = stringPreferencesKey("cached_content_id")
        val KEY_CACHED_CONTENT_HASH = stringPreferencesKey("cached_content_hash")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Создает или возвращает существующий серверный кэш для системного промпта.
     * Время жизни кэша (TTL): 4 часа.
     */
    suspend fun getOrCreateCache(
        apiKey: String,
        systemPrompt: String,
        modelName: String = "gemini-2.5-flash-native-audio-latest"
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || systemPrompt.isBlank()) return@withContext null

        val promptHash = systemPrompt.hashCode().toString()
        val prefs = dataStore.data.first()
        val existingId = prefs[KEY_CACHED_CONTENT_ID]
        val existingHash = prefs[KEY_CACHED_CONTENT_HASH]

        // Если кэш уже создан и системный промпт не изменился
        if (!existingId.isNullOrBlank() && existingHash == promptHash) {
            return@withContext existingId
        }

        // Удаляем старый кэш, если промпт изменился
        if (!existingId.isNullOrBlank()) {
            deleteCache(apiKey, existingId)
        }

        // Создаем новый кэш через Vertex AI REST API
        val modelPath = if (modelName.startsWith("publishers/")) modelName else "publishers/google/models/$modelName"
        val payload = buildJsonObject {
            put("model", modelPath)
            put("displayName", "gemini_voice_session_cache")
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("ttl", "14400s") // 4 часа
        }.toString()

        val url = "$BASE_URL/cachedContents?key=${apiKey.trim()}"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey.trim())
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
                        logger.d("ContextCacheService: Создан серверный KV-кэш: $cacheName (Скидка 75%, TTFT ~110 мс)")
                        return@withContext cacheName
                    }
                } else {
                    logger.w("ContextCacheService: Создание кэша отклонено (${response.code}): $body")
                }
            }
        }.onFailure {
            logger.e("ContextCacheService: Сбой сети при создании кэша", it)
        }

        null
    }

    /**
     * Удаляет серверный кэш по истечении сессии или смене темы.
     */
    suspend fun deleteCache(apiKey: String, cacheId: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || cacheId.isBlank()) return@withContext
        val cleanId = cacheId.removePrefix("cachedContents/")
        val url = "$BASE_URL/cachedContents/$cleanId?key=${apiKey.trim()}"

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey.trim())
            .delete()
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    dataStore.edit {
                        it.remove(KEY_CACHED_CONTENT_ID)
                        it.remove(KEY_CACHED_CONTENT_HASH)
                    }
                    logger.d("ContextCacheService: Серверный кэш успешно удален")
                }
            }
        }
    }
}