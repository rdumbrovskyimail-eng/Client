// >>> FILE: app/src/main/java/com/client/app/forvo/ForvoRepository.kt
package com.client.app.forvo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.util.AppLogger
import com.client.app.util.CryptoManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class Pronunciation(
    val query: String,
    val mp3Url: String,
    val country: String?,
    val sex: String?,
    val rating: Int,
    val username: String?,
    val fetchedAtMs: Long = System.currentTimeMillis()
) {
    fun isStale(): Boolean = System.currentTimeMillis() - fetchedAtMs > ForvoRepository.URL_TTL_MS
}

sealed interface ForvoResult {
    data class Found(val pronunciation: Pronunciation) : ForvoResult
    data object NotFound : ForvoResult
    data object NoApiKey : ForvoResult
    data object QuotaExceeded : ForvoResult
    data class Failed(val reason: String) : ForvoResult
}

data class ForvoQuota(val used: Int, val limit: Int) {
    val isExhausted: Boolean get() = used >= limit
}

@Singleton
class ForvoRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger,
    private val cryptoManager: CryptoManager
) {
    companion object {
        val KEY_FORVO_API = stringPreferencesKey("forvo_api_key")
        val KEY_FORVO_HOST = stringPreferencesKey("forvo_host")
        val KEY_QUOTA_LIMIT = intPreferencesKey("forvo_quota_limit")
        val KEY_QUOTA_USED = intPreferencesKey("forvo_quota_used")
        val KEY_QUOTA_DAY = stringPreferencesKey("forvo_quota_day")

        const val HOST_FREE = "https://apifree.forvo.com"
        const val DEFAULT_QUOTA_LIMIT = 500
        const val URL_TTL_MS = 90L * 60 * 1000
        private const val MISS_TTL_MS = 30L * 60 * 1000

        // E-17: Математически выверенная функция границы 22:00 UTC
        fun calculateForvoDayId(instant: Instant): String {
            val shifted = instant.minus(Duration.ofHours(22))
            return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(shifted)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Semaphore(3)
    private val hits = ConcurrentHashMap<String, Pronunciation>()
    private val misses = ConcurrentHashMap<String, Long>()

    private val _quota = MutableStateFlow(ForvoQuota(0, DEFAULT_QUOTA_LIMIT))
    val quota: StateFlow<ForvoQuota> = _quota.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.data.collect { prefs ->
                val currentDay = calculateForvoDayId(Instant.now())
                val savedDay = prefs[KEY_QUOTA_DAY] ?: ""
                val limit = prefs[KEY_QUOTA_LIMIT] ?: DEFAULT_QUOTA_LIMIT
                val used = if (savedDay == currentDay) prefs[KEY_QUOTA_USED] ?: 0 else 0
                _quota.value = ForvoQuota(used, limit)
            }
        }
    }

    suspend fun freshUrl(rawWord: String, lang: String): String? = withContext(Dispatchers.IO) {
        val apiKey = readApiKey()
        if (apiKey.isEmpty()) return@withContext null

        when (val result = lookupInternal(rawWord, lang, apiKey)) {
            is ForvoResult.Found -> result.pronunciation.mp3Url
            else -> null
        }
    }

    suspend fun lookupBatch(
        words: List<String>,
        lang: String,
        onResult: (word: String, result: ForvoResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val apiKey = readApiKey()
        if (apiKey.isEmpty()) {
            words.forEach { onResult(it, ForvoResult.NoApiKey) }
            return@withContext
        }

        words.distinctBy { it.lowercase() }.forEach { word ->
            launch {
                val res = lookupInternal(word, lang, apiKey)
                onResult(word, res)
            }
        }
    }

    private suspend fun lookupInternal(rawWord: String, lang: String, apiKey: String): ForvoResult {
        val targetLang = lang.lowercase().trim().take(2).ifBlank { "de" }
        val query = cleanWord(rawWord, targetLang)
        if (query.isEmpty()) return ForvoResult.NotFound

        val cacheKey = "$targetLang:${query.lowercase()}"
        hits[cacheKey]?.let { if (!it.isStale()) return ForvoResult.Found(it) else hits.remove(cacheKey) }
        misses[cacheKey]?.let { if (System.currentTimeMillis() - it < MISS_TTL_MS) return ForvoResult.NotFound }

        if (_quota.value.isExhausted) return ForvoResult.QuotaExceeded

        return gate.withPermit {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val host = readHost()
            val url = "$host/key/$apiKey/format/json/action/standard-pronunciation/word/$encoded/language/$targetLang"

            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use ForvoResult.Failed("HTTP ${resp.code}")

                    // Ошибка №6 [DEFECT]: Учитываем списание квоты сразу за совершенный поисковый запрос к API
                    registerSuccessfulPlayback()

                    val root = json.parseToJsonElement(body).jsonObject

                    // Ошибка №5 [DEFECT]: Валидация ошибок Forvo для исключения отравления Negative Cache (misses)
                    if (root.containsKey("errors")) {
                        val msg = root["errors"].toString()
                        return@use ForvoResult.Failed("Forvo API: $msg")
                    }

                    val item = root["items"]?.jsonArray?.firstOrNull()?.jsonObject
                    val mp3 = item?.get("pathmp3")?.jsonPrimitive?.contentOrNull?.replace("http://", "https://")

                    if (!mp3.isNullOrBlank()) {
                        val pronunciation = Pronunciation(
                            query = query,
                            mp3Url = mp3,
                            country = item["country"]?.jsonPrimitive?.contentOrNull,
                            sex = item["sex"]?.jsonPrimitive?.contentOrNull,
                            rating = item["rate"]?.jsonPrimitive?.intOrNull ?: 0,
                            username = item["username"]?.jsonPrimitive?.contentOrNull
                        )
                        hits[cacheKey] = pronunciation
                        ForvoResult.Found(pronunciation)
                    } else {
                        misses[cacheKey] = System.currentTimeMillis()
                        ForvoResult.NotFound
                    }
                }
            }.getOrElse {
                ForvoResult.Failed(it.localizedMessage ?: "Сбой сети")
            }
        }
    }

    // E-17, E-18: Персистентная регистрация успешного выполнения запроса
    fun registerSuccessfulPlayback() {
        CoroutineScope(Dispatchers.IO).launch {
            val currentDay = calculateForvoDayId(Instant.now())
            dataStore.edit { prefs ->
                val savedDay = prefs[KEY_QUOTA_DAY] ?: ""
                val currentUsed = if (savedDay == currentDay) prefs[KEY_QUOTA_USED] ?: 0 else 0
                val newUsed = currentUsed + 1
                prefs[KEY_QUOTA_DAY] = currentDay
                prefs[KEY_QUOTA_USED] = newUsed
                _quota.value = ForvoQuota(newUsed, prefs[KEY_QUOTA_LIMIT] ?: DEFAULT_QUOTA_LIMIT)
            }
        }
    }

    fun clearMisses() = misses.clear()

    private suspend fun readApiKey(): String =
        cryptoManager.decrypt(dataStore.data.first()[KEY_FORVO_API]?.trim().orEmpty())

    private suspend fun readHost(): String =
        dataStore.data.first()[KEY_FORVO_HOST]?.trim()?.ifBlank { HOST_FREE } ?: HOST_FREE

    private fun cleanWord(raw: String, lang: String): String {
        val normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        var w = normalized.replace(Regex("[^\\p{L}\\p{M}\\d\\s\\-'’]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (w.isEmpty()) return ""

        val articles = ARTICLES[lang] ?: emptySet()
        val firstSpace = w.indexOf(' ')
        if (firstSpace > 0) {
            val head = w.substring(0, firstSpace).lowercase()
            if (head in articles) w = w.substring(firstSpace + 1).trim()
        }

        if (lang in setOf("fr", "it", "ca")) {
            w = w.replace(Regex("^(l|d|dell|nell|all|un|qu)['’]", RegexOption.IGNORE_CASE), "").trim()
        }
        return w
    }

    private val ARTICLES: Map<String, Set<String>> = mapOf(
        "de" to setOf("der", "die", "das", "dem", "den", "des", "ein", "eine"),
        "en" to setOf("the", "a", "an"),
        "fr" to setOf("le", "la", "les", "un", "une", "des", "du"),
        "es" to setOf("el", "la", "los", "las", "un", "una")
    )
}