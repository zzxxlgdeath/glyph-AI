package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AiProvider(
    val displayName: String,
    val endpointLabel: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val presetModels: List<String>,
    val isOpenAiCompatible: Boolean
) {
    GROQ(
        displayName = "Groq",
        endpointLabel = "api.groq.com/openai/v1",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.1-8b-instant",
        presetModels = listOf(
            "llama-3.1-8b-instant",
            "llama-3.3-70b-versatile"
        ),
        isOpenAiCompatible = true
    ),
    GEMINI(
        displayName = "Gemini",
        endpointLabel = "generativelanguage.googleapis.com",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-flash-latest",
        presetModels = listOf(
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro"
        ),
        isOpenAiCompatible = false
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        endpointLabel = "openrouter.ai/api/v1",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        presetModels = listOf(
            "openai/gpt-4o-mini",
            "google/gemini-2.5-flash",
            "anthropic/claude-3.5-haiku",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-chat"
        ),
        isOpenAiCompatible = true
    ),
    OPENAI(
        displayName = "OpenAI",
        endpointLabel = "api.openai.com/v1",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4.1-mini",
        presetModels = listOf(
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4o-mini",
            "gpt-4o"
        ),
        isOpenAiCompatible = true
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        endpointLabel = "api.deepseek.com/v1",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        presetModels = listOf(
            "deepseek-chat",
            "deepseek-reasoner"
        ),
        isOpenAiCompatible = true
    ),
    CUSTOM_OPENAI(
        displayName = "OpenAI-Совм.",
        endpointLabel = "Custom /v1 Endpoint",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-small-latest",
        presetModels = listOf(
            "mistral-small-latest",
            "mistral-large-latest",
            "grok-3-mini",
            "llama-3.3-70b",
            "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "gpt-4.1-mini"
        ),
        isOpenAiCompatible = true
    )
}

enum class SttProvider(
    val labelRu: String,
    val labelEn: String
) {
    AUTO("Авто", "Auto"),
    GROQ_WHISPER("Groq Whisper", "Groq Whisper"),
    GEMINI_AUDIO("Gemini Audio", "Gemini Audio"),
    OPENAI_WHISPER("OpenAI Whisper", "OpenAI Whisper"),
    OPENROUTER_AUDIO("OpenRouter Audio", "OpenRouter Audio"),
    CUSTOM_WHISPER("OpenAI-Совм. STT", "Custom STT");

    fun getLabel(isEnglish: Boolean): String = if (isEnglish) labelEn else labelRu
}

private val EXCLUDED_MODEL_KEYWORDS = listOf(
    "gemma",
    "gemini-1.",
    "gemini-2.0",
    "gemini-exp",
    "gemini-3.1-flash-lite-preview",
    "llama-4-maverick",
    "llama-4-scout",
    "qwen3-32b",
    "image",
    "imagen",
    "vision",
    "-vl",
    "llava",
    "pixtral",
    "flux",
    "sdxl",
    "stable-diffusion",
    "dall-e",
    "sora",
    "veo",
    "lyria",
    "whisper",
    "guard",
    "prompt-guard",
    "tts",
    "playai",
    "orpheus",
    "embedding",
    "moderation",
    "babbage",
    "davinci",
    "curie",
    "ada",
    "realtime",
    "transcribe",
    "audio",
    "live",
    "search",
    "computer-use",
    "robotics",
    "rerank",
    "canary",
    "aqa",
    "learnlm",
    "thinking-exp",
    "customtools",
    "gpt-3.5",
    "deepseek-r1-distill",
    "llama3-70b-8192",
    "llama3-8b-8192",
    "mixtral-8x7b",
    "llama-3.2-",
    "qwen-qwq",
    "mistral-saba",
    "allam",
    "compound"
)

fun isActiveSupportedTextModel(rawId: String, provider: AiProvider): Boolean {
    val id = rawId.trim().removePrefix("models/")
    if (id.isEmpty()) return false
    val lower = id.lowercase(Locale.ROOT)

    if (EXCLUDED_MODEL_KEYWORDS.any { lower.contains(it) }) return false

    return when (provider) {
        AiProvider.GEMINI -> {
            val isLatestAlias = lower == "gemini-flash-latest" ||
                lower == "gemini-flash-lite-latest" ||
                lower == "gemini-pro-latest"
            val isGemini25 = lower.startsWith("gemini-2.5-") &&
                !lower.contains("-preview") &&
                !lower.contains("-exp") &&
                !lower.endsWith("-001")
            val isGemini3Or35 = (lower.startsWith("gemini-3") || lower.startsWith("gemini-3.5")) &&
                !lower.contains("-exp") &&
                !lower.endsWith("-001")
            isLatestAlias || isGemini25 || isGemini3Or35
        }
        AiProvider.OPENAI -> {
            lower.startsWith("gpt-4") ||
                lower.startsWith("gpt-5") ||
                lower.startsWith("o1") ||
                lower.startsWith("o3") ||
                lower.startsWith("o4") ||
                lower.startsWith("chatgpt-")
        }
        else -> true
    }
}

