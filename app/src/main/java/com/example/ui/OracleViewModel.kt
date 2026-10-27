package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiProvider
import com.example.data.OracleDatabase
import com.example.data.OracleHistoryItem
import com.example.data.OracleHistoryRepository
import com.example.data.OracleSettings
import com.example.data.OracleSettingsRepository
import com.example.data.SttProvider
import com.example.data.VoiceCaptureMode
import com.example.glyph.GlyphOracleCoordinator
import com.example.glyph.GlyphOracleToyService
import com.example.glyph.ToyOracleState
import com.example.network.AiOracleClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OracleViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator = GlyphOracleCoordinator.getInstance(application)
    private val settingsRepository = OracleSettingsRepository.getInstance(application)
    private val historyRepository = OracleHistoryRepository(
        OracleDatabase.getInstance(application).historyDao()
    )

    val oracleState: StateFlow<ToyOracleState> = coordinator.oracleState
    val matrixFrame: StateFlow<IntArray> = coordinator.matrixFrame
    val isHardwareConnected: StateFlow<Boolean> = coordinator.isHardwareConnected
    val isToyServiceBound: StateFlow<Boolean> = coordinator.isToyServiceBound

    val settings: StateFlow<OracleSettings> = settingsRepository.settings

    private val _apiDiagnosticMessage = MutableStateFlow<String?>(null)
    val apiDiagnosticMessage: StateFlow<String?> = _apiDiagnosticMessage.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private var autoFetchJob: Job? = null

    val history: StateFlow<List<OracleHistoryItem>> = historyRepository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init {
        refreshAllConfiguredProvidersOnStartup()
    }

    private fun refreshAllConfiguredProvidersOnStartup() {
        viewModelScope.launch {
            val snapshot = settings.value
            val active = snapshot.selectedProvider
            fetchModelsInternal(
                provider = active,
                silent = true,
                updateStatusIndicator = true
            )
            AiProvider.entries
                .filter { it != active }
                .forEach { provider ->
                    val key = settings.value.getResolvedApiKey(provider)
                    if (key.isNotBlank() || provider == AiProvider.OPENROUTER) {
                        fetchModelsInternal(
                            provider = provider,
                            silent = true,
                            updateStatusIndicator = false
                        )
                    }
                }
        }
    }

    private fun scheduleAutoFetchModels(
        provider: AiProvider,
        debounceMs: Long = 500L,
        announceSuccess: Boolean = false
    ) {
        autoFetchJob?.cancel()
        autoFetchJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            fetchModelsInternal(
                provider = provider,
                silent = !announceSuccess,
                updateStatusIndicator = true
            )
        }
    }

    private suspend fun fetchModelsInternal(
        provider: AiProvider,
        silent: Boolean,
        updateStatusIndicator: Boolean
    ) {
        val current = settings.value
        val key = current.getResolvedApiKey(provider)
        val baseUrl = current.getBaseUrl(provider)
        val isEn = current.isEnglish

        val canFetchWithoutKey = provider == AiProvider.OPENROUTER
        if (key.isBlank() && !canFetchWithoutKey) {
            if (!silent) {
                _apiDiagnosticMessage.value = if (isEn) {
                    "Enter an API key for ${provider.displayName} first"
                } else {
                    "Сначала введите API-ключ для ${provider.displayName}"
                }
            }
            return
        }

        if (silent && !canFetchWithoutKey && key.length < 8) {
            return
        }

        if (updateStatusIndicator) {
            _isFetchingModels.value = true
        }
        if (!silent) {
            _apiDiagnosticMessage.value = if (isEn) {
                "Fetching models from ${provider.displayName}..."
            } else {
                "Загрузка актуальных моделей ${provider.displayName}..."
            }
        }

        val result = AiOracleClient.fetchAvailableModels(provider, key, baseUrl, isEn)
        if (updateStatusIndicator) {
            _isFetchingModels.value = false
        }

        result.fold(
            onSuccess = { models ->
                settingsRepository.setFetchedModels(provider, models)
                if (!silent) {
                    _apiDiagnosticMessage.value = if (isEn) {
                        "Updated ${models.size} live models from ${provider.displayName}."
                    } else {
                        "Список моделей ${provider.displayName} обновлен (${models.size} шт.)."
                    }
                }
            },
            onFailure = { err ->
                if (!silent) {
                    _apiDiagnosticMessage.value = if (isEn) {
                        "Error loading models: ${err.message}"
                    } else {
                        "Ошибка загрузки моделей: ${err.message}"
                    }
                }
            }
        )
    }

    fun triggerGlyphLongPress() {
        coordinator.onGlyphButtonLongPress()
    }

    fun stopVoiceListening() {
        coordinator.stopVoiceListening()
    }

    fun dismissError() {
        coordinator.dismissError()
    }

    fun submitTextQuestion(question: String) {
        coordinator.submitQuestion(question)
    }

    fun replayOnMatrix(item: OracleHistoryItem) {
        coordinator.displayAnswerOnMatrix(
            question = item.question,
            answer = item.answer,
            providerName = item.provider,
            latencyMs = item.latencyMs
        )
    }

    fun previewAnswerDirectly(label: String, sampleAnswer: String) {
        coordinator.displayAnswerOnMatrix(
            question = label,
            answer = sampleAnswer
        )
    }

    fun selectProvider(provider: AiProvider) {
        _apiDiagnosticMessage.value = null
        settingsRepository.updateSettings { it.copy(selectedProvider = provider) }
        scheduleAutoFetchModels(provider = provider, debounceMs = 80L, announceSuccess = false)
    }

    fun updateSttProvider(sttProvider: SttProvider) {
        settingsRepository.updateSettings { it.copy(sttProvider = sttProvider) }
    }

    fun updateActiveModel(model: String) {
        settingsRepository.updateSettings { current ->
            when (current.selectedProvider) {
                AiProvider.GROQ -> current.copy(groqModel = model)
                AiProvider.GEMINI -> current.copy(geminiModel = model)
                AiProvider.OPENROUTER -> current.copy(openRouterModel = model)
                AiProvider.OPENAI -> current.copy(openAiModel = model)
                AiProvider.DEEPSEEK -> current.copy(deepSeekModel = model)
                AiProvider.CUSTOM_OPENAI -> current.copy(customOpenAiModel = model)
            }
        }
    }

    fun updateCustomOpenAiBaseUrl(baseUrl: String) {
        settingsRepository.updateSettings { current ->
            current.copy(customOpenAiBaseUrl = baseUrl)
        }
        if (baseUrl.trim().startsWith("http", ignoreCase = true)) {
            scheduleAutoFetchModels(
                provider = AiProvider.CUSTOM_OPENAI,
                debounceMs = 650L,
                announceSuccess = false
            )
        }
    }

    fun applyOpenAiEndpointPreset(baseUrl: String, sampleModel: String) {
        settingsRepository.updateSettings { current ->
            current.copy(
                selectedProvider = AiProvider.CUSTOM_OPENAI,
                customOpenAiBaseUrl = baseUrl,
                customOpenAiModel = sampleModel,
                customCompatibleModels = (listOf(sampleModel) + current.customCompatibleModels).distinct()
            )
        }
        scheduleAutoFetchModels(
            provider = AiProvider.CUSTOM_OPENAI,
            debounceMs = 150L,
            announceSuccess = true
        )
    }

    fun addCustomModel(modelId: String) {
        val current = settings.value
        val currentProvider = current.selectedProvider
        settingsRepository.addCustomModel(currentProvider, modelId)
        _apiDiagnosticMessage.value = if (current.isEnglish) {
            "Model «${modelId.trim()}» added and selected for ${currentProvider.displayName}"
        } else {
            "Модель «${modelId.trim()}» добавлена и выбрана для ${currentProvider.displayName}"
        }
    }

    fun removeCustomModel(modelId: String) {
        val current = settings.value
        val currentProvider = current.selectedProvider
        settingsRepository.removeCustomModel(currentProvider, modelId)
        _apiDiagnosticMessage.value = if (current.isEnglish) {
            "Model «${modelId.trim()}» removed"
        } else {
            "Модель «${modelId.trim()}» удалена из списка"
        }
    }

    fun fetchModelsFromApi() {
        autoFetchJob?.cancel()
        autoFetchJob = viewModelScope.launch {
            fetchModelsInternal(
                provider = settings.value.selectedProvider,
                silent = false,
                updateStatusIndicator = true
            )
        }
    }

    fun testActiveProviderConnection() {
        val current = settings.value
        val isEn = current.isEnglish
        if (!current.hasActiveApiKey) {
            _apiDiagnosticMessage.value = if (isEn) {
                "Enter an API key for ${current.selectedProvider.displayName} first"
            } else {
                "Сначала введите API-ключ для ${current.selectedProvider.displayName}"
            }
            return
        }

        _apiDiagnosticMessage.value = if (isEn) {
            "Testing ${current.selectedProvider.displayName} (${current.getActiveModel()})..."
        } else {
            "Проверка ${current.selectedProvider.displayName} (${current.getActiveModel()})..."
        }
        viewModelScope.launch {
            val testPrompt = if (isEn) "What is 2+2?" else "Сколько будет 2+2?"
            val res = AiOracleClient.askOracle(testPrompt, current)
            res.fold(
                onSuccess = { ok ->
                    _apiDiagnosticMessage.value = if (isEn) {
                        "SUCCESS (${ok.provider.displayName} • ${ok.model}): «${ok.answer}» in ${ok.latencyMs} ms"
                    } else {
                        "УСПЕШНО (${ok.provider.displayName} • ${ok.model}): ответ «${ok.answer}» за ${ok.latencyMs} мс"
                    }
                    coordinator.displayAnswerOnMatrix(
                        question = if (isEn) "API Test" else "Тест API",
                        answer = ok.answer,
                        providerName = ok.provider.displayName,
                        latencyMs = ok.latencyMs
                    )
                },
                onFailure = { err ->
                    _apiDiagnosticMessage.value = if (isEn) {
                        "ERROR: ${err.message}"
                    } else {
                        "ОШИБКА: ${err.message}"
                    }
                }
            )
        }
    }

    fun updateCustomApiKey(provider: AiProvider, key: String) {
        settingsRepository.updateSettings { current ->
            when (provider) {
                AiProvider.GROQ -> current.copy(customGroqKey = key)
                AiProvider.GEMINI -> current.copy(customGeminiKey = key)
                AiProvider.OPENROUTER -> current.copy(customOpenRouterKey = key)
                AiProvider.OPENAI -> current.copy(customOpenAiKey = key)
                AiProvider.DEEPSEEK -> current.copy(customDeepSeekKey = key)
                AiProvider.CUSTOM_OPENAI -> current.copy(customCompatibleKey = key)
            }
        }
        if (key.trim().length >= 8) {
            scheduleAutoFetchModels(
                provider = provider,
                debounceMs = 550L,
                announceSuccess = true
            )
        }
    }

    fun clearAllApiKeys() {
        autoFetchJob?.cancel()
        settingsRepository.clearAllApiKeys()
        val isEn = settings.value.isEnglish
        _apiDiagnosticMessage.value = if (isEn) {
            "All saved API keys cleared from device."
        } else {
            "Все сохраненные API-ключи очищены с устройства."
        }
    }

    fun updateSystemPrompt(prompt: String) {
        settingsRepository.updateSettings { it.copy(systemPrompt = prompt) }
    }

    fun resetSystemPrompt() {
        settingsRepository.resetSystemPrompt()
    }

    fun updateMaxWordsLimit(limit: Int) {
        settingsRepository.updateSettings { it.copy(maxWordsLimit = limit.coerceIn(1, 5)) }
    }

    fun updateMatrixBrightness(brightness: Int) {
        settingsRepository.updateSettings { it.copy(matrixBrightness = brightness.coerceIn(40, 255)) }
        coordinator.refreshBrightnessPreview()
    }

    fun updateScrollSpeedMs(speedMs: Int) {
        settingsRepository.updateSettings { it.copy(scrollSpeedMs = speedMs.coerceIn(35, 160)) }
    }

    fun updateSilenceTimeoutMs(timeoutMs: Int) {
        settingsRepository.updateSettings { it.copy(silenceTimeoutMs = timeoutMs.coerceIn(600, 2000)) }
    }

    fun updateUiLanguage(lang: String) {
        val cleanUi = if (lang.startsWith("en", ignoreCase = true)) "en" else "ru"
        settingsRepository.updateSettings { current ->
            val switchingToEn = cleanUi == "en"
            val updatedPrompt = when {
                switchingToEn && current.systemPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_RU ->
                    OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
                !switchingToEn && current.systemPrompt == OracleSettings.DEFAULT_SYSTEM_PROMPT_EN ->
                    OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
                else -> current.systemPrompt
            }
            current.copy(
                uiLanguage = cleanUi,
                systemPrompt = updatedPrompt
            )
        }
    }

    fun updateSpeechLanguage(lang: String) {
        settingsRepository.updateSettings { current ->
            current.copy(speechLanguage = lang)
        }
    }

    fun updateVoiceCaptureMode(mode: VoiceCaptureMode) {
        settingsRepository.updateSettings { it.copy(voiceCaptureMode = mode) }
    }

    fun updateBackgroundServiceEnabled(enabled: Boolean) {
        settingsRepository.updateSettings { it.copy(backgroundServiceEnabled = enabled) }
        GlyphOracleToyService.syncBackgroundServiceState(getApplication())
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteById(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
        }
    }
}
