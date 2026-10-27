package com.example.glyph

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.data.OracleDatabase
import com.example.data.OracleHistoryItem
import com.example.data.OracleHistoryRepository
import com.example.data.OracleSettingsRepository
import com.example.data.VoiceCaptureMode
import com.example.network.AiOracleClient
import com.example.network.AiOracleResult
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class ToyOracleState {
    data object Idle : ToyOracleState()
    data class Listening(
        val partialText: String = "",
        val rmsDb: Float = 0f,
        val isDirectAudioRecord: Boolean = false
    ) : ToyOracleState()

    data class Thinking(
        val question: String,
        val providerName: String
    ) : ToyOracleState()

    data class DisplayingAnswer(
        val question: String,
        val answer: String,
        val isStatic: Boolean,
        val providerName: String,
        val latencyMs: Long
    ) : ToyOracleState()

    data class Error(
        val message: String
    ) : ToyOracleState()
}

@OptIn(ExperimentalCoroutinesApi::class)
class GlyphOracleCoordinator private constructor(private val appContext: Context) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Uncaught coroutine exception in GlyphOracleCoordinator", throwable)
            mainHandler.post {
                showError(throwable.localizedMessage ?: "Unexpected internal error")
            }
        }
    }

    private val hardwareDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val hardwareScope = CoroutineScope(SupervisorJob() + hardwareDispatcher + exceptionHandler)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val settingsRepo = OracleSettingsRepository.getInstance(appContext)
    private val historyRepo = OracleHistoryRepository(
        OracleDatabase.getInstance(appContext).historyDao()
    )

    private val _oracleState = MutableStateFlow<ToyOracleState>(ToyOracleState.Idle)
    val oracleState: StateFlow<ToyOracleState> = _oracleState.asStateFlow()

    private val _matrixFrame = MutableStateFlow(PixelMatrixRenderer.renderIdleGlyph(4, 255))
    val matrixFrame: StateFlow<IntArray> = _matrixFrame.asStateFlow()

    private val _isHardwareConnected = MutableStateFlow(false)
    val isHardwareConnected: StateFlow<Boolean> = _isHardwareConnected.asStateFlow()

    private val _isToyServiceBound = MutableStateFlow(false)
    val isToyServiceBound: StateFlow<Boolean> = _isToyServiceBound.asStateFlow()

    @Volatile
    private var isAppInForeground: Boolean = false

    @Volatile
    private var glyphManager: GlyphMatrixManager? = null

    private val isBindingHardware = AtomicBoolean(false)
    private val sessionToken = AtomicLong(0L)

    // Conflated channel + reusable hardware buffer to avoid per-frame coroutine & Builder allocations
    private val hardwareFrameChannel = Channel<IntArray>(Channel.CONFLATED)
    private val reusableHardwareBuffer = IntArray(PixelMatrixRenderer.TOTAL_PIXELS)

    private var speechRecognizer: SpeechRecognizer? = null
    private var lockScreenRecorder: LockScreenAudioRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastPartialTranscript: String = ""
    private var lastDisplayedAnswer: String? = null

    private var animationJob: Job? = null
    private var activeQueryJob: Job? = null
    private var speechWatchdogJob: Job? = null
    private var tickCounter = 0

    private val glyphCallback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            isBindingHardware.set(false)
            hardwareScope.launch {
                try {
                    val manager = glyphManager ?: GlyphMatrixManager.getInstance(appContext).also {
                        glyphManager = it
                    }
                    val isPhone3 = runCatching { Common.is23112() }.getOrDefault(false)
                    val registered = manager.register(Glyph.DEVICE_23112)
                    _isHardwareConnected.value = registered
                    Log.d(
                        TAG,
                        "GlyphService connected (Common.is23112=$isPhone3), register(DEVICE_23112)=$registered"
                    )
                    hardwareFrameChannel.trySend(_matrixFrame.value)
                } catch (t: Throwable) {
                    Log.w(TAG, "Glyph register error: ${t.message}")
                    _isHardwareConnected.value = false
                }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            isBindingHardware.set(false)
            _isHardwareConnected.value = false
        }
    }

    init {
        startHardwareFrameWorker()
        ensureHardwareInitialized()
        showIdleState()
    }

    private fun startHardwareFrameWorker() {
        hardwareScope.launch {
            for (uiPixels in hardwareFrameChannel) {
                pushFrameToHardwareInternal(uiPixels)
            }
        }
    }

    fun ensureHardwareInitialized() {
        hardwareScope.launch {
            try {
                val manager = glyphManager ?: GlyphMatrixManager.getInstance(appContext).also {
                    glyphManager = it
                }
                if (_isHardwareConnected.value) {
                    manager.register(Glyph.DEVICE_23112)
                    hardwareFrameChannel.trySend(_matrixFrame.value)
                } else if (isBindingHardware.compareAndSet(false, true)) {
                    manager.init(glyphCallback)
                }
            } catch (t: Throwable) {
                isBindingHardware.set(false)
                Log.w(TAG, "GlyphMatrixManager init fallback: ${t.message}")
                _isHardwareConnected.value = false
            }
        }
    }

    fun onAppForeground() {
        isAppInForeground = true
        ensureHardwareInitialized()
        hardwareFrameChannel.trySend(_matrixFrame.value)
    }

    fun onAppBackground() {
        isAppInForeground = false
        if (!_isToyServiceBound.value) {
            hardwareScope.launch {
                try {
                    glyphManager?.closeAppMatrix()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun ensureToyActiveFromEvent() {
        if (!_isToyServiceBound.value) {
            _isToyServiceBound.value = true
        }
        if (!_isHardwareConnected.value) {
            ensureHardwareInitialized()
        }
    }

    fun onToyServiceBind() {
        _isToyServiceBound.value = true
        ensureHardwareInitialized()
        showIdleState()
    }

    fun onToyServiceUnbind() {
        _isToyServiceBound.value = false
        sessionToken.incrementAndGet()
        mainHandler.post {
            activeQueryJob?.cancel()
            activeQueryJob = null
            animationJob?.cancel()
            animationJob = null
            stopListeningInternal()
            releaseWakeLock()
            _oracleState.value = ToyOracleState.Idle
        }
        hardwareScope.launch {
            try {
                setHardwareTimeoutDisabledInternal(false)
                glyphManager?.turnOff()
                if (!isAppInForeground) {
                    isBindingHardware.set(false)
                    _isHardwareConnected.value = false
                    glyphManager?.unInit()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun dismissError() {
        mainHandler.post {
            if (_oracleState.value is ToyOracleState.Error) {
                showIdleState()
            }
        }
    }

    fun onGlyphButtonLongPress() {
        mainHandler.post {
            vibrateShortTick()
            when (val current = _oracleState.value) {
                is ToyOracleState.Listening -> {
                    if (current.isDirectAudioRecord) {
                        lockScreenRecorder?.finishEarly()
                    } else {
                        val captured = lastPartialTranscript.trim()
                        stopListeningInternal()
                        if (captured.isNotBlank()) {
                            submitQuestion(captured)
                        } else {
                            showIdleState()
                        }
                    }
                }
                else -> {
                    startVoiceListening()
                }
            }
        }
    }

    fun startVoiceListening() {
        mainHandler.post {
            val currentSettings = settingsRepo.settings.value
            if (!currentSettings.hasActiveApiKey) {
                val msg = if (currentSettings.isEnglish) {
                    "Enter an API key for ${currentSettings.selectedProvider.displayName} in settings first"
                } else {
                    "Сначала введите API-ключ для ${currentSettings.selectedProvider.displayName} в настройках"
                }
                showError(msg)
                return@post
            }

            val token = sessionToken.incrementAndGet()
            activeQueryJob?.cancel()
            stopListeningInternal()
            acquireWakeLock()

            scope.launch {
                AiOracleClient.prewarmConnection(currentSettings)
            }

            val mode = currentSettings.voiceCaptureMode
            val inBackgroundOrLocked = !isAppInForeground || isDeviceLockedOrScreenOff()
            val prefersCloudStt = currentSettings.sttProvider != com.example.data.SttProvider.AUTO

            val useDirectAudioRecord = when (mode) {
                VoiceCaptureMode.ALWAYS_AUDIORECORD -> true
                VoiceCaptureMode.AUTO_HYBRID ->
                    inBackgroundOrLocked || prefersCloudStt || !SpeechRecognizer.isRecognitionAvailable(appContext)
            }

            if (useDirectAudioRecord) {
                startDirectAudioRecordListening(token)
            } else {
                startAndroidSpeechRecognizer(token, fallbackToAudioRecordOnError = true)
            }
        }
    }

    private fun startDirectAudioRecordListening(token: Long) {
        val currentSettings = settingsRepo.settings.value
        val isEn = currentSettings.isEnglish
        setHardwareTimeoutDisabled(true)
        _oracleState.value = ToyOracleState.Listening(
            partialText = if (isEn) {
                "Speak now (auto-stops on pause)..."
            } else {
                "Говорите (автоотправка при паузе)..."
            },
            rmsDb = 2f,
            isDirectAudioRecord = true
        )
        startListeningAnimationLoop()

        val recorder = LockScreenAudioRecorder(
            scope = scope,
            silenceTimeoutMs = currentSettings.silenceTimeoutMs.toLong(),
            isEnglish = isEn,
            onRmsChanged = { rmsDb ->
                if (sessionToken.get() == token) {
                    val current = _oracleState.value
                    if (current is ToyOracleState.Listening && kotlin.math.abs(current.rmsDb - rmsDb) >= 0.7f) {
                        _oracleState.value = current.copy(rmsDb = rmsDb)
                    }
                }
            },
            onRecordingComplete = { wavBytes ->
                mainHandler.post {
                    if (sessionToken.get() == token) {
                        processRecordedWavAudio(token, wavBytes)
                    }
                }
            },
            onError = { errMsg ->
                mainHandler.post {
                    if (sessionToken.get() == token) {
                        showError(errMsg)
                    }
                }
            }
        )
        lockScreenRecorder = recorder
        recorder.start()
    }

    private fun processRecordedWavAudio(token: Long, wavBytes: ByteArray) {
        val currentSettings = settingsRepo.settings.value
        val isEn = currentSettings.isEnglish
        _oracleState.value = ToyOracleState.Thinking(
            question = if (isEn) "Processing voice..." else "Обработка голоса...",
            providerName = currentSettings.selectedProvider.displayName
        )
        startThinkingAnimationLoop()

        activeQueryJob?.cancel()
        activeQueryJob = scope.launch {
            val result = AiOracleClient.processAudioAndAskOracle(wavBytes, currentSettings)
            if (sessionToken.get() != token) return@launch
            result.fold(
                onSuccess = { oracleResult ->
                    handleOracleSuccess(token, oracleResult)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    mainHandler.post {
                        if (sessionToken.get() == token) {
                            showError(
                                error.message ?: if (isEn) "Voice recognition failed" else "Ошибка распознавания аудио"
                            )
                        }
                    }
                }
            )
        }
    }

    private fun scheduleSpeechWatchdog(
        token: Long,
        fallbackToAudioRecordOnError: Boolean,
        isEn: Boolean,
        timeoutMs: Long = 8_000L
    ) {
        speechWatchdogJob?.cancel()
        speechWatchdogJob = scope.launch {
            delay(timeoutMs)
            mainHandler.post {
                if (sessionToken.get() != token) return@post
                val state = _oracleState.value
                if (state is ToyOracleState.Listening && !state.isDirectAudioRecord) {
                    val partial = lastPartialTranscript.trim()
                    stopSpeechRecognizerOnly()
                    if (partial.isNotBlank()) {
                        submitQuestion(partial)
                    } else if (fallbackToAudioRecordOnError) {
                        startDirectAudioRecordListening(token)
                    } else {
                        showError(if (isEn) "Speech recognition timed out" else "Время ожидания речи истекло")
                    }
                }
            }
        }
    }

    private fun startAndroidSpeechRecognizer(token: Long, fallbackToAudioRecordOnError: Boolean) {
        try {
            val currentSettings = settingsRepo.settings.value
            val isEn = currentSettings.isEnglish
            setHardwareTimeoutDisabled(true)
            lastPartialTranscript = ""
            _oracleState.value = ToyOracleState.Listening(
                partialText = "",
                rmsDb = 2f,
                isDirectAudioRecord = false
            )
            startListeningAnimationLoop()

            stopSpeechRecognizerOnly()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer

            scheduleSpeechWatchdog(token, fallbackToAudioRecordOnError, isEn, 8_000L)

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (sessionToken.get() != token) return
                    _oracleState.value = ToyOracleState.Listening(
                        partialText = "",
                        rmsDb = 2f,
                        isDirectAudioRecord = false
                    )
                }

                override fun onBeginningOfSpeech() {
                    if (sessionToken.get() != token) return
                    scheduleSpeechWatchdog(token, fallbackToAudioRecordOnError, isEn, 8_000L)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (sessionToken.get() != token) return
                    val current = _oracleState.value
                    if (current is ToyOracleState.Listening && kotlin.math.abs(current.rmsDb - rmsdB) >= 0.8f) {
                        _oracleState.value = current.copy(rmsDb = rmsdB)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (sessionToken.get() != token) return
                    speechWatchdogJob?.cancel()
                    val fallbackText = lastPartialTranscript.trim()
                    stopSpeechRecognizerOnly()
                    if (fallbackText.isNotBlank()) {
                        submitQuestion(fallbackText)
                    } else if (fallbackToAudioRecordOnError) {
                        Log.w(TAG, "SpeechRecognizer error $error -> switching to AudioRecord")
                        startDirectAudioRecordListening(token)
                    } else {
                        val errorDesc = mapSpeechError(error, isEn)
                        showError(errorDesc)
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (sessionToken.get() != token) return
                    speechWatchdogJob?.cancel()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                        .ifBlank { lastPartialTranscript.trim() }
                    stopListeningInternal()
                    if (spokenText.isNotBlank()) {
                        submitQuestion(spokenText)
                    } else if (fallbackToAudioRecordOnError) {
                        startDirectAudioRecordListening(token)
                    } else {
                        showError(
                            if (isEn) "Speech not recognized. Try again."
                            else "Речь не распознана. Попробуйте еще раз."
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (sessionToken.get() != token) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotBlank() && partial != lastPartialTranscript) {
                        lastPartialTranscript = partial
                        scheduleSpeechWatchdog(token, fallbackToAudioRecordOnError, isEn, 6_000L)
                        val current = _oracleState.value
                        if (current is ToyOracleState.Listening) {
                            _oracleState.value = current.copy(partialText = partial)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val localeTag = currentSettings.getRecognizerLocaleTag()
            val completeSilenceMs = currentSettings.silenceTimeoutMs.toLong().coerceIn(1100L, 2200L)
            val possibleSilenceMs = (completeSilenceMs - 200L).coerceAtLeast(900L)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (!localeTag.isNullOrBlank()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    completeSilenceMs
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    possibleSilenceMs
                )
            }

            recognizer.startListening(intent)
        } catch (e: Exception) {
            if (fallbackToAudioRecordOnError) {
                startDirectAudioRecordListening(token)
            } else {
                showError("Mic error: ${e.localizedMessage ?: "unknown"}")
            }
        }
    }

    fun stopVoiceListening() {
        mainHandler.post {
            val current = _oracleState.value
            if (current is ToyOracleState.Listening && current.isDirectAudioRecord) {
                lockScreenRecorder?.finishEarly()
                return@post
            }

            val captured = lastPartialTranscript.trim()
            stopListeningInternal()
            if (captured.isNotBlank()) {
                submitQuestion(captured)
            } else {
                showIdleState()
            }
        }
    }

    fun submitQuestion(question: String) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isEmpty()) return

        mainHandler.post {
            val currentSettings = settingsRepo.settings.value
            if (!currentSettings.hasActiveApiKey) {
                val msg = if (currentSettings.isEnglish) {
                    "Enter an API key for ${currentSettings.selectedProvider.displayName} in settings first"
                } else {
                    "Сначала введите API-ключ для ${currentSettings.selectedProvider.displayName} в настройках"
                }
                showError(msg)
                return@post
            }

            val token = sessionToken.incrementAndGet()
            activeQueryJob?.cancel()
            stopListeningInternal()
            acquireWakeLock()

            activeQueryJob = scope.launch {
                executeOracleQuery(token, cleanQuestion)
            }
        }
    }

    private suspend fun executeOracleQuery(token: Long, cleanQuestion: String) {
        val currentSettings = settingsRepo.settings.value
        _oracleState.value = ToyOracleState.Thinking(
            question = cleanQuestion,
            providerName = currentSettings.selectedProvider.displayName
        )
        startThinkingAnimationLoop()

        val result = AiOracleClient.askOracle(cleanQuestion, currentSettings)
        if (sessionToken.get() != token) return
        result.fold(
            onSuccess = { oracleResult ->
                handleOracleSuccess(token, oracleResult)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val fallbackMsg = if (currentSettings.isEnglish) {
                    "AI connection error"
                } else {
                    "Ошибка соединения с нейросетью"
                }
                mainHandler.post {
                    if (sessionToken.get() == token) {
                        showError(error.message ?: fallbackMsg)
                    }
                }
            }
        )
    }

    private suspend fun handleOracleSuccess(token: Long, oracleResult: AiOracleResult) {
        if (sessionToken.get() != token) return
        val isStatic = PixelMatrixRenderer.shouldDisplayStatically(oracleResult.answer)
        lastDisplayedAnswer = oracleResult.answer
        vibrateShortTick()

        historyRepo.insert(
            OracleHistoryItem(
                question = oracleResult.question,
                answer = oracleResult.answer,
                provider = oracleResult.provider.displayName,
                model = oracleResult.model,
                latencyMs = oracleResult.latencyMs,
                isStaticDisplay = isStatic
            )
        )

        if (sessionToken.get() == token) {
            displayAnswerOnMatrix(
                question = oracleResult.question,
                answer = oracleResult.answer,
                providerName = oracleResult.provider.displayName,
                latencyMs = oracleResult.latencyMs
            )
        }
    }

    fun displayAnswerOnMatrix(
        question: String,
        answer: String,
        providerName: String = settingsRepo.settings.value.selectedProvider.displayName,
        latencyMs: Long = 0L
    ) {
        val cleanAnswer = PixelMatrixRenderer.normalizeForMatrix(answer)
        val isStatic = PixelMatrixRenderer.shouldDisplayStatically(cleanAnswer)
        lastDisplayedAnswer = cleanAnswer

        _oracleState.value = ToyOracleState.DisplayingAnswer(
            question = question,
            answer = cleanAnswer,
            isStatic = isStatic,
            providerName = providerName,
            latencyMs = latencyMs
        )

        animationJob?.cancel()
        animationJob = scope.launch {
            try {
                setHardwareTimeoutDisabled(true)
                val settings = settingsRepo.settings.value
                val brightness = settings.matrixBrightness

                if (isStatic) {
                    val staticFrame = PixelMatrixRenderer.renderStaticAnswer(cleanAnswer, brightness)
                    updateMatrixFrame(staticFrame)
                    delay(8_500L)
                } else {
                    val textWidth = PixelMatrixRenderer.measureTextWidth(cleanAnswer)
                    val scrollSpeed = settings.scrollSpeedMs.toLong().coerceIn(35L, 160L)
                    repeat(3) {
                        for (offset in 0..(textWidth + PixelMatrixRenderer.MATRIX_SIZE + 4)) {
                            if (!isActive) return@launch
                            val currentBrightness = settingsRepo.settings.value.matrixBrightness
                            val frame = PixelMatrixRenderer.renderMarqueeAnswer(
                                text = cleanAnswer,
                                scrollOffsetPx = offset,
                                brightness = currentBrightness
                            )
                            updateMatrixFrame(frame)
                            delay(scrollSpeed)
                        }
                        delay(300L)
                    }
                }
                showIdleState()
            } finally {
                setHardwareTimeoutDisabled(false)
                releaseWakeLock()
            }
        }
    }

    fun onAodTick() {
        scope.launch {
            val brightness = settingsRepo.settings.value.matrixBrightness.coerceAtMost(90)
            val aodFrame = PixelMatrixRenderer.renderAodFrame(lastDisplayedAnswer, brightness)
            updateMatrixFrame(aodFrame)
        }
    }

    fun refreshBrightnessPreview() {
        scope.launch {
            val brightness = settingsRepo.settings.value.matrixBrightness
            when (val st = _oracleState.value) {
                is ToyOracleState.Idle -> {
                    updateMatrixFrame(PixelMatrixRenderer.renderIdleGlyph(4, brightness))
                }
                is ToyOracleState.DisplayingAnswer -> {
                    if (st.isStatic) {
                        updateMatrixFrame(PixelMatrixRenderer.renderStaticAnswer(st.answer, brightness))
                    }
                }
                else -> {}
            }
        }
    }

    private fun showIdleState() {
        _oracleState.value = ToyOracleState.Idle
        animationJob?.cancel()
        animationJob = scope.launch {
            setHardwareTimeoutDisabled(false)
            releaseWakeLock()
            val brightness = settingsRepo.settings.value.matrixBrightness
            val frame = PixelMatrixRenderer.renderIdleGlyph(4, brightness)
            updateMatrixFrame(frame)
        }
    }

    private fun startListeningAnimationLoop() {
        animationJob?.cancel()
        animationJob = scope.launch {
            while (isActive) {
                tickCounter++
                val current = _oracleState.value
                val rms = if (current is ToyOracleState.Listening) current.rmsDb else 2f
                val brightness = settingsRepo.settings.value.matrixBrightness
                val frame = PixelMatrixRenderer.renderListeningWave(tickCounter, rms, brightness)
                updateMatrixFrame(frame)
                delay(110L)
            }
        }
    }

    private fun startThinkingAnimationLoop() {
        animationJob?.cancel()
        animationJob = scope.launch {
            while (isActive) {
                tickCounter++
                val brightness = settingsRepo.settings.value.matrixBrightness
                val frame = PixelMatrixRenderer.renderThinkingSpinner(tickCounter, brightness)
                updateMatrixFrame(frame)
                delay(110L)
            }
        }
    }

    /**
     * Keeps `ToyOracleState.Error` visible in the app UI until the user dismisses it or starts a new query,
     * while blinking "ERR" on the physical 25x25 LED matrix for 3 seconds and then returning the rear LEDs
     * to the low-power idle glyph.
     */
    private fun showError(message: String) {
        _oracleState.value = ToyOracleState.Error(message)
        animationJob?.cancel()
        animationJob = scope.launch {
            try {
                repeat(15) {
                    if (!isActive) return@launch
                    tickCounter++
                    val brightness = settingsRepo.settings.value.matrixBrightness
                    val frame = PixelMatrixRenderer.renderErrorFrame(tickCounter, brightness)
                    updateMatrixFrame(frame)
                    delay(200L)
                }
                val brightness = settingsRepo.settings.value.matrixBrightness
                updateMatrixFrame(PixelMatrixRenderer.renderIdleGlyph(4, brightness))
            } finally {
                setHardwareTimeoutDisabled(false)
                releaseWakeLock()
            }
        }
    }

    private fun updateMatrixFrame(rawPixels: IntArray) {
        _matrixFrame.value = rawPixels
        hardwareFrameChannel.trySend(rawPixels)
    }

    private fun pushFrameToHardwareInternal(uiPixels: IntArray) {
        val manager = glyphManager ?: return
        if (!_isHardwareConnected.value) return
        if (!_isToyServiceBound.value && !isAppInForeground) return

        try {
            val hardwarePixels = PixelMatrixRenderer.toHardwareMatrixArray(uiPixels, reusableHardwareBuffer)
            if (_isToyServiceBound.value) {
                manager.setMatrixFrame(hardwarePixels)
            } else {
                try {
                    manager.setAppMatrixFrame(hardwarePixels)
                } catch (_: Throwable) {
                    manager.setMatrixFrame(hardwarePixels)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pushFrameToHardware error: ${t.message}")
        }
    }

    private fun setHardwareTimeoutDisabled(disableTimeout: Boolean) {
        hardwareScope.launch {
            setHardwareTimeoutDisabledInternal(disableTimeout)
        }
    }

    private fun setHardwareTimeoutDisabledInternal(disableTimeout: Boolean) {
        if (!_isHardwareConnected.value) return
        try {
            glyphManager?.setGlyphMatrixTimeout(disableTimeout)
        } catch (_: Throwable) {
        }
    }

    private fun isDeviceLockedOrScreenOff(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isInteractive = pm?.isInteractive ?: true
        val isLocked = km?.isKeyguardLocked ?: false
        return !isInteractive || isLocked
    }

    private fun acquireWakeLock() {
        try {
            releaseWakeLock()
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GlyphOracle::VoiceSessionWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
            wl.acquire(60_000L)
            wakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) {
                wl.release()
            }
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    private fun stopSpeechRecognizerOnly() {
        speechWatchdogJob?.cancel()
        speechWatchdogJob = null
        val oldRecognizer = speechRecognizer
        speechRecognizer = null
        if (oldRecognizer != null) {
            mainHandler.post {
                try {
                    oldRecognizer.cancel()
                } catch (_: Throwable) {
                }
                try {
                    oldRecognizer.destroy()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun stopListeningInternal() {
        lockScreenRecorder?.stopCancel()
        lockScreenRecorder = null
        stopSpeechRecognizerOnly()
    }

    private fun vibrateShortTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun mapSpeechError(error: Int, isEnglish: Boolean): String = if (isEnglish) {
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Grant microphone permission in app"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during STT"
            SpeechRecognizer.ERROR_NO_MATCH -> "Could not recognize question. Speak clearer."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (no voice heard)"
            else -> "Speech error (code $error)"
        }
    } else {
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи аудио с микрофона"
            SpeechRecognizer.ERROR_CLIENT -> "Клиентская ошибка распознавателя речи"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Откройте приложение и разрешите доступ к микрофону (RECORD_AUDIO)"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Ошибка сети при распознавании речи"
            SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось распознать вопрос. Повторите четче."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель речи занят"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Время ожидания речи истекло (не услышан голос)"
            else -> "Ошибка распознавания речи (код $error)"
        }
    }

    companion object {
        private const val TAG = "GlyphOracleCoordinator"

        @Volatile
        private var INSTANCE: GlyphOracleCoordinator? = null

        fun getInstance(context: Context): GlyphOracleCoordinator {
            return INSTANCE ?: synchronized(this) {
                val instance = GlyphOracleCoordinator(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