enum class VoiceCaptureMode(
    val titleRu: String,
    val subtitleRu: String,
    val titleEn: String,
    val subtitleEn: String
) {
    AUTO_HYBRID(
        titleRu = "Авто (Гибридный режим)",
        subtitleRu = "В приложении — системный распознаватель, в фоне и на заблокированном экране — прямая запись AudioRecord",
        titleEn = "Auto (Hybrid Mode)",
        subtitleEn = "Uses system recognizer in-app, and direct AudioRecord in background or on lock screen"
    ),
    ALWAYS_AUDIORECORD(
        titleRu = "Всегда прямая запись (AudioRecord)",
        subtitleRu = "Запись напрямую с микрофона и распознавание через выбранный STT провайдер",
        titleEn = "Always Direct Mic (AudioRecord)",
        subtitleEn = "Direct microphone capture transcribed via selected STT provider"
    );

    fun getTitle(isEnglish: Boolean): String = if (isEnglish) titleEn else titleRu
    fun getSubtitle(isEnglish: Boolean): String = if (isEnglish) subtitleEn else subtitleRu
}

data class OpenAiEndpointPreset(
    val name: String,
    val baseUrl: String,
    val sampleModel: String
)

val POPULAR_OPENAI_COMPATIBLE_ENDPOINTS = listOf(
    OpenAiEndpointPreset("Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest"),
    OpenAiEndpointPreset("xAI Grok", "https://api.x.ai/v1", "grok-3-mini"),
    OpenAiEndpointPreset("Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    OpenAiEndpointPreset("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b"),
    OpenAiEndpointPreset("Fireworks", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct")
)

fun resolveSystemUiLanguage(): String {
    val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
    return if (sysLang.startsWith("ru")) "ru" else "en"
}

fun resolveSystemSpeechLanguage(): String {
    val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
    return if (sysLang.startsWith("ru")) "ru-RU" else "en-US"
}

data class OracleSettings(
    val selectedProvider: AiProvider = AiProvider.GROQ,
    val sttProvider: SttProvider = SttProvider.AUTO,
    val groqModel: String = AiProvider.GROQ.defaultModel,
    val geminiModel: String = AiProvider.GEMINI.defaultModel,
    val openRouterModel: String = AiProvider.OPENROUTER.defaultModel,
    val openAiModel: String = AiProvider.OPENAI.defaultModel,
    val deepSeekModel: String = AiProvider.DEEPSEEK.defaultModel,
    val customOpenAiModel: String = AiProvider.CUSTOM_OPENAI.defaultModel,
    val customOpenAiBaseUrl: String = AiProvider.CUSTOM_OPENAI.defaultBaseUrl,
    val customGroqModels: List<String> = emptyList(),
    val customGeminiModels: List<String> = emptyList(),
    val customOpenRouterModels: List<String> = emptyList(),
    val customOpenAiModels: List<String> = emptyList(),
    val customDeepSeekModels: List<String> = emptyList(),
    val customCompatibleModels: List<String> = emptyList(),
    val fetchedGroqModels: List<String> = emptyList(),
    val fetchedGeminiModels: List<String> = emptyList(),
    val fetchedOpenRouterModels: List<String> = emptyList(),
    val fetchedOpenAiModels: List<String> = emptyList(),
    val fetchedDeepSeekModels: List<String> = emptyList(),
    val fetchedCompatibleModels: List<String> = emptyList(),
    val customGroqKey: String = "",
    val customGeminiKey: String = "",
    val customOpenRouterKey: String = "",
    val customOpenAiKey: String = "",
    val customDeepSeekKey: String = "",
    val customCompatibleKey: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT_RU,
    val maxWordsLimit: Int = 2,
    val matrixBrightness: Int = 255,
    val scrollSpeedMs: Int = 75,
    val silenceTimeoutMs: Int = 1050,
    val uiLanguage: String = resolveSystemUiLanguage(),
    val speechLanguage: String = "auto",
    val voiceCaptureMode: VoiceCaptureMode = VoiceCaptureMode.AUTO_HYBRID,
    val backgroundServiceEnabled: Boolean = false
) {
    val isEnglish: Boolean
        get() = uiLanguage.startsWith("en", ignoreCase = true)

    val hasActiveApiKey: Boolean
        get() = getResolvedApiKey(selectedProvider).isNotBlank()

    fun getEffectiveSystemPrompt(): String {
        val raw = systemPrompt.trim()
        if (raw.isNotEmpty()) return raw
        return if (isEnglish) DEFAULT_SYSTEM_PROMPT_EN else DEFAULT_SYSTEM_PROMPT_RU
    }

    /**
     * Returns the ISO-639-1 language code ("ru" or "en") for Whisper STT, or null if set to "auto"
     * so Whisper automatically detects the spoken language.
     */
    fun getWhisperLanguageIso(): String? = when {
        speechLanguage.equals("auto", ignoreCase = true) -> null
        speechLanguage.startsWith("en", ignoreCase = true) -> "en"
        speechLanguage.startsWith("ru", ignoreCase = true) -> "ru"
        else -> null
    }

    /**
     * Returns the BCP-47 locale tag for Android's on-device SpeechRecognizer, or null if "auto"
     * so the system recognizer uses the user's default voice locale.
     */
    fun getRecognizerLocaleTag(): String? = when {
        speechLanguage.equals("auto", ignoreCase = true) -> null
        else -> speechLanguage
    }

    fun getRawModelInput(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.GROQ -> groqModel
        AiProvider.GEMINI -> geminiModel
        AiProvider.OPENROUTER -> openRouterModel
        AiProvider.OPENAI -> openAiModel
        AiProvider.DEEPSEEK -> deepSeekModel
        AiProvider.CUSTOM_OPENAI -> customOpenAiModel
    }

    fun getActiveModel(provider: AiProvider = selectedProvider): String {
        val raw = when (provider) {
            AiProvider.GROQ -> groqModel.trim()
            AiProvider.GEMINI -> geminiModel.trim().removePrefix("models/")
            AiProvider.OPENROUTER -> openRouterModel.trim()
            AiProvider.OPENAI -> openAiModel.trim()
            AiProvider.DEEPSEEK -> deepSeekModel.trim()
            AiProvider.CUSTOM_OPENAI -> customOpenAiModel.trim()
        }
        if (raw.isEmpty()) return provider.defaultModel
        val isCustomUserModel = getCustomModelsForProvider(provider).any { it.equals(raw, ignoreCase = true) }
        return if (isCustomUserModel || isActiveSupportedTextModel(raw, provider)) {
            raw
        } else {
            provider.defaultModel
        }
    }

    fun getBaseUrl(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.CUSTOM_OPENAI -> customOpenAiBaseUrl.trim()
            .trimEnd('/')
            .ifBlank { AiProvider.CUSTOM_OPENAI.defaultBaseUrl }
        else -> provider.defaultBaseUrl
    }

    fun getCustomModelsForProvider(provider: AiProvider = selectedProvider): List<String> = when (provider) {
        AiProvider.GROQ -> customGroqModels
        AiProvider.GEMINI -> customGeminiModels
        AiProvider.OPENROUTER -> customOpenRouterModels
        AiProvider.OPENAI -> customOpenAiModels
        AiProvider.DEEPSEEK -> customDeepSeekModels
        AiProvider.CUSTOM_OPENAI -> customCompatibleModels
    }

    fun getFetchedModelsForProvider(provider: AiProvider = selectedProvider): List<String> = when (provider) {
        AiProvider.GROQ -> fetchedGroqModels
        AiProvider.GEMINI -> fetchedGeminiModels
        AiProvider.OPENROUTER -> fetchedOpenRouterModels
        AiProvider.OPENAI -> fetchedOpenAiModels
        AiProvider.DEEPSEEK -> fetchedDeepSeekModels
        AiProvider.CUSTOM_OPENAI -> fetchedCompatibleModels
    }.filter { isActiveSupportedTextModel(it, provider) }

    fun hasFetchedLiveModels(provider: AiProvider = selectedProvider): Boolean =
        getFetchedModelsForProvider(provider).isNotEmpty()

    /**
     * When live API models have been fetched for a provider, they replace the static preset list
     * while preserving any user-added custom models and the currently selected model.
     */
    fun getAllModelsForProvider(provider: AiProvider = selectedProvider): List<String> {
        val active = getActiveModel(provider)
        val custom = getCustomModelsForProvider(provider)
        val fetched = getFetchedModelsForProvider(provider)
        val baseList = if (fetched.isNotEmpty()) {
            fetched
        } else {
            provider.presetModels
        }
        return (custom + baseList + listOf(active)).filter { it.isNotBlank() }.distinct()
    }

    fun getRawApiKeyInput(provider: AiProvider = selectedProvider): String = when (provider) {
        AiProvider.GROQ -> customGroqKey
        AiProvider.GEMINI -> customGeminiKey
        AiProvider.OPENROUTER -> customOpenRouterKey
        AiProvider.OPENAI -> customOpenAiKey
        AiProvider.DEEPSEEK -> customDeepSeekKey
        AiProvider.CUSTOM_OPENAI -> customCompatibleKey
    }

    fun getResolvedApiKey(provider: AiProvider = selectedProvider): String {
        return cleanApiKeyString(getRawApiKeyInput(provider))
    }

    private fun cleanApiKeyString(raw: String): String {
        return raw.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .replace("\"", "")
            .replace("'", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT_RU =
            "Отвечай на любой вопрос максимально точно и ультра-кратко: 1–2 слова в ВЕРХНЕМ РЕГИСТРЕ без точки и пояснений.\n" +
                "• Да/нет — ДА или НЕТ\n" +
                "• Число или расчёт — только число (42, 100%)\n" +
                "• Выбор «А или Б» — только лучший вариант"

        const val DEFAULT_SYSTEM_PROMPT_EN =
            "Answer any question accurately and ultra-briefly: 1–2 UPPERCASE words with no period or explanation.\n" +
                "• Yes/no — YES or NO\n" +
                "• Number or math — only the number (42, 100%)\n" +
                "• Choice \"A or B\" — only the best option"

        const val DEFAULT_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT_RU
    }
}

/**
 * Hardware-backed Android Keystore AES/GCM encryptor for API keys stored in SharedPreferences.
 */
internal object ApiKeyCrypto {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "glyph_oracle_api_key_master_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE_BYTES = 12
    private const val PREFIX = "enc:v1:"

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
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
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        if (plainText.startsWith(PREFIX)) return plainText
        val secretKey = getOrCreateSecretKey() ?: return plainText
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plainText
        }
    }

    fun decrypt(storedValue: String?): String {
        val raw = storedValue.orEmpty().trim()
        if (raw.isEmpty()) return ""
        if (!raw.startsWith(PREFIX)) return raw
        val secretKey = getOrCreateSecretKey() ?: return ""
        return try {
            val encoded = raw.removePrefix(PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_SIZE_BYTES) return ""
            val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
            val cipherBytes = combined.copyOfRange(IV_SIZE_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }
}

class OracleSettingsRepository private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("glyph_oracle_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<OracleSettings> = _settings.asStateFlow()

    private fun sanitizeSavedModel(raw: String?, provider: AiProvider, customModels: List<String>): String {
        val cleaned = raw.orEmpty().trim().removePrefix("models/")
        if (cleaned.isEmpty()) return provider.defaultModel
        if (customModels.any { it.equals(cleaned, ignoreCase = true) }) return cleaned
        return if (isActiveSupportedTextModel(cleaned, provider)) cleaned else provider.defaultModel
    }

    private fun readAndMigrateApiKey(prefKey: String, migrationEditor: SharedPreferences.Editor): String {
        val stored = prefs.getString(prefKey, "") ?: ""
        if (stored.isBlank()) return ""
        val decrypted = ApiKeyCrypto.decrypt(stored)
        if (!stored.startsWith("enc:v1:") && decrypted.isNotBlank()) {
            val encrypted = ApiKeyCrypto.encrypt(decrypted)
            if (encrypted.startsWith("enc:v1:")) {
                migrationEditor.putString(prefKey, encrypted)
            }
        }
        return decrypted
    }

    private fun loadFromPrefs(): OracleSettings {
        val providerName = prefs.getString("selected_provider", AiProvider.GROQ.name) ?: AiProvider.GROQ.name
        val provider = runCatching { AiProvider.valueOf(providerName) }.getOrDefault(AiProvider.GROQ)

        val sttProviderName = prefs.getString("stt_provider", SttProvider.AUTO.name) ?: SttProvider.AUTO.name
        val sttProvider = runCatching { SttProvider.valueOf(sttProviderName) }.getOrDefault(SttProvider.AUTO)

        val modeName = prefs.getString("voice_capture_mode", VoiceCaptureMode.AUTO_HYBRID.name)
            ?: VoiceCaptureMode.AUTO_HYBRID.name
        val voiceMode = runCatching { VoiceCaptureMode.valueOf(modeName) }
            .getOrDefault(VoiceCaptureMode.AUTO_HYBRID)

        val customGroq = parseList(prefs.getString("custom_groq_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.GROQ) }
        val customGemini = parseList(prefs.getString("custom_gemini_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.GEMINI) }
        val customOpenRouter = parseList(prefs.getString("custom_openrouter_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.OPENROUTER) }
        val customOpenAi = parseList(prefs.getString("custom_openai_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.OPENAI) }
        val customDeepSeek = parseList(prefs.getString("custom_deepseek_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.DEEPSEEK) }
        val customCompatible = parseList(prefs.getString("custom_compatible_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.CUSTOM_OPENAI) }

        val validGroqModel = sanitizeSavedModel(prefs.getString("groq_model", null), AiProvider.GROQ, customGroq)
        val validGeminiModel = sanitizeSavedModel(prefs.getString("gemini_model", null), AiProvider.GEMINI, customGemini)
        val validOpenRouterModel = sanitizeSavedModel(prefs.getString("openrouter_model", null), AiProvider.OPENROUTER, customOpenRouter)
        val validOpenAiModel = sanitizeSavedModel(prefs.getString("openai_model", null), AiProvider.OPENAI, customOpenAi)
        val validDeepSeekModel = sanitizeSavedModel(prefs.getString("deepseek_model", null), AiProvider.DEEPSEEK, customDeepSeek)
        val validCustomOpenAiModel = sanitizeSavedModel(prefs.getString("custom_openai_model", null), AiProvider.CUSTOM_OPENAI, customCompatible)

        val migrationEditor = prefs.edit()

        val savedUiLang = prefs.getString("ui_language", null)
        val effectiveUiLang = if (savedUiLang.isNullOrBlank()) {
            val legacySpeech = prefs.getString("speech_language", null)
            val detected = when {
                legacySpeech != null && legacySpeech.startsWith("en", ignoreCase = true) -> "en"
                legacySpeech != null && legacySpeech.startsWith("ru", ignoreCase = true) -> "ru"
                else -> resolveSystemUiLanguage()
            }
            migrationEditor.putString("ui_language", detected)
            detected
        } else {
            savedUiLang
        }

        val savedSpeechLang = prefs.getString("speech_language", "auto") ?: "auto"

        val isEn = effectiveUiLang.startsWith("en", ignoreCase = true)
        val defaultPrompt = if (isEn) {
            OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
        } else {
            OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
        }

        val schemaVersion = prefs.getInt("prefs_schema_version", 1)
        val savedPrompt = prefs.getString("system_prompt", defaultPrompt) ?: defaultPrompt
        val isStandardDefaultPrompt = savedPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_RU ||
            savedPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
        val isLegacyBuiltInPrompt = schemaVersion < 2 && (
            savedPrompt.contains("{maxWords}", ignoreCase = true) ||
                savedPrompt.contains("светодиодной матрицы 25x25", ignoreCase = true) ||
                savedPrompt.contains("25x25 LED Glyph Matrix", ignoreCase = true)
            )

        val migratedPrompt = if (isStandardDefaultPrompt || isLegacyBuiltInPrompt) {
            if (savedPrompt != defaultPrompt) {
                migrationEditor.putString("system_prompt", defaultPrompt)
            }
            defaultPrompt
        } else {
            savedPrompt
        }
        if (schemaVersion < 2) {
            migrationEditor.putInt("prefs_schema_version", 2)
        }

        val groqKey = readAndMigrateApiKey("custom_groq_key", migrationEditor)
        val geminiKey = readAndMigrateApiKey("custom_gemini_key", migrationEditor)
        val openRouterKey = readAndMigrateApiKey("custom_openrouter_key", migrationEditor)
        val openAiKey = readAndMigrateApiKey("custom_openai_key", migrationEditor)
        val deepSeekKey = readAndMigrateApiKey("custom_deepseek_key", migrationEditor)
        val compatibleKey = readAndMigrateApiKey("custom_compatible_key", migrationEditor)
        migrationEditor.apply()

        return OracleSettings(
            selectedProvider = provider,
            sttProvider = sttProvider,
            groqModel = validGroqModel,
            geminiModel = validGeminiModel,
            openRouterModel = validOpenRouterModel,
            openAiModel = validOpenAiModel,
            deepSeekModel = validDeepSeekModel,
            customOpenAiModel = validCustomOpenAiModel,
            customOpenAiBaseUrl = prefs.getString("custom_openai_base_url", AiProvider.CUSTOM_OPENAI.defaultBaseUrl) ?: AiProvider.CUSTOM_OPENAI.defaultBaseUrl,
            customGroqModels = customGroq,
            customGeminiModels = customGemini,
            customOpenRouterModels = customOpenRouter,
            customOpenAiModels = customOpenAi,
            customDeepSeekModels = customDeepSeek,
            customCompatibleModels = customCompatible,
            fetchedGroqModels = parseList(prefs.getString("fetched_groq_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.GROQ) },
            fetchedGeminiModels = parseList(prefs.getString("fetched_gemini_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.GEMINI) },
            fetchedOpenRouterModels = parseList(prefs.getString("fetched_openrouter_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.OPENROUTER) },
            fetchedOpenAiModels = parseList(prefs.getString("fetched_openai_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.OPENAI) },
            fetchedDeepSeekModels = parseList(prefs.getString("fetched_deepseek_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.DEEPSEEK) },
            fetchedCompatibleModels = parseList(prefs.getString("fetched_compatible_models", "")).filter { isActiveSupportedTextModel(it, AiProvider.CUSTOM_OPENAI) },
            customGroqKey = groqKey,
            customGeminiKey = geminiKey,
            customOpenRouterKey = openRouterKey,
            customOpenAiKey = openAiKey,
            customDeepSeekKey = deepSeekKey,
            customCompatibleKey = compatibleKey,
            systemPrompt = migratedPrompt,
            maxWordsLimit = prefs.getInt("max_words_limit", 2).coerceIn(1, 5),
            matrixBrightness = prefs.getInt("matrix_brightness", 255).coerceIn(40, 255),
            scrollSpeedMs = prefs.getInt("scroll_speed_ms", 75).coerceIn(35, 160),
            silenceTimeoutMs = prefs.getInt("silence_timeout_ms", 1050).coerceIn(600, 2000),
            uiLanguage = effectiveUiLang,
            speechLanguage = savedSpeechLang,
            voiceCaptureMode = voiceMode,
            backgroundServiceEnabled = prefs.getBoolean("background_service_enabled", false)
        )
    }

    @Synchronized
    fun updateSettings(transform: (OracleSettings) -> OracleSettings) {
        var previous: OracleSettings = _settings.value
        var updated: OracleSettings = previous
        _settings.update { current ->
            previous = current
            updated = transform(current)
            updated
        }
        persistDiff(previous, updated)
    }

    private fun persistDiff(old: OracleSettings, updated: OracleSettings) {
        if (old == updated) return
        val editor = prefs.edit()
        if (old.selectedProvider != updated.selectedProvider) editor.putString("selected_provider", updated.selectedProvider.name)
        if (old.sttProvider != updated.sttProvider) editor.putString("stt_provider", updated.sttProvider.name)
        if (old.groqModel != updated.groqModel) editor.putString("groq_model", updated.groqModel)
        if (old.geminiModel != updated.geminiModel) editor.putString("gemini_model", updated.geminiModel)
        if (old.openRouterModel != updated.openRouterModel) editor.putString("openrouter_model", updated.openRouterModel)
        if (old.openAiModel != updated.openAiModel) editor.putString("openai_model", updated.openAiModel)
        if (old.deepSeekModel != updated.deepSeekModel) editor.putString("deepseek_model", updated.deepSeekModel)
        if (old.customOpenAiModel != updated.customOpenAiModel) editor.putString("custom_openai_model", updated.customOpenAiModel)
        if (old.customOpenAiBaseUrl != updated.customOpenAiBaseUrl) editor.putString("custom_openai_base_url", updated.customOpenAiBaseUrl)
        if (old.customGroqModels != updated.customGroqModels) editor.putString("custom_groq_models", serializeList(updated.customGroqModels))
        if (old.customGeminiModels != updated.customGeminiModels) editor.putString("custom_gemini_models", serializeList(updated.customGeminiModels))
        if (old.customOpenRouterModels != updated.customOpenRouterModels) editor.putString("custom_openrouter_models", serializeList(updated.customOpenRouterModels))
        if (old.customOpenAiModels != updated.customOpenAiModels) editor.putString("custom_openai_models", serializeList(updated.customOpenAiModels))
        if (old.customDeepSeekModels != updated.customDeepSeekModels) editor.putString("custom_deepseek_models", serializeList(updated.customDeepSeekModels))
        if (old.customCompatibleModels != updated.customCompatibleModels) editor.putString("custom_compatible_models", serializeList(updated.customCompatibleModels))
        if (old.fetchedGroqModels != updated.fetchedGroqModels) editor.putString("fetched_groq_models", serializeList(updated.fetchedGroqModels))
        if (old.fetchedGeminiModels != updated.fetchedGeminiModels) editor.putString("fetched_gemini_models", serializeList(updated.fetchedGeminiModels))
        if (old.fetchedOpenRouterModels != updated.fetchedOpenRouterModels) editor.putString("fetched_openrouter_models", serializeList(updated.fetchedOpenRouterModels))
        if (old.fetchedOpenAiModels != updated.fetchedOpenAiModels) editor.putString("fetched_openai_models", serializeList(updated.fetchedOpenAiModels))
        if (old.fetchedDeepSeekModels != updated.fetchedDeepSeekModels) editor.putString("fetched_deepseek_models", serializeList(updated.fetchedDeepSeekModels))
        if (old.fetchedCompatibleModels != updated.fetchedCompatibleModels) editor.putString("fetched_compatible_models", serializeList(updated.fetchedCompatibleModels))
        if (old.customGroqKey != updated.customGroqKey) editor.putString("custom_groq_key", ApiKeyCrypto.encrypt(updated.customGroqKey))
        if (old.customGeminiKey != updated.customGeminiKey) editor.putString("custom_gemini_key", ApiKeyCrypto.encrypt(updated.customGeminiKey))
        if (old.customOpenRouterKey != updated.customOpenRouterKey) editor.putString("custom_openrouter_key", ApiKeyCrypto.encrypt(updated.customOpenRouterKey))
        if (old.customOpenAiKey != updated.customOpenAiKey) editor.putString("custom_openai_key", ApiKeyCrypto.encrypt(updated.customOpenAiKey))
        if (old.customDeepSeekKey != updated.customDeepSeekKey) editor.putString("custom_deepseek_key", ApiKeyCrypto.encrypt(updated.customDeepSeekKey))
        if (old.customCompatibleKey != updated.customCompatibleKey) editor.putString("custom_compatible_key", ApiKeyCrypto.encrypt(updated.customCompatibleKey))
        if (old.systemPrompt != updated.systemPrompt) editor.putString("system_prompt", updated.systemPrompt)
        if (old.maxWordsLimit != updated.maxWordsLimit) editor.putInt("max_words_limit", updated.maxWordsLimit)
        if (old.matrixBrightness != updated.matrixBrightness) editor.putInt("matrix_brightness", updated.matrixBrightness)
        if (old.scrollSpeedMs != updated.scrollSpeedMs) editor.putInt("scroll_speed_ms", updated.scrollSpeedMs)
        if (old.silenceTimeoutMs != updated.silenceTimeoutMs) editor.putInt("silence_timeout_ms", updated.silenceTimeoutMs)
        if (old.uiLanguage != updated.uiLanguage) editor.putString("ui_language", updated.uiLanguage)
        if (old.speechLanguage != updated.speechLanguage) editor.putString("speech_language", updated.speechLanguage)
        if (old.voiceCaptureMode != updated.voiceCaptureMode) editor.putString("voice_capture_mode", updated.voiceCaptureMode.name)
        if (old.backgroundServiceEnabled != updated.backgroundServiceEnabled) editor.putBoolean("background_service_enabled", updated.backgroundServiceEnabled)
        editor.apply()
    }

    fun addCustomModel(provider: AiProvider, modelId: String) {
        val clean = modelId.trim()
        if (clean.isEmpty()) return
        updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> current.copy(
                    groqModel = clean,
                    customGroqModels = (listOf(clean) + current.customGroqModels).distinct()
                )
                AiProvider.GEMINI -> current.copy(
                    geminiModel = clean,
                    customGeminiModels = (listOf(clean) + current.customGeminiModels).distinct()
                )
                AiProvider.OPENROUTER -> current.copy(
                    openRouterModel = clean,
                    customOpenRouterModels = (listOf(clean) + current.customOpenRouterModels).distinct()
                )
                AiProvider.OPENAI -> current.copy(
                    openAiModel = clean,
                    customOpenAiModels = (listOf(clean) + current.customOpenAiModels).distinct()
                )
                AiProvider.DEEPSEEK -> current.copy(
                    deepSeekModel = clean,
                    customDeepSeekModels = (listOf(clean) + current.customDeepSeekModels).distinct()
                )
                AiProvider.CUSTOM_OPENAI -> current.copy(
                    customOpenAiModel = clean,
                    customCompatibleModels = (listOf(clean) + current.customCompatibleModels).distinct()
                )
            }
        }
    }

    fun removeCustomModel(provider: AiProvider, modelId: String) {
        val clean = modelId.trim()
        if (clean.isEmpty()) return
        updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> {
                    val nextList = current.customGroqModels.filterNot { it == clean }
                    val fallback = current.fetchedGroqModels.firstOrNull() ?: AiProvider.GROQ.defaultModel
                    val nextActive = if (current.groqModel == clean) fallback else current.groqModel
                    current.copy(groqModel = nextActive, customGroqModels = nextList)
                }
                AiProvider.GEMINI -> {
                    val nextList = current.customGeminiModels.filterNot { it == clean }
                    val fallback = current.fetchedGeminiModels.firstOrNull() ?: AiProvider.GEMINI.defaultModel
                    val nextActive = if (current.geminiModel == clean) fallback else current.geminiModel
                    current.copy(geminiModel = nextActive, customGeminiModels = nextList)
                }
                AiProvider.OPENROUTER -> {
                    val nextList = current.customOpenRouterModels.filterNot { it == clean }
                    val fallback = current.fetchedOpenRouterModels.firstOrNull() ?: AiProvider.OPENROUTER.defaultModel
                    val nextActive = if (current.openRouterModel == clean) fallback else current.openRouterModel
                    current.copy(openRouterModel = nextActive, customOpenRouterModels = nextList)
                }
                AiProvider.OPENAI -> {
                    val nextList = current.customOpenAiModels.filterNot { it == clean }
                    val fallback = current.fetchedOpenAiModels.firstOrNull() ?: AiProvider.OPENAI.defaultModel
                    val nextActive = if (current.openAiModel == clean) fallback else current.openAiModel
                    current.copy(openAiModel = nextActive, customOpenAiModels = nextList)
                }
                AiProvider.DEEPSEEK -> {
                    val nextList = current.customDeepSeekModels.filterNot { it == clean }
                    val fallback = current.fetchedDeepSeekModels.firstOrNull() ?: AiProvider.DEEPSEEK.defaultModel
                    val nextActive = if (current.deepSeekModel == clean) fallback else current.deepSeekModel
                    current.copy(deepSeekModel = nextActive, customDeepSeekModels = nextList)
                }
                AiProvider.CUSTOM_OPENAI -> {
                    val nextList = current.customCompatibleModels.filterNot { it == clean }
                    val fallback = current.fetchedCompatibleModels.firstOrNull() ?: AiProvider.CUSTOM_OPENAI.defaultModel
                    val nextActive = if (current.customOpenAiModel == clean) fallback else current.customOpenAiModel
                    current.copy(customOpenAiModel = nextActive, customCompatibleModels = nextList)
                }
            }
        }
    }

    /**
     * Replaces the cached API-fetched model list for `provider` with the latest list from the server,
     * keeping models always up-to-date. If the currently selected model is not in the live list or user
     * custom models, updates the active model to the first available live model.
     */
    fun setFetchedModels(provider: AiProvider, models: List<String>) {
        val cleaned = models.map { it.trim().removePrefix("models/") }
            .filter { isActiveSupportedTextModel(it, provider) }
            .distinct()
        if (cleaned.isEmpty()) return
        val fetchedSet = cleaned.toSet()

        updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> {
                    val userCustom = current.customGroqModels.filter { !fetchedSet.contains(it) && isActiveSupportedTextModel(it, AiProvider.GROQ) }
                    val currentActive = sanitizeSavedModel(current.groqModel, AiProvider.GROQ, userCustom)
                    val validActive = if (fetchedSet.contains(currentActive) || userCustom.contains(currentActive)) currentActive else cleaned.first()
                    current.copy(
                        groqModel = validActive,
                        customGroqModels = userCustom,
                        fetchedGroqModels = cleaned
                    )
                }
                AiProvider.GEMINI -> {
                    val userCustom = current.customGeminiModels.filter { !fetchedSet.contains(it) && isActiveSupportedTextModel(it, AiProvider.GEMINI) }
                    val currentActive = sanitizeSavedModel(current.geminiModel, AiProvider.GEMINI, userCustom)
                    val validActive = if (fetchedSet.contains(currentActive) || userCustom.contains(currentActive)) currentActive else cleaned.first()
                    current.copy(
                        geminiModel = validActive,
                        customGeminiModels = userCustom,
                        fetchedGeminiModels = cleaned
                    )
                }
                AiProvider.OPENROUTER -> {
                    val userCustom = current.customOpenRouterModels.filter { !fetchedSet.contains(it) && isActiveSupportedTextModel(it, AiProvider.OPENROUTER) }
                    val currentActive = sanitizeSavedModel(current.openRouterModel, AiProvider.OPENROUTER, userCustom)
                    val validActive = if (fetchedSet.contains(currentActive) || userCustom.contains(currentActive)) currentActive else cleaned.first()
                    current.copy(
                        openRouterModel = validActive,
                        customOpenRouterModels = userCustom,
                        fetchedOpenRouterModels = cleaned
                    )
                }
                AiProvider.OPENAI -> {
                    val userCustom = current.customOpenAiModels.filter { !fetchedSet.contains(it) && isActiveSupportedTextModel(it, AiProvider.OPENAI) }
                    val currentActive = sanitizeSavedModel(current.openAiModel, AiProvider.OPENAI, userCustom)
                    val validActive = if (fetchedSet.contains(currentActive) || userCustom.contains(currentActive)) currentActive else cleaned.first()
                    current.copy(
                        openAiModel = validActive,
                        customOpenAiModels = userCustom,
                        fetchedOpenAiModels = cleaned
                    )
                }
                AiProvider.DEEPSEEK -> {
                    val userCustom = current.customDeepSeekModels.filter { !fetchedSet.contains(it) && isActiveSupportedTextModel(it, AiProvider.DEEPSEEK) }
                    val currentActive = sanitizeSavedModel(current.deepSeekModel, AiProvider.DEEPSEEK, userCustom)
                    val validActive = if (fetchedSet.contains(currentActive) || userCustom.contains(currentActive)) currentActive else cleaned.first()
                    current.copy(
                        deepSeekModel = validActive,
                        customDeepSeekModels = userCustom,
                        fetchedDeepSeekModels = cleaned
                    )
                }
                AiProvider.CUSTOM_OPENAI -> {
                    val userCustom = current.customCompatibleModels.filter { !fetchedSet.contains(it) && isActiveSupportedTextModel(it, AiProvider.CUSTOM_OPENAI) }
                    val currentActive = sanitizeSavedModel(current.customOpenAiModel, AiProvider.CUSTOM_OPENAI, userCustom)
                    val validActive = if (fetchedSet.contains(currentActive) || userCustom.contains(currentActive)) currentActive else cleaned.first()
                    current.copy(
                        customOpenAiModel = validActive,
                        customCompatibleModels = userCustom,
                        fetchedCompatibleModels = cleaned
                    )
                }
            }
        }
    }

    fun resetSystemPrompt() {
        updateSettings { current ->
            val defaultPrompt = if (current.isEnglish) {
                OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
            } else {
                OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
            }
            current.copy(systemPrompt = defaultPrompt)
        }
    }

    fun clearAllApiKeys() {
        updateSettings { current ->
            current.copy(
                customGroqKey = "",
                customGeminiKey = "",
                customOpenRouterKey = "",
                customOpenAiKey = "",
                customDeepSeekKey = "",
                customCompatibleKey = ""
            )
        }
    }

    private fun parseList(raw: String?): List<String> {
        return raw.orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun serializeList(list: List<String>): String {
        return list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("|")
    }

    companion object {
        @Volatile
        private var INSTANCE: OracleSettingsRepository? = null

        fun getInstance(context: Context): OracleSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = OracleSettingsRepository(context)
                INSTANCE = instance
                instance
            }
        }
    }
}
