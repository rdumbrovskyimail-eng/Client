// >>> FILE: app/src/main/java/com/client/app/forvo/ForvoRepository.kt
package com.client.app.forvo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/* ═══════════════════════════════════════════════════════════════════════════
 *  МОДЕЛИ ДАННЫХ FORVO
 * ═══════════════════════════════════════════════════════════════════════════ */

data class Pronunciation(
    val query: String,
    val mp3Url: String,
    val country: String?,
    val sex: String?,
    val rating: Int,
    val username: String?,
    /** Время получения ссылки. Ссылки Forvo валидны ровно 2 часа */
    val fetchedAtMs: Long = System.currentTimeMillis()
) {
    /** 90 минут с запасом, чтобы не воспроизводить протухшие URL */
    fun isStale(): Boolean =
        System.currentTimeMillis() - fetchedAtMs > ForvoRepository.URL_TTL_MS
}

sealed interface ForvoResult {
    data class Found(val pronunciation: Pronunciation) : ForvoResult
    data object NotFound : ForvoResult
    data object NoApiKey : ForvoResult
    data object QuotaExceeded : ForvoResult
    data class Failed(val reason: String) : ForvoResult
}

data class ForvoQuota(val used: Int, val limit: Int) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val isExhausted: Boolean get() = used >= limit
}

