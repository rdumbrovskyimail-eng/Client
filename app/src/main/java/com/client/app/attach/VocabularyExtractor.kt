// >>> FILE: app/src/main/java/com/client/app/attach/VocabularyExtractor.kt
package com.client.app.attach

import android.util.Base64
import com.client.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/* ═══════════════════════════════════════════════════════════════════════════
 *  МОДЕЛИ ДАННЫХ ИЗВЛЕЧЕНИЯ ЛЕКСИКИ
 * ═══════════════════════════════════════════════════════════════════════════ */

data class VocabItem(
    /** Словарная форма в норме языка (например, "der Tisch") */
    val lemma: String,
    /** Очищенная форма для поиска в Forvo (например, "Tisch") */
    val forvoQuery: String,
    val translation: String? = null,
    val partOfSpeech: String? = null,
    val example: String? = null
)

data class MaterialAnalysis(
    /** ISO 639-1 код языка материала */
    val language: String,
    val title: String?,
    val fullText: String,
    val summary: String?,
    val vocabulary: List<VocabItem>,
    /** Список предупреждений (например, о страницах, которые не удалось разобрать) */
    val warnings: List<String> = emptyList()
)

sealed interface AnalysisResult {
    data class Success(val analysis: MaterialAnalysis, val modelUsed: String = "") : AnalysisResult
    data class Failure(val reason: String) : AnalysisResult
}

