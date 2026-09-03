package com.client.app.forvo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForvoRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger
) {
    companion object {
        val KEY_FORVO_API = stringPreferencesKey("forvo_api_key")
        private const val HOST = "https://apifree.forvo.com"

        // Мультиязычная фильтрация артиклей (с поддержкой апострофов l' и d' без пробела)
        private val ARTICLE_REGEX = Regex(
            "^(der|die|das|dem|den|des|ein|eine|eines|einer|einem|einen|the|a|an|le|la|les|un|une|des|el|la|los|las|lo|il|i|gli|uno)\\s+|^([ldl]['’])",
            RegexOption.IGNORE_CASE
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun fetchPronunciationUrl(rawWord: String, lang: String = "de"): String? = withContext(Dispatchers.IO) {
        val apiKey = dataStore.data.first()[KEY_FORVO_API]?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            logger.w("Forvo: API Key не задан")
            return@withContext null
        }

        val cleanWord = rawWord
            .replace(ARTICLE_REGEX, "")
            .replace(Regex("[^\\p{L}\\p{M}\\d\\s-]"), "")
            .trim()

        if (cleanWord.isEmpty()) return@withContext null

        val targetLang = lang.lowercase().trim().ifBlank { "de" }
        val cacheKey = "$targetLang:${cleanWord.lowercase()}"
        cache[cacheKey]?.let { return@withContext it }

        val encoded = URLEncoder.encode(cleanWord, "UTF-8").replace("+", "%20")
        val url = "$HOST/key/$apiKey/format/json/action/standard-pronunciation/word/$encoded/language/$targetLang"

        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val mp3 = json.parseToJsonElement(body)
                    .jsonObject["items"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("pathmp3")?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.replace("http://", "https://")

                if (mp3 != null) cache[cacheKey] = mp3
                mp3
            }
        } catch (e: Exception) {
            logger.e("Forvo query error", e)
            null
        }
    }
}