@Singleton
class ForvoRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger
) {
    companion object {
        val KEY_FORVO_API = stringPreferencesKey("forvo_api_key")
        val KEY_FORVO_HOST = stringPreferencesKey("forvo_host")
        val KEY_QUOTA_LIMIT = intPreferencesKey("forvo_quota_limit")
        private val KEY_QUOTA_USED = intPreferencesKey("forvo_quota_used")
        private val KEY_QUOTA_DAY = stringPreferencesKey("forvo_quota_day")

        const val HOST_FREE = "https://apifree.forvo.com"
        const val DEFAULT_QUOTA_LIMIT = 500

        /** 90 минут кэша строго в рамках 2-часового окна жизни ссылок Forvo CDN */
        const val URL_TTL_MS = 90L * 60 * 1000
        /** Отрицательный кэш: не тратим квоту на повторный поиск отсутствующих слов */
        private const val MISS_TTL_MS = 12L * 60 * 60 * 1000

        private const val MAX_PARALLEL = 3
        private const val MAX_RETRIES = 2
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Semaphore(MAX_PARALLEL)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val hits = ConcurrentHashMap<String, Pronunciation>()
    private val misses = ConcurrentHashMap<String, Long>()

    @Volatile private var currentDay: String = forvoDayKey()
    private val _quota = MutableStateFlow(ForvoQuota(0, DEFAULT_QUOTA_LIMIT))
    val quota: StateFlow<ForvoQuota> = _quota.asStateFlow()

    private val quotaSyncTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @OptIn(FlowPreview::class)
    init {
        ioScope.launch { refreshQuota() }
        ioScope.launch {
            quotaSyncTrigger.debounce(500).collect {
                persistQuota()
            }
        }
    }

    /* ═════════════════════════ ПУБЛИЧНОЕ API ═════════════════════════ */

    suspend fun lookupBatch(
        words: List<String>,
        lang: String,
        onResult: (word: String, result: ForvoResult) -> Unit
    ) = coroutineScope {
        val apiKey = readApiKey()
        if (apiKey.isEmpty()) {
            words.forEach { onResult(it, ForvoResult.NoApiKey) }
            return@coroutineScope
        }

        // Синхронизируем квоту с DataStore один раз перед запуском батча
        refreshQuota()

        val unique = words
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        unique.forEach { word ->
            launch {
                val res = lookupInternal(word, lang, apiKey)
                onResult(word, res)
            }
        }
    }

    suspend fun lookup(rawWord: String, lang: String = "de"): ForvoResult =
        withContext(Dispatchers.IO) {
            val apiKey = readApiKey()
            if (apiKey.isEmpty()) return@withContext ForvoResult.NoApiKey
            lookupInternal(rawWord, lang, apiKey)
        }

    private suspend fun lookupInternal(rawWord: String, lang: String, apiKey: String): ForvoResult {
        val targetLang = normalizeLang(lang)
        val query = cleanWord(rawWord, targetLang)
        if (query.isEmpty()) return ForvoResult.NotFound

        val cacheKey = "$targetLang:${query.lowercase()}"

        // 1. Проверка оперативного кэша метаданных
        hits[cacheKey]?.let { cached ->
            if (!cached.isStale()) return ForvoResult.Found(cached)
            hits.remove(cacheKey)
        }

        // 2. Проверка отрицательного кэша
        misses[cacheKey]?.let { at ->
            if (System.currentTimeMillis() - at < MISS_TTL_MS) {
                return ForvoResult.NotFound
            }
            misses.remove(cacheKey)
        }

        // Быстрая проверка in-memory квоты без блокирующего чтения DataStore
        if (_quota.value.isExhausted) return ForvoResult.QuotaExceeded

        return gate.withPermit {
            var result = request(apiKey, "standard-pronunciation", query, targetLang, null)

            if (result is ForvoResult.NotFound) {
                result = request(apiKey, "word-pronunciations", query, targetLang, "rate-desc")
            }

            when (result) {
                is ForvoResult.Found -> hits[cacheKey] = result.pronunciation
                is ForvoResult.NotFound -> misses[cacheKey] = System.currentTimeMillis()
                else -> Unit
            }
            result
        }
    }

    fun registerPlayback() {
        bump(1)
    }

    suspend fun freshUrl(word: String, lang: String): String? {
        val targetLang = normalizeLang(lang)
        val key = "$targetLang:${cleanWord(word, targetLang).lowercase()}"
        hits[key]?.takeIf { !it.isStale() }?.let { return it.mp3Url }
        return (lookup(word, lang) as? ForvoResult.Found)?.pronunciation?.mp3Url
    }

    fun clearCache() {
        hits.clear()
        misses.clear()
    }

    /* ═════════════════════════ HTTP СЕТЕВОЙ СТЕК ═════════════════════════ */

    private suspend fun request(
        apiKey: String,
        action: String,
        word: String,
        lang: String,
        order: String?
    ): ForvoResult {
        val host = readHost()
        val encoded = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        val url = buildString {
            append(host)
            append("/key/").append(apiKey)
            append("/format/json")
            append("/action/").append(action)
            append("/word/").append(encoded)
            append("/language/").append(lang)
            if (order != null) {
                append("/order/").append(order)
                append("/limit/1")
            }
        }

        var attempt = 0
        while (true) {
            try {
                bump(1)
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()

                    when {
                        resp.code == 429 -> {
                            if (attempt++ < MAX_RETRIES) {
                                delay(700L * attempt)
                                return@use
                            }
                            return ForvoResult.QuotaExceeded
                        }
                        resp.code == 401 || resp.code == 403 ->
                            return ForvoResult.Failed("Forvo: ключ отклонён (${resp.code})")
                        resp.code >= 500 -> {
                            if (attempt++ < MAX_RETRIES) {
                                delay(500L * attempt)
                                return@use
                            }
                            return ForvoResult.Failed("Forvo: сервер ${resp.code}")
                        }
                        !resp.isSuccessful ->
                            return ForvoResult.Failed("Forvo: HTTP ${resp.code}")
                    }

                    return parseBody(body, word)
                }
            } catch (e: Exception) {
                if (attempt++ < MAX_RETRIES) {
                    delay(500L * attempt)
                    continue
                }
                logger.e("Forvo query error: $word", e)
                return ForvoResult.Failed(e.localizedMessage ?: "Сбой сети")
            }
        }
    }

    private fun parseBody(body: String, query: String): ForvoResult {
        if (body.isBlank()) return ForvoResult.NotFound
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ForvoResult.Failed("Forvo: некорректный ответ")

        root["error"]?.let { err ->
            val text = runCatching { err.jsonPrimitive.content }.getOrElse { err.toString() }
            return if (text.contains("limit", true)) ForvoResult.QuotaExceeded
            else ForvoResult.Failed("Forvo: $text")
        }

        val item = root["items"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return ForvoResult.NotFound

        val mp3 = item["pathmp3"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.replace("http://", "https://")
            ?: return ForvoResult.NotFound

        return ForvoResult.Found(
            Pronunciation(
                query = query,
                mp3Url = mp3,
                country = item["country"]?.jsonPrimitive?.contentOrNull,
                sex = item["sex"]?.jsonPrimitive?.contentOrNull,
                rating = item["rate"]?.jsonPrimitive?.intOrNull ?: 0,
                username = item["username"]?.jsonPrimitive?.contentOrNull
            )
        )
    }

    /* ═════════════════════════ СЧЁТЧИК КВОТЫ (22:00 UTC) ═════════════════════════ */

    private fun forvoDayKey(): String =
        Instant.now().minusSeconds(22 * 3600).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private suspend fun refreshQuota() {
        val prefs = dataStore.data.first()
        val today = forvoDayKey()
        currentDay = today
        val storedDay = prefs[KEY_QUOTA_DAY]
        val limit = prefs[KEY_QUOTA_LIMIT] ?: DEFAULT_QUOTA_LIMIT

        if (storedDay != today) {
            dataStore.edit {
                it[KEY_QUOTA_DAY] = today
                it[KEY_QUOTA_USED] = 0
            }
            _quota.update { ForvoQuota(0, limit) }
        } else {
            val onDisk = prefs[KEY_QUOTA_USED] ?: 0
            _quota.update { current ->
                ForvoQuota(maxOf(onDisk, current.used), limit)
            }
        }
    }

    private fun bump(n: Int) {
        val today = forvoDayKey()
        _quota.update { cur ->
            if (currentDay != today) {
                currentDay = today
                ForvoQuota(n, cur.limit)
            } else {
                cur.copy(used = cur.used + n)
            }
        }
        quotaSyncTrigger.tryEmit(Unit)
    }

    private suspend fun persistQuota() {
        runCatching {
            val today = forvoDayKey()
            val snapshotUsed = _quota.value.used
            dataStore.edit { prefs ->
                val storedDay = prefs[KEY_QUOTA_DAY]
                if (storedDay != today) {
                    prefs[KEY_QUOTA_DAY] = today
                    prefs[KEY_QUOTA_USED] = snapshotUsed
                } else {
                    prefs[KEY_QUOTA_USED] = maxOf(prefs[KEY_QUOTA_USED] ?: 0, snapshotUsed)
                }
            }
        }
    }

    private suspend fun readApiKey(): String =
        dataStore.data.first()[KEY_FORVO_API]?.trim().orEmpty()

    private suspend fun readHost(): String =
        dataStore.data.first()[KEY_FORVO_HOST]?.trim()?.ifBlank { HOST_FREE } ?: HOST_FREE

    /* ═════════════════════════ ОЧИСТКА АРТИКЛЕЙ ═════════════════════════ */

    private fun normalizeLang(lang: String): String =
        lang.lowercase().trim().substringBefore('-').ifBlank { "de" }

    private fun cleanWord(raw: String, lang: String): String {
        var w = raw.trim()

        // Очищаем служебные символы, сохраняя все буквы с диакритикой, дефисы и апострофы
        w = w.replace(Regex("[^\\p{L}\\p{M}\\d\\s\\-'’]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (w.isEmpty()) return ""

        val articles = ARTICLES[lang] ?: emptySet()
        if (articles.isNotEmpty()) {
            val firstSpace = w.indexOf(' ')
            if (firstSpace > 0) {
                val head = w.substring(0, firstSpace).lowercase()
                if (head in articles) w = w.substring(firstSpace + 1).trim()
            }
        }

        if (lang in ELISION_LANGS) {
            w = w.replace(
                Regex("^(l|d|dell|nell|all|un|qu)['’]", RegexOption.IGNORE_CASE),
                ""
            ).trim()
        }

        return w
    }

    private val ARTICLES: Map<String, Set<String>> = mapOf(
        "de" to setOf(
            "der", "die", "das", "dem", "den", "des",
            "ein", "eine", "einer", "eines", "einem", "einen"
        ),
        "en" to setOf("the", "a", "an"),
        "fr" to setOf("le", "la", "les", "un", "une", "des", "du", "de"),
        "es" to setOf("el", "la", "los", "las", "un", "una", "unos", "unas", "lo"),
        "it" to setOf("il", "lo", "la", "i", "gli", "le", "un", "uno", "una"),
        "pt" to setOf("o", "a", "os", "as", "um", "uma"),
        "nl" to setOf("de", "het", "een")
    )

    private val ELISION_LANGS = setOf("fr", "it", "ca")
}