@Singleton
class VocabularyExtractor @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

        /** Общедоступная проверенная модель с глубоким OCR по умолчанию */
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        /** Проверенный вседоступный fallback */
        private const val FALLBACK_MODEL = "gemini-2.5-flash"

        private const val MAX_IMAGES_PER_CALL = 4
        private const val MAX_VOCAB = 250
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    suspend fun analyze(
        apiKey: String,
        images: List<ByteArray>,
        plainText: String,
        forLanguageLearning: Boolean,
        model: String = DEFAULT_MODEL,
        targetLanguageHint: String? = null
    ): AnalysisResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext AnalysisResult.Failure("Нет Gemini API Key")
        if (images.isEmpty() && plainText.isBlank()) {
            return@withContext AnalysisResult.Failure("Вложение пустое")
        }

        val batches = if (images.isEmpty()) listOf(emptyList())
        else images.chunked(MAX_IMAGES_PER_CALL)

        val merged = mutableListOf<MaterialAnalysis>()
        val failures = mutableListOf<String>()
        var effectiveModel = model.ifBlank { DEFAULT_MODEL }

        batches.forEachIndexed { index, batch ->
            coroutineContext.ensureActive()
            val textPart = if (index == 0) plainText else ""
            val pageRange = if (images.isEmpty()) "Текст"
            else "Стр. ${index * MAX_IMAGES_PER_CALL + 1}–${minOf((index + 1) * MAX_IMAGES_PER_CALL, images.size)}"

            when (val r = callOnce(apiKey, effectiveModel, batch, textPart, forLanguageLearning, targetLanguageHint)) {
                is AnalysisResult.Success -> {
                    merged.add(r.analysis)
                    if (r.modelUsed.isNotBlank()) {
                        effectiveModel = r.modelUsed
                    }
                }
                is AnalysisResult.Failure -> {
                    failures.add("$pageRange: ${r.reason}")
                }
            }
        }

        if (merged.isEmpty()) {
            return@withContext AnalysisResult.Failure(
                "Не удалось извлечь содержимое:\n${failures.joinToString("\n")}"
            )
        }

        // Дедупликация лексики по forvoQuery без учёта регистра
        val seen = LinkedHashMap<String, VocabItem>()
        merged.flatMap { it.vocabulary }.forEach { item ->
            val key = item.forvoQuery.lowercase().trim()
            if (key.isNotEmpty() && !seen.containsKey(key)) {
                seen[key] = item
            }
        }

        AnalysisResult.Success(
            MaterialAnalysis(
                language = merged.firstOrNull { it.language.isNotBlank() }?.language ?: "de",
                title = merged.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) },
                fullText = merged.joinToString("\n\n") { it.fullText }.trim(),
                summary = merged.mapNotNull { it.summary?.takeIf(String::isNotBlank) }
                    .joinToString(" ").takeIf { it.isNotBlank() },
                vocabulary = seen.values.take(MAX_VOCAB),
                warnings = failures
            )
        )
    }

    private suspend fun callOnce(
        apiKey: String,
        model: String,
        images: List<ByteArray>,
        plainText: String,
        forLanguageLearning: Boolean,
        langHint: String?,
        isFallbackAttempt: Boolean = false
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val modelId = model.removePrefix("models/").trim()
        val url = "$ENDPOINT/$modelId:generateContent?key=${apiKey.trim()}"

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        images.forEach { bytes ->
                            add(buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", "image/jpeg")
                                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                })
                            })
                        }
                        if (plainText.isNotBlank()) {
                            add(buildJsonObject { put("text", plainText.take(200_000)) })
                        }
                        add(buildJsonObject {
                            put("text", buildInstruction(forLanguageLearning, langHint))
                        })
                    })
                })
            })

            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", schema(forLanguageLearning))
                put("temperature", 0.1)
                put("maxOutputTokens", 32768)
                // mediaResolution используется только на первичной модели 3.x во избежание ошибки 400
                if (!isFallbackAttempt && !modelId.contains("2.0") && !modelId.contains("1.5")) {
                    put("mediaResolution", "MEDIA_RESOLUTION_HIGH")
                }
            })

            put("safetySettings", buildJsonArray {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { cat ->
                    add(buildJsonObject {
                        put("category", cat)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            })
        }

        try {
            coroutineContext.ensureActive()
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()

                // Откат при отсутствии модели (404) или несовместимости ее параметров (400)
                val isModelOrParamUnsupported = resp.code == 404 || 
                    (resp.code == 400 && (raw.contains("mediaResolution", ignoreCase = true) || raw.contains("not supported", ignoreCase = true)))

                if (isModelOrParamUnsupported && !isFallbackAttempt && modelId != FALLBACK_MODEL) {
                    logger.w("VocabularyExtractor: $modelId вернул ${resp.code} (несовместимость параметров), откат на $FALLBACK_MODEL")
                    return@withContext callOnce(
                        apiKey, FALLBACK_MODEL, images, plainText,
                        forLanguageLearning, langHint, isFallbackAttempt = true
                    )
                }

                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(raw).jsonObject["error"]
                            ?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull()
                    return@withContext AnalysisResult.Failure("Ошибка (${resp.code}): ${msg.orEmpty()}".trim())
                }
                val parsed = parseResponse(raw)
                if (parsed is AnalysisResult.Success) {
                    parsed.copy(modelUsed = modelId)
                } else {
                    parsed
                }
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            logger.e("VocabularyExtractor query failed", e)
            AnalysisResult.Failure(e.localizedMessage ?: "Сбой сети")
        }
    }

    private fun parseResponse(raw: String): AnalysisResult {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return AnalysisResult.Failure("Неожиданный ответ сервера")

        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return AnalysisResult.Failure("Модель вернула пустой результат")

        if (candidate["finishReason"]?.jsonPrimitive?.contentOrNull == "SAFETY") {
            return AnalysisResult.Failure("Материал заблокирован фильтрами безопасности Google")
        }

        val payload = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { p ->
                val o = p.jsonObject
                if (o["thought"]?.jsonPrimitive?.booleanOrNull == true) null
                else o["text"]?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString("")
            ?.trim()
            ?: return AnalysisResult.Failure("Отсутствует текстовая часть ответа")

        val obj = runCatching {
            json.parseToJsonElement(payload.removeSurrounding("```json", "```").trim()).jsonObject
        }.getOrElse {
            return AnalysisResult.Failure("Модель вернула невалидный JSON")
        }

        val vocab = obj["vocabulary"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val lemma = o["lemma"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (lemma.isEmpty()) return@mapNotNull null
            VocabItem(
                lemma = lemma,
                forvoQuery = o["forvo_query"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: lemma,
                translation = o["translation"]?.jsonPrimitive?.contentOrNull,
                partOfSpeech = o["part_of_speech"]?.jsonPrimitive?.contentOrNull,
                example = o["example"]?.jsonPrimitive?.contentOrNull
            )
        } ?: emptyList()

        return AnalysisResult.Success(
            MaterialAnalysis(
                language = obj["language"]?.jsonPrimitive?.contentOrNull
                    ?.lowercase()?.take(2).orEmpty().ifBlank { "de" },
                title = obj["title"]?.jsonPrimitive?.contentOrNull,
                fullText = obj["full_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull,
                vocabulary = vocab
            )
        )
    }

    private fun buildInstruction(forLanguageLearning: Boolean, langHint: String?): String {
        val base = buildString {
            append("Внимательно изучи приложенный документ или изображение. ")
            append("В full_text перенеси весь текст документа максимально точно, сохраняя структуру. ")
            append("В language укажи ISO 639-1 код языка материала")
            if (!langHint.isNullOrBlank()) append(" (ожидается '$langHint')")
            append(". ")
        }
        if (!forLanguageLearning) return base + "Поле vocabulary оставь пустым списком."

        return base + """
            Это учебный языковой материал. Сформируй в поле vocabulary ПОЛНЫЙ список
            всех ключевых и знаменательных слов и выражений из текста (существительные,
            глаголы, прилагательные, наречия). Не сокращай и не пропускай лексику.
            
            Требования к полям каждого элемента:
            • lemma — словарная форма (для немецких существительных укажи артикль: "der Tisch", для глаголов — инфинитив).
            • forvo_query — СТРОГО то же слово БЕЗ артикля и пометок, предназначенное для точного аудиопоиска в базе Forvo (например: "Tisch").
            • translation — перевод на русский язык.
            • part_of_speech — часть речи.
            • example — короткое предложение из текста с этим словом.
        """.trimIndent()
    }

    private fun schema(forLanguageLearning: Boolean): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("language", buildJsonObject {
                put("type", "STRING")
                put("description", "ISO 639-1 код языка документа")
            })
            put("title", buildJsonObject { put("type", "STRING") })
            put("full_text", buildJsonObject { put("type", "STRING") })
            put("summary", buildJsonObject { put("type", "STRING") })
            put("vocabulary", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("lemma", buildJsonObject { put("type", "STRING") })
                        put("forvo_query", buildJsonObject { put("type", "STRING") })
                        put("translation", buildJsonObject { put("type", "STRING") })
                        put("part_of_speech", buildJsonObject { put("type", "STRING") })
                        put("example", buildJsonObject { put("type", "STRING") })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("lemma"))
                        add(JsonPrimitive("forvo_query"))
                    })
                    put("propertyOrdering", buildJsonArray {
                        add(JsonPrimitive("lemma"))
                        add(JsonPrimitive("forvo_query"))
                        add(JsonPrimitive("translation"))
                        add(JsonPrimitive("part_of_speech"))
                        add(JsonPrimitive("example"))
                    })
                })
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("language"))
            add(JsonPrimitive("full_text"))
            if (forLanguageLearning) add(JsonPrimitive("vocabulary"))
        })
        put("propertyOrdering", buildJsonArray {
            add(JsonPrimitive("language"))
            add(JsonPrimitive("title"))
            add(JsonPrimitive("full_text"))
            add(JsonPrimitive("summary"))
            add(JsonPrimitive("vocabulary"))
        })
    }
}