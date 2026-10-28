package com.example.network

import android.util.Base64
import com.example.data.AiProvider
import com.example.data.OracleSettings
import com.example.data.SttProvider
import com.example.data.isActiveSupportedTextModel
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class AiOracleResult(
    val question: String,
    val answer: String,
    val rawAnswer: String,
    val provider: AiProvider,
    val model: String,
    val latencyMs: Long
)

object AiOracleClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json".toMediaType()
    private val wavMediaType = "audio/wav".toMediaType()

    private val Q_PREFIX_REGEX = Regex("^(?:Q|Question|Вопрос)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val A_PREFIX_REGEX = Regex("^(?:A|Answer|Ответ)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val CLOSED_THINK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val UNCLOSED_THINK_REGEX = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val ANSWER_PREFIX_REGEX = Regex("^(?:A|Answer|Ответ|Итог)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val STT_HALLUCINATIONS = listOf(
        "субтитры сделал",
        "субтитры создавал",
        "редактор субтитров",
        "продолжение следует",
        "подпишись на канал",
        "спасибо за просмотр",
        "dimatorzok",
        "amara.org",
        "subtitles by",
        "thank you for watching"
    )

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun OkHttpClient.executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private suspend fun executeWithTransientRetry(request: Request): Pair<Int, String> {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val (code, body) = okHttpClient.executeCancellable(request).use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
                val isTransientStatus = code == 429 || code == 502 || code == 503 || code == 504
                if (isTransientStatus && attempt == 0) {
                    attempt++
                    delay(450L)
                    continue
                }
                return code to body
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt == 0 && e !is UnknownHostException) {
                    attempt++
                    delay(350L)
                    continue
                }
                throw e
            }
        }
    }

    internal fun isModelNotFoundError(code: Int, errorBody: String): Boolean {
        if (code == 404) return true
        val lower = errorBody.lowercase()
        return (code == 400 || code == 410) && (
            lower.contains("not found") ||
                lower.contains("does not exist") ||
                lower.contains("decommissioned") ||
                lower.contains("deprecated") ||
                lower.contains("model_not_found")
            )
    }

    private fun formatHttpError(
        providerName: String,
        model: String?,
        code: Int,
        body: String,
        isEnglish: Boolean
    ): String {
        val detail = extractApiErrorMessage(body)
        val modelTag = if (!model.isNullOrBlank()) " ($model)" else ""
        return when {
            code == 401 || code == 403 -> {
                if (isEnglish) {
                    "Invalid API key for $providerName (HTTP $code)"
                } else {
                    "Неверный API-ключ для $providerName (HTTP $code)"
                }
            }
            isModelNotFoundError(code, body) -> {
                if (isEnglish) {
                    "Model$modelTag is deprecated or not found on $providerName (HTTP $code)"
                } else {
                    "Модель$modelTag снята с поддержки или не найдена у $providerName (HTTP $code)"
                }
            }
            code == 429 -> {
                if (isEnglish) {
                    "$providerName rate limit or quota exceeded (HTTP 429)"
                } else {
                    "Превышен лимит запросов или квота $providerName (HTTP 429)"
                }
            }
            detail.isNotBlank() -> "$providerName HTTP $code$modelTag: $detail"
            else -> "$providerName HTTP $code$modelTag"
        }
    }

    private fun mapNetworkException(e: Throwable, isEnglish: Boolean): Throwable {
        if (e is CancellationException) throw e
        return when (e) {
            is UnknownHostException, is ConnectException -> IllegalStateException(
                if (isEnglish) "No internet connection or server unreachable"
                else "Нет подключения к интернету или сервер недоступен",
                e
            )
            is SocketTimeoutException -> IllegalStateException(
                if (isEnglish) "Server response timed out (20s)"
                else "Превышено время ожидания ответа от сервера (20 с)",
                e
            )
            else -> e
        }
    }

    /**
     * Pre-warms DNS + TCP + TLS 1.3 + HTTP/2 connection to the active AI provider while the user
     * is speaking their question into the microphone, eliminating 150-350ms of handshake latency.
     */
    suspend fun prewarmConnection(settings: OracleSettings) = withContext(Dispatchers.IO) {
        try {
            val provider = settings.selectedProvider
            val key = settings.getResolvedApiKey(provider)
            if (key.isBlank() && provider != AiProvider.OPENROUTER) return@withContext
            val baseUrl = settings.getBaseUrl(provider)
            val pingUrl = when (provider) {
                AiProvider.GEMINI -> "$baseUrl/models?pageSize=1"
                else -> "$baseUrl/models"
            }
            val reqBuilder = Request.Builder()
                .url(pingUrl)
                .head()
            if (provider == AiProvider.GEMINI) {
                reqBuilder.header("x-goog-api-key", key)
            } else if (key.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $key")
            }
            okHttpClient.executeCancellable(reqBuilder.build()).close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best-effort socket pre-warm
        }
    }

    /**
     * Fetches the list of available chat/text models from the selected AI provider's API
     * (`GET {baseUrl}/models` for OpenAI-compatible providers, `/v1beta/models` for Gemini).
     * Note: OpenRouter's `/api/v1/models` endpoint is public and can be fetched even without a key.
     */
    suspend fun fetchAvailableModels(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String = provider.defaultBaseUrl,
        isEnglish: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() && provider != AiProvider.OPENROUTER) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (isEnglish) "Enter an API key for ${provider.displayName} first"
                    else "Сначала введите API-ключ для ${provider.displayName}"
                )
            )
        }

        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }

        try {
            val models = when (provider) {
                AiProvider.GEMINI -> {
                    val req = Request.Builder()
                        .url("$cleanBase/models?pageSize=100")
                        .header("x-goog-api-key", cleanKey)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    val (code, body) = executeWithTransientRetry(req)
                    if (code !in 200..299) {
                        throw IllegalStateException(
                            formatHttpError(provider.displayName, null, code, body, isEnglish)
                        )
                    }
                    val arr = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray
                    val rawGeminiModels = arr?.mapNotNull { el ->
                        val obj = el.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                            ?: return@mapNotNull null
                        val methods = obj["supportedGenerationMethods"]?.jsonArray
                            ?.mapNotNull { m -> m.jsonPrimitive.content }
                        val supportsGenerate = methods == null || methods.contains("generateContent")
                        if (supportsGenerate) name else null
                    }?.filter { id ->
                        isActiveSupportedTextModel(id, AiProvider.GEMINI)
                    } ?: emptyList()

                    val withAliases = (
                        listOf("gemini-flash-latest", "gemini-flash-lite-latest") + rawGeminiModels
                        ).distinct()
                    prioritizeModels(withAliases, provider.presetModels)
                }

                else -> {
                    val reqBuilder = Request.Builder()
                        .url("$cleanBase/models")
                        .header("Accept", "application/json")
                        .get()
                    if (cleanKey.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer $cleanKey")
                    }
                    val (code, body) = executeWithTransientRetry(reqBuilder.build())
                    if (code !in 200..299) {
                        throw IllegalStateException(
                            formatHttpError(provider.displayName, null, code, body, isEnglish)
                        )
                    }
                    val arr = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    val filtered = arr?.mapNotNull { el ->
                        el.jsonObject["id"]?.jsonPrimitive?.content
                    }?.filter { id ->
                        isActiveSupportedTextModel(id, provider)
                    } ?: emptyList()

                    val prioritized = prioritizeModels(filtered, provider.presetModels)
                    if (provider == AiProvider.OPENROUTER) {
                        prioritized.take(60)
                    } else {
                        prioritized
                    }
                }
            }

            if (models.isEmpty()) {
                Result.failure(
                    IllegalStateException(
                        if (isEnglish) "Server returned an empty model list"
                        else "Сервер вернул пустой список моделей"
                    )
                )
            } else {
                Result.success(models)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapNetworkException(e, isEnglish))
        }
    }

    private fun prioritizeModels(fetched: List<String>, preferredOrder: List<String>): List<String> {
        val distinctFetched = fetched.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val preferredMap = preferredOrder.withIndex().associate { (idx, name) -> name.lowercase() to idx }
        val (knownPreferred, others) = distinctFetched.partition { preferredMap.containsKey(it.lowercase()) }
        val sortedPreferred = knownPreferred.sortedBy { preferredMap[it.lowercase()] ?: Int.MAX_VALUE }
        val sortedOthers = others.sorted()
        return (sortedPreferred + sortedOthers).distinct()
    }

    /**
     * Parses a two-line `Q: ...` / `A: ...` response from single-trip Gemini Audio.
     * Returns null if either question or answer is missing or if the response was truncated
     * after `Q:` (preventing `Q:` from ever being mistaken for `A:`).
     */
    internal fun parseSingleTripQa(rawText: String): Pair<String, String>? {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val qLineRaw = lines.firstOrNull { Q_PREFIX_REGEX.containsMatchIn(it) }
        val aLineRaw = lines.firstOrNull { A_PREFIX_REGEX.containsMatchIn(it) }

        val question = qLineRaw?.replaceFirst(Q_PREFIX_REGEX, "")?.trim()
        val explicitAnswer = aLineRaw?.replaceFirst(A_PREFIX_REGEX, "")?.trim()

        if (!question.isNullOrBlank() && !explicitAnswer.isNullOrBlank()) {
            return question to explicitAnswer
        }

        // If Q: was found but A: was not labeled, ONLY accept a subsequent line that is not Q:
        if (!question.isNullOrBlank() && lines.size >= 2) {
            val candidateAnswerLine = lines.last()
            if (candidateAnswerLine != qLineRaw && !Q_PREFIX_REGEX.containsMatchIn(candidateAnswerLine)) {
                return question to candidateAnswerLine
            }
        }

        return null
    }

    /**
     * Fast-path single-round-trip Audio -> Oracle Answer for Gemini when Gemini is the active provider
     * and STT is set to AUTO (with no separate Groq key) or GEMINI_AUDIO.
     */
    suspend fun processAudioAndAskOracle(
        wavBytes: ByteArray,
        settings: OracleSettings
    ): Result<AiOracleResult> = withContext(Dispatchers.IO) {
        val totalStart = System.currentTimeMillis()
        val groqKey = settings.getResolvedApiKey(AiProvider.GROQ)
        val geminiKey = settings.getResolvedApiKey(AiProvider.GEMINI)

        val allowGeminiSingleTrip = settings.selectedProvider == AiProvider.GEMINI &&
            geminiKey.isNotBlank() &&
            (settings.sttProvider == SttProvider.GEMINI_AUDIO ||
                (settings.sttProvider == SttProvider.AUTO && groqKey.isBlank()))

        if (allowGeminiSingleTrip) {
            try {
                val singleTrip = askGeminiAudioSingleTrip(wavBytes, geminiKey, settings, totalStart)
                if (singleTrip != null) {
                    return@withContext Result.success(singleTrip)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to 2-step transcribe + askOracle below
            }
        }

        val sttRes = transcribeAudioWav(wavBytes, settings)
        val recognizedQuestion = sttRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }.trim()

        if (recognizedQuestion.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (settings.isEnglish) "Speech not recognized. Try speaking louder."
                    else "Речь не распознана. Попробуйте сказать громче."
                )
            )
        }

        askOracle(recognizedQuestion, settings)
    }

    private suspend fun askGeminiAudioSingleTrip(
        wavBytes: ByteArray,
        geminiKey: String,
        settings: OracleSettings,
        startTimeMs: Long
    ): AiOracleResult? {
        val userModel = settings.getActiveModel(AiProvider.GEMINI)
            .removePrefix("models/")
            .trim()
            .ifBlank { AiProvider.GEMINI.defaultModel }

        if (userModel.startsWith("gemma", ignoreCase = true)) {
            return null
        }

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val effectivePrompt = settings.getEffectiveSystemPrompt()

        val promptText = if (settings.isEnglish) {
            "$effectivePrompt\n" +
                "Listen to the user's spoken question in this audio. " +
                "Output strictly in 2 lines:\n" +
                "Q: <transcribed question>\n" +
                "A: <answer following the instructions above>"
        } else {
            "$effectivePrompt\n" +
                "Прослушай голосовой вопрос пользователя в этом аудио. " +
                "Выведи ответ строго в 2 строки:\n" +
                "Q: <распознанный текст вопроса>\n" +
                "A: <ответ согласно инструкции выше>"
        }

        val url = "${settings.getBaseUrl(AiProvider.GEMINI)}/models/$userModel:generateContent"
        for (includeThinkingConfig in listOf(true, false)) {
            coroutineContext.ensureActive()
            try {
                val payload = buildJsonObject {
                    putJsonArray("contents") {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                putJsonArray("parts") {
                                    add(buildJsonObject { put("text", promptText) })
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", "audio/wav")
                                                put("data", base64Audio)
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    }
                    putGeminiGenerationConfig(
                        model = userModel,
                        baseMaxTokens = 300,
                        temperature = 0.1,
                        includeThinkingConfig = includeThinkingConfig
                    )
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", geminiKey.trim())
                    .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
                    .build()

                val (code, bodyString) = executeWithTransientRetry(request)
                if (code !in 200..299) {
                    if (code == 400 && includeThinkingConfig) {
                        continue
                    }
                    return null
                }
                val root = json.parseToJsonElement(bodyString).jsonObject
                val rawText = extractGeminiResponseText(root)
                val qa = parseSingleTripQa(rawText) ?: if (includeThinkingConfig) continue else return null

                val cleanQ = filterSttHallucinations(qa.first)
                if (cleanQ.isBlank()) return null
                val cleanA = sanitizeUltraShortAnswer(qa.second, settings.maxWordsLimit, settings.isEnglish)
                if (cleanA.isBlank() || cleanA == "?") return null

                val latency = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                return AiOracleResult(
                    question = cleanQ,
                    answer = cleanA,
                    rawAnswer = qa.second,
                    provider = AiProvider.GEMINI,
                    model = userModel,
                    latencyMs = latency
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Transcribes 16kHz mono WAV audio recorded by `LockScreenAudioRecorder`.
     * Respects `settings.sttProvider` and `settings.getWhisperLanguageIso()` (including auto-detection).
     */
    suspend fun transcribeAudioWav(
        wavBytes: ByteArray,
        settings: OracleSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        val groqKey = settings.getResolvedApiKey(AiProvider.GROQ)
        val openAiKey = settings.getResolvedApiKey(AiProvider.OPENAI)
        val geminiKey = settings.getResolvedApiKey(AiProvider.GEMINI)
        val openRouterKey = settings.getResolvedApiKey(AiProvider.OPENROUTER)
        val customKey = settings.getResolvedApiKey(AiProvider.CUSTOM_OPENAI)
        val langIso = settings.getWhisperLanguageIso()
        val isEn = settings.isEnglish

        val providerOrder: List<AiProvider> = when (settings.sttProvider) {
            SttProvider.GROQ_WHISPER -> listOf(AiProvider.GROQ)
            SttProvider.GEMINI_AUDIO -> listOf(AiProvider.GEMINI)
            SttProvider.OPENAI_WHISPER -> listOf(AiProvider.OPENAI)
            SttProvider.OPENROUTER_AUDIO -> listOf(AiProvider.OPENROUTER)
            SttProvider.CUSTOM_WHISPER -> listOf(AiProvider.CUSTOM_OPENAI)
            SttProvider.AUTO -> linkedSetOf<AiProvider>().apply {
                if (settings.selectedProvider != AiProvider.DEEPSEEK) {
                    add(settings.selectedProvider)
                }
                if (groqKey.isNotBlank()) add(AiProvider.GROQ)
                if (geminiKey.isNotBlank()) add(AiProvider.GEMINI)
                if (openAiKey.isNotBlank()) add(AiProvider.OPENAI)
                if (openRouterKey.isNotBlank()) add(AiProvider.OPENROUTER)
                if (customKey.isNotBlank()) add(AiProvider.CUSTOM_OPENAI)
            }.toList()
        }

        val errors = mutableListOf<String>()

        for (provider in providerOrder) {
            coroutineContext.ensureActive()
            when (provider) {
                AiProvider.GROQ -> {
                    if (groqKey.isBlank()) {
                        if (settings.sttProvider == SttProvider.GROQ_WHISPER) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    if (isEn) "Enter a Groq API key in Settings for Groq Whisper STT"
                                    else "Укажите API-ключ Groq в настройках для распознавания через Groq Whisper"
                                )
                            )
                        }
                        continue
                    }
                    try {
                        val transcript = transcribeViaOpenAiWhisperEndpoint(
                            providerName = "Groq STT",
                            wavBytes = wavBytes,
                            endpointUrl = "https://api.groq.com/openai/v1/audio/transcriptions",
                            apiKey = groqKey,
                            models = listOf("whisper-large-v3-turbo", "whisper-large-v3"),
                            langIso = langIso,
                            isEnglish = isEn
                        )
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add(e.message ?: "Groq STT error")
                    }
                }

                AiProvider.OPENAI -> {
                    if (openAiKey.isBlank()) {
                        if (settings.sttProvider == SttProvider.OPENAI_WHISPER) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    if (isEn) "Enter an OpenAI API key in Settings for OpenAI Whisper STT"
                                    else "Укажите API-ключ OpenAI в настройках для распознавания через OpenAI Whisper"
                                )
                            )
                        }
                        continue
                    }
                    try {
                        val transcript = transcribeViaOpenAiWhisperEndpoint(
                            providerName = "OpenAI STT",
                            wavBytes = wavBytes,
                            endpointUrl = "https://api.openai.com/v1/audio/transcriptions",
                            apiKey = openAiKey,
                            models = listOf("gpt-4o-mini-transcribe", "whisper-1"),
                            langIso = langIso,
                            isEnglish = isEn
                        )
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add(e.message ?: "OpenAI STT error")
                    }
                }

                AiProvider.GEMINI -> {
                    if (geminiKey.isBlank()) {
                        if (settings.sttProvider == SttProvider.GEMINI_AUDIO) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    if (isEn) "Enter a Gemini API key in Settings for Gemini Audio STT"
                                    else "Укажите API-ключ Gemini в настройках для распознавания речи"
                                )
                            )
                        }
                        continue
                    }
                    try {
                        val transcript = transcribeViaGeminiAudio(wavBytes, geminiKey, settings)
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add(e.message ?: "Gemini STT error")
                    }
                }

                AiProvider.OPENROUTER -> {
                    if (openRouterKey.isBlank()) {
                        if (settings.sttProvider == SttProvider.OPENROUTER_AUDIO) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    if (isEn) "Enter an OpenRouter API key in Settings for OpenRouter Audio STT"
                                    else "Укажите API-ключ OpenRouter в настройках для распознавания речи"
                                )
                            )
                        }
                        continue
                    }
                    try {
                        val transcript = transcribeViaOpenRouterAudio(wavBytes, openRouterKey, isEn)
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add(e.message ?: "OpenRouter STT error")
                    }
                }

                AiProvider.CUSTOM_OPENAI -> {
                    if (customKey.isBlank()) {
                        if (settings.sttProvider == SttProvider.CUSTOM_WHISPER) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    if (isEn) "Enter an API key for Custom OpenAI-Compatible STT"
                                    else "Укажите API-ключ для совместимого сервера STT"
                                )
                            )
                        }
                        continue
                    }
                    try {
                        val base = settings.getBaseUrl(AiProvider.CUSTOM_OPENAI)
                        val transcript = transcribeViaOpenAiWhisperEndpoint(
                            providerName = "Custom STT",
                            wavBytes = wavBytes,
                            endpointUrl = "$base/audio/transcriptions",
                            apiKey = customKey,
                            models = listOf("whisper-large-v3-turbo", "whisper-1"),
                            langIso = langIso,
                            isEnglish = isEn
                        )
                        if (transcript.isNotBlank()) return@withContext Result.success(transcript)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors.add(e.message ?: "Custom STT error")
                    }
                }

                AiProvider.DEEPSEEK -> {
                    // DeepSeek does not expose an audio/transcriptions endpoint
                }
            }
        }

        val errSummary = if (errors.isNotEmpty()) {
            errors.joinToString("; ")
        } else {
            if (isEn) {
                "Configure an API key for speech recognition (Groq / Gemini / OpenAI / OpenRouter) in Settings"
            } else {
                "Для распознавания голоса укажите API-ключ (Groq / Gemini / OpenAI / OpenRouter) в настройках"
            }
        }
        Result.failure(IllegalStateException(errSummary))
    }

    private suspend fun transcribeViaOpenAiWhisperEndpoint(
        providerName: String,
        wavBytes: ByteArray,
        endpointUrl: String,
        apiKey: String,
        models: List<String>,
        langIso: String?,
        isEnglish: Boolean
    ): String {
        var lastError: Exception? = null

        for (whisperModel in models) {
            coroutineContext.ensureActive()
            try {
                val multipartBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "question.wav",
                        wavBytes.toRequestBody(wavMediaType)
                    )
                    .addFormDataPart("model", whisperModel)
                    .addFormDataPart("temperature", "0")
                    .addFormDataPart("response_format", "json")

                if (!langIso.isNullOrBlank()) {
                    multipartBuilder.addFormDataPart("language", langIso)
                }

                val request = Request.Builder()
                    .url(endpointUrl)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(multipartBuilder.build())
                    .build()

                val (code, bodyString) = executeWithTransientRetry(request)
                if (code !in 200..299) {
                    val err = IllegalStateException(
                        formatHttpError(providerName, whisperModel, code, bodyString, isEnglish)
                    )
                    if (isModelNotFoundError(code, bodyString)) {
                        lastError = err
                        continue
                    }
                    throw err
                }
                val obj = json.parseToJsonElement(bodyString).jsonObject
                val rawText = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                val cleaned = filterSttHallucinations(rawText)
                if (cleaned.isNotBlank()) return cleaned
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                break
            }
        }
        if (lastError != null) throw mapNetworkException(lastError, isEnglish)
        return ""
    }

    private suspend fun transcribeViaGeminiAudio(
        wavBytes: ByteArray,
        geminiKey: String,
        settings: OracleSettings
    ): String {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val userModel = settings.getActiveModel(AiProvider.GEMINI)
            .removePrefix("models/")
            .trim()
        val candidateModels = linkedSetOf<String>().apply {
            if (userModel.isNotBlank() && !userModel.startsWith("gemma", ignoreCase = true)) {
                add(userModel)
            }
            add("gemini-flash-latest")
            add("gemini-2.5-flash")
        }

        var lastError: Exception? = null
        for (model in candidateModels) {
            coroutineContext.ensureActive()
            try {
                val url = "${settings.getBaseUrl(AiProvider.GEMINI)}/models/$model:generateContent"

                val payload = buildJsonObject {
                    putJsonArray("contents") {
                        add(
                            buildJsonObject {
                                putJsonArray("parts") {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "text",
                                                "Transcribe the spoken question from this audio verbatim. Output ONLY the transcribed words without quotes. If there is only silence, output an empty string."
                                            )
                                        }
                                    )
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", "audio/wav")
                                                put("data", base64Audio)
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    }
                    putGeminiGenerationConfig(
                        model = model,
                        baseMaxTokens = 256,
                        temperature = 0.0,
                        includeThinkingConfig = true
                    )
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", geminiKey.trim())
                    .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
                    .build()

                val (code, bodyString) = executeWithTransientRetry(request)
                if (code !in 200..299) {
                    val err = IllegalStateException(
                        formatHttpError("Gemini STT", model, code, bodyString, settings.isEnglish)
                    )
                    if (isModelNotFoundError(code, bodyString)) {
                        lastError = err
                        continue
                    }
                    throw err
                }
                val root = json.parseToJsonElement(bodyString).jsonObject
                val rawText = extractGeminiResponseText(root).replace('\n', ' ')
                val cleaned = filterSttHallucinations(rawText)
                if (cleaned.isNotBlank()) return cleaned
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                break
            }
        }
        if (lastError != null) throw mapNetworkException(lastError, settings.isEnglish)
        return ""
    }

    private suspend fun transcribeViaOpenRouterAudio(
        wavBytes: ByteArray,
        openRouterKey: String,
        isEnglish: Boolean
    ): String {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("model", "google/gemini-2.5-flash")
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put(
                                        "text",
                                        "Transcribe this spoken question verbatim. Output ONLY the transcribed words, nothing else. If there is no speech, output nothing."
                                    )
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") {
                                        put("data", base64Audio)
                                        put("format", "wav")
                                    }
                                }
                            )
                        }
                    }
                )
            }
            put("temperature", 0.0)
            put("max_tokens", 200)
        }.toString()

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${openRouterKey.trim()}")
            .header("Accept", "application/json")
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
            .build()

        val (code, bodyString) = executeWithTransientRetry(request)
        if (code !in 200..299) {
            throw IllegalStateException(
                formatHttpError("OpenRouter STT", "google/gemini-2.5-flash", code, bodyString, isEnglish)
            )
        }
        val root = json.parseToJsonElement(bodyString).jsonObject
        val rawText = root["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
            ?.trim()
            .orEmpty()
        return filterSttHallucinations(rawText)
    }

    internal fun filterSttHallucinations(raw: String): String {
        val trimmed = raw.trim().trim('"', '\'', '«', '»')
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (STT_HALLUCINATIONS.any { lower.contains(it) }) {
            return ""
        }
        return trimmed
    }

    suspend fun askOracle(
        question: String,
        settings: OracleSettings
    ): Result<AiOracleResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val provider = settings.selectedProvider
        val requestedModel = settings.getActiveModel(provider)
        val apiKey = settings.getResolvedApiKey(provider)
        val baseUrl = settings.getBaseUrl(provider)
        val isEn = settings.isEnglish

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (isEn) {
                        "Missing API key for ${provider.displayName}. Enter your key in Settings."
                    } else {
                        "Не задан API-ключ для ${provider.displayName}. Введите свой ключ во вкладке Настройки."
                    }
                )
            )
        }

        val fullSystemInstruction = settings.getEffectiveSystemPrompt()

        try {
            val (rawText, usedModel) = when (provider) {
                AiProvider.GEMINI -> {
                    callGeminiWithFallback(
                        baseUrl = baseUrl,
                        requestedModel = requestedModel,
                        apiKey = apiKey,
                        systemInstruction = fullSystemInstruction,
                        question = question,
                        isEnglish = isEn
                    )
                }

                AiProvider.GROQ -> {
                    callGroqWithFallback(
                        requestedModel = requestedModel,
                        apiKey = apiKey,
                        systemInstruction = fullSystemInstruction,
                        question = question,
                        isEnglish = isEn
                    )
                }

                AiProvider.OPENROUTER,
                AiProvider.OPENAI,
                AiProvider.DEEPSEEK,
                AiProvider.CUSTOM_OPENAI -> {
                    callOpenCompatibleChatApi(
                        providerName = provider.displayName,
                        url = "$baseUrl/chat/completions",
                        apiKey = apiKey,
                        model = requestedModel,
                        systemInstruction = fullSystemInstruction,
                        question = question,
                        isOpenRouter = provider == AiProvider.OPENROUTER,
                        isEnglish = isEn
                    ) to requestedModel
                }
            }

            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val sanitized = sanitizeUltraShortAnswer(rawText, settings.maxWordsLimit, isEn)

            Result.success(
                AiOracleResult(
                    question = question,
                    answer = sanitized,
                    rawAnswer = rawText.trim(),
                    provider = provider,
                    model = usedModel,
                    latencyMs = latency
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapNetworkException(e, isEn))
        }
    }

    private suspend fun callGroqWithFallback(
        requestedModel: String,
        apiKey: String,
        systemInstruction: String,
        question: String,
        isEnglish: Boolean
    ): Pair<String, String> {
        val activeModel = requestedModel.trim().ifBlank { AiProvider.GROQ.defaultModel }
        val candidates = linkedSetOf(activeModel, AiProvider.GROQ.defaultModel)
        var lastError: Exception? = null

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            try {
                val reply = callOpenCompatibleChatApi(
                    providerName = AiProvider.GROQ.displayName,
                    url = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = candidate,
                    systemInstruction = systemInstruction,
                    question = question,
                    isOpenRouter = false,
                    isEnglish = isEnglish
                )
                return reply to candidate
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelNotFoundException) {
                lastError = e
                continue
            } catch (e: Exception) {
                throw e
            }
        }
        throw lastError ?: IllegalStateException("Groq API error")
    }

    private class ModelNotFoundException(message: String) : IllegalStateException(message)

    private suspend fun callOpenCompatibleChatApi(
        providerName: String,
        url: String,
        apiKey: String,
        model: String,
        systemInstruction: String,
        question: String,
        isOpenRouter: Boolean,
        isEnglish: Boolean
    ): String {
        val trimmedModel = model.trim()
        val lowerModel = trimmedModel.lowercase()
        val isOpenAiOSeries = lowerModel.startsWith("o1") ||
            lowerModel.startsWith("o3") ||
            lowerModel.startsWith("o4") ||
            lowerModel.contains("/o1") ||
            lowerModel.contains("/o3") ||
            lowerModel.contains("/o4")
        val usesMaxCompletionTokens = isOpenAiOSeries || lowerModel.startsWith("gpt-5")
        val isReasoningModel = usesMaxCompletionTokens ||
            lowerModel.contains("r1") ||
            lowerModel.contains("reasoner") ||
            lowerModel.contains("qwen3") ||
            lowerModel.contains("2.5-pro") ||
            lowerModel.contains("thinking")

        for (minimalParams in listOf(false, true)) {
            coroutineContext.ensureActive()
            val payload = buildJsonObject {
                put("model", trimmedModel)
                putJsonArray("messages") {
                    if (minimalParams && isOpenAiOSeries) {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "$systemInstruction\n\n$question")
                            }
                        )
                    } else {
                        val instructionRole = if (isOpenAiOSeries && !isOpenRouter) "developer" else "system"
                        add(
                            buildJsonObject {
                                put("role", instructionRole)
                                put("content", systemInstruction)
                            }
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", question)
                            }
                        )
                    }
                }
                if (!minimalParams) {
                    if (usesMaxCompletionTokens) {
                        put("max_completion_tokens", 300)
                    } else if (!isReasoningModel) {
                        put("temperature", 0.1)
                        put("max_tokens", 160)
                    } else {
                        put("max_tokens", 350)
                    }
                }
            }.toString()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")

            if (isOpenRouter) {
                requestBuilder
                    .header("HTTP-Referer", "https://nothing.tech/glyph-oracle")
                    .header("X-Title", "Glyph Oracle Nothing Phone 3")
            }

            val request = requestBuilder
                .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
                .build()

            val (code, bodyString) = executeWithTransientRetry(request)
            if (code !in 200..299) {
                if (isModelNotFoundError(code, bodyString)) {
                    throw ModelNotFoundException(
                        formatHttpError(providerName, trimmedModel, code, bodyString, isEnglish)
                    )
                }
                val lowerBody = bodyString.lowercase()
                val isUnsupportedParamError = code == 400 && !minimalParams && (
                    lowerBody.contains("max_tokens") ||
                        lowerBody.contains("max_completion_tokens") ||
                        lowerBody.contains("temperature") ||
                        lowerBody.contains("role") ||
                        lowerBody.contains("developer") ||
                        lowerBody.contains("system") ||
                        lowerBody.contains("unsupported")
                    )
                if (isUnsupportedParamError) {
                    continue
                }
                throw IllegalStateException(
                    formatHttpError(providerName, trimmedModel, code, bodyString, isEnglish)
                )
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val msgObj = root["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject

            val content = msgObj?.get("content")?.jsonPrimitive?.content

            if (content.isNullOrBlank()) {
                throw IllegalStateException(
                    if (isEnglish) "Empty response from $providerName ($trimmedModel)"
                    else "Пустой ответ от $providerName ($trimmedModel)"
                )
            }
            return content
        }
        throw IllegalStateException("Failed to call $providerName ($trimmedModel)")
    }

    private suspend fun callGeminiWithFallback(
        baseUrl: String,
        requestedModel: String,
        apiKey: String,
        systemInstruction: String,
        question: String,
        isEnglish: Boolean
    ): Pair<String, String> {
        val cleanRequested = requestedModel.trim()
            .removePrefix("models/")
            .ifBlank { AiProvider.GEMINI.defaultModel }

        val candidates = linkedSetOf(
            cleanRequested,
            "gemini-flash-latest",
            "gemini-2.5-flash"
        )
        var lastError: Exception? = null

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            try {
                val reply = callGeminiRestApi(
                    baseUrl = baseUrl,
                    model = candidate,
                    apiKey = apiKey,
                    systemInstruction = systemInstruction,
                    question = question,
                    isEnglish = isEnglish
                )
                return reply to candidate
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelNotFoundException) {
                lastError = e
                continue
            } catch (e: Exception) {
                throw e
            }
        }
        throw lastError ?: IllegalStateException("Gemini API error")
    }

    private suspend fun callGeminiRestApi(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemInstruction: String,
        question: String,
        isEnglish: Boolean
    ): String {
        val cleanModel = model.trim()
            .removePrefix("models/")
            .ifBlank { AiProvider.GEMINI.defaultModel }
        val url = "${baseUrl.trimEnd('/')}/models/$cleanModel:generateContent"
        val isGemmaModel = cleanModel.startsWith("gemma", ignoreCase = true)

        var lastException: Exception? = null
        val thinkingModes = if (geminiUsesThinkingLevel(cleanModel) || geminiSupportsThinkingBudgetZero(cleanModel)) {
            listOf(true, false)
        } else {
            listOf(false)
        }

        var tokenBudget = 256

        for (includeThinkingConfig in thinkingModes) {
            coroutineContext.ensureActive()
            try {
                val payload = buildJsonObject {
                    if (!isGemmaModel) {
                        putJsonObject("systemInstruction") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", systemInstruction) })
                            }
                        }
                    }
                    putJsonArray("contents") {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                putJsonArray("parts") {
                                    val promptText = if (isGemmaModel) {
                                        "$systemInstruction\n\nQuestion: $question"
                                    } else {
                                        question
                                    }
                                    add(buildJsonObject { put("text", promptText) })
                                }
                            }
                        )
                    }
                    putGeminiGenerationConfig(
                        model = cleanModel,
                        baseMaxTokens = tokenBudget,
                        temperature = 0.1,
                        includeThinkingConfig = includeThinkingConfig
                    )
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey.trim())
                    .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(jsonMediaType))
                    .build()

                val (code, bodyString) = executeWithTransientRetry(request)
                if (code !in 200..299) {
                    if (isModelNotFoundError(code, bodyString)) {
                        throw ModelNotFoundException(
                            formatHttpError("Gemini", cleanModel, code, bodyString, isEnglish)
                        )
                    }
                    if (code == 400 && includeThinkingConfig) {
                        lastException = IllegalStateException(
                            formatHttpError("Gemini", cleanModel, code, bodyString, isEnglish)
                        )
                        continue
                    }
                    throw IllegalStateException(
                        formatHttpError("Gemini", cleanModel, code, bodyString, isEnglish)
                    )
                }

                val root = json.parseToJsonElement(bodyString).jsonObject
                val candidateObj = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val finishReason = candidateObj?.get("finishReason")?.jsonPrimitive?.content.orEmpty()
                val thoughtsTokens = root["usageMetadata"]?.jsonObject
                    ?.get("thoughtsTokenCount")?.jsonPrimitive?.intOrNull ?: 0

                if (finishReason == "SAFETY" || finishReason == "RECITATION" || finishReason == "PROHIBITED_CONTENT") {
                    throw IllegalStateException(
                        if (isEnglish) "Response blocked by Gemini safety filter ($finishReason)"
                        else "Ответ заблокирован фильтром безопасности Gemini ($finishReason)"
                    )
                }

                val text = extractGeminiResponseText(root).replace('\n', ' ').trim()

                if (text.isBlank()) {
                    if (finishReason == "MAX_TOKENS" || thoughtsTokens > 0) {
                        tokenBudget = 1024
                    }
                    if (includeThinkingConfig) {
                        lastException = IllegalStateException(
                            if (isEnglish) "Empty Gemini response ($cleanModel, finishReason=$finishReason)"
                            else "Пустой ответ Gemini ($cleanModel, причина=$finishReason)"
                        )
                        continue
                    }
                    throw IllegalStateException(
                        if (isEnglish) "Empty Gemini response ($cleanModel, finishReason=$finishReason)"
                        else "Пустой ответ Gemini ($cleanModel, причина=$finishReason)"
                    )
                }
                return text
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelNotFoundException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (!includeThinkingConfig) throw e
            }
        }

        throw lastException ?: IllegalStateException("Gemini API error ($cleanModel)")
    }

    private fun geminiUsesThinkingLevel(model: String): Boolean {
        val lower = model.lowercase()
        return lower.startsWith("gemini-3") || lower.contains("gemini-3.")
    }

    private fun geminiSupportsThinkingBudgetZero(model: String): Boolean {
        val lower = model.lowercase()
        return (lower.contains("2.5") && lower.contains("flash")) ||
            lower.contains("3.5-flash") ||
            lower == "gemini-flash-latest" ||
            lower == "gemini-flash-lite-latest"
    }

    private fun JsonObjectBuilder.putGeminiGenerationConfig(
        model: String,
        baseMaxTokens: Int,
        temperature: Double = 0.1,
        includeThinkingConfig: Boolean = true
    ) {
        val isProOrThinking = model.contains("pro", ignoreCase = true) ||
            model.contains("thinking", ignoreCase = true) ||
            geminiUsesThinkingLevel(model)

        putJsonObject("generationConfig") {
            if (includeThinkingConfig && geminiUsesThinkingLevel(model)) {
                put("maxOutputTokens", if (isProOrThinking) 1024 else maxOf(baseMaxTokens, 300))
                putJsonObject("thinkingConfig") {
                    put("thinkingLevel", "low")
                }
            } else if (isProOrThinking) {
                put("temperature", temperature)
                put("maxOutputTokens", 1024)
            } else {
                put("temperature", temperature)
                put("maxOutputTokens", maxOf(baseMaxTokens, 256))
                if (includeThinkingConfig && geminiSupportsThinkingBudgetZero(model)) {
                    putJsonObject("thinkingConfig") {
                        put("thinkingBudget", 0)
                    }
                }
            }
        }
    }

    /**
     * Extracts only non-thought text parts from a Gemini response so internal reasoning
     * (`"thought": true`) from Gemini 2.5 / 3.x never overwrites the short answer.
     */
    internal fun extractGeminiResponseText(root: JsonObject): String {
        val parts = root["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?: return ""

        val nonThoughtTexts = parts.mapNotNull { partEl ->
            val obj = partEl.jsonObject
            val isThought = obj["thought"]?.jsonPrimitive?.booleanOrNull == true
            if (isThought) null else obj["text"]?.jsonPrimitive?.content
        }.map { it.trim() }.filter { it.isNotEmpty() }

        return nonThoughtTexts.joinToString("\n").trim()
    }

    private fun extractApiErrorMessage(errorBody: String): String {
        if (errorBody.isBlank()) return ""
        return try {
            val obj = json.parseToJsonElement(errorBody).jsonObject
            val errorElement = obj["error"]
            when {
                errorElement != null -> {
                    val errObj = runCatching { errorElement.jsonObject }.getOrNull()
                    errObj?.get("message")?.jsonPrimitive?.content
                        ?: runCatching { errorElement.jsonPrimitive.content }.getOrNull()
                        ?: errorBody.take(180)
                }
                else -> errorBody.take(180)
            }
        } catch (_: Exception) {
            errorBody.take(180)
        }
    }

    fun sanitizeUltraShortAnswer(
        raw: String,
        maxWords: Int,
        isEnglish: Boolean = false
    ): String {
        val withoutClosedThink = raw.replace(CLOSED_THINK_REGEX, "")
        val withoutThink = withoutClosedThink.replace(UNCLOSED_THINK_REGEX, "").trim()

        val nonEmptyLines = withoutThink
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("Thinking Process:", ignoreCase = true) }

        val labeledLine = nonEmptyLines.lastOrNull { ANSWER_PREFIX_REGEX.containsMatchIn(it) }
            ?.replaceFirst(ANSWER_PREFIX_REGEX, "")

        val chosenLine = when {
            !labeledLine.isNullOrBlank() -> labeledLine
            nonEmptyLines.size > 1 -> {
                val last = nonEmptyLines.last()
                val first = nonEmptyLines.first()
                if (last.split(WHITESPACE_REGEX).size <= maxWords + 2) last else first
            }
            else -> nonEmptyLines.firstOrNull().orEmpty()
        }

        val cleaned = chosenLine
            .replace("*", "")
            .replace("\"", "")
            .replace("«", "")
            .replace("»", "")
            .replace("`", "")
            .replace("’", "'")
            .trim()
            .trim('\'', '.', '!', ',', ';', ':')
            .trim()

        if (cleaned.isEmpty()) return "?"

        val words = cleaned.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        val limited = words.take(maxWords.coerceIn(1, 5)).joinToString(" ")
        return limited.uppercase(if (isEnglish) java.util.Locale.US else java.util.Locale.getDefault())
    }
}
