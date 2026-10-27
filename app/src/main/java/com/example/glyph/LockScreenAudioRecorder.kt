package com.example.glyph

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Direct hardware microphone recorder using `android.media.AudioRecord`.
 *
 * Speed & Sensitivity Optimizations:
 * - Uses `MediaRecorder.AudioSource.MIC` as primary source with hardware AGC (and without
 *   aggressive hardware NoiseSuppressor that suppresses off-axis speech when holding the phone
 *   with the rear Glyph Matrix facing the user).
 * - High-sensitivity VAD (`onsetThreshold` 32..110 RMS) so normal and quiet conversational speech
 *   is captured effortlessly without raising one's voice.
 * - Strict cancellation (`isCancelled` + `sessionId`) so `stopCancel()` never triggers completion callbacks.
 * - Top-of-loop deadline checks and negative `AudioRecord.read()` error handling (`ERROR_DEAD_OBJECT`, etc.)
 *   to prevent infinite loops on hardware disconnect.
 * - Trims leading and trailing silence while preserving generous pre-roll (~250ms) and post-roll (~280ms).
 */
class LockScreenAudioRecorder(
    private val scope: CoroutineScope,
    private val silenceTimeoutMs: Long = 1050L,
    private val isEnglish: Boolean = false,
    private val onRmsChanged: (Float) -> Unit,
    private val onRecordingComplete: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recordingJob: Job? = null
    private val activeSessionId = AtomicLong(0L)

    @Volatile
    private var shouldStopEarly: Boolean = false

    @Volatile
    private var isCancelled: Boolean = false

    val isRecording: Boolean
        get() = recordingJob?.isActive == true && !isCancelled

    @SuppressLint("MissingPermission")
    fun start() {
        stopCancel()
        shouldStopEarly = false
        isCancelled = false
        val sessionIdToken = activeSessionId.incrementAndGet()

        recordingJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(3200)
            val bufferSizeInBytes = minBufSize * 2

            var audioRecord: AudioRecord? = null
            var agc: AutomaticGainControl? = null

            var recordedPcmBytes: ByteArray? = null
            var firstVoiceBytePosition = 0
            var lastVoiceBytePosition = 0
            var speechStarted = false
            var maxPeakObserved = 0
            var maxRmsObserved = 0.0
            var errorMessage: String? = null

            try {
                audioRecord = createInitializedAudioRecord(
                    sampleRate = sampleRate,
                    channelConfig = channelConfig,
                    audioFormat = audioFormat,
                    bufferSizeInBytes = bufferSizeInBytes
                )

                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    errorMessage = if (isEnglish) {
                        "Failed to initialize microphone"
                    } else {
                        "Не удалось инициализировать микрофон"
                    }
                } else {
                    val audioSessionId = audioRecord.audioSessionId
                    try {
                        if (AutomaticGainControl.isAvailable()) {
                            agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                        }
                    } catch (_: Throwable) {
                    }

                    audioRecord.startRecording()
                    if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        errorMessage = if (isEnglish) {
                            "Microphone access blocked by OS. Open the app once."
                        } else {
                            "Запись с микрофона заблокирована системой. Откройте приложение один раз."
                        }
                    } else {
                        val pcmStream = ByteArrayOutputStream(sampleRate * 2 * 6)
                        val shortBuffer = ShortArray(512) // 32ms per chunk at 16kHz
                        val byteChunk = ByteArray(shortBuffer.size * 2)

                        val startTime = System.currentTimeMillis()
                        val effectiveSilenceWindow = silenceTimeoutMs.coerceIn(600L, 2000L)
                        var speechStartTime = 0L
                        var lastVoiceTimestamp = startTime
                        var noiseFloorRms = 28.0
                        var peakSpeechRms = 0.0

                        while (isActive && !shouldStopEarly && !isCancelled) {
                            val loopNow = System.currentTimeMillis()
                            val loopElapsed = loopNow - startTime

                            // Wait up to 5.5s for the user to start speaking
                            if (!speechStarted && loopElapsed > 5_500L) {
                                break
                            }
                            // Max active speech duration 9.0s after speech onset, or 12.5s overall safety cap
                            if (speechStarted && (loopNow - speechStartTime) > 9_000L) {
                                break
                            }
                            if (loopElapsed > 12_500L) {
                                break
                            }

                            val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                            if (readCount < 0) {
                                errorMessage = if (isEnglish) {
                                    "Microphone hardware read error (code $readCount)"
                                } else {
                                    "Ошибка чтения с микрофона (код $readCount)"
                                }
                                break
                            }
                            if (readCount == 0) {
                                delay(10L)
                                continue
                            }

                            val now = System.currentTimeMillis()
                            val elapsed = now - startTime

                            // Skip only the first 90ms so the initial 25ms haptic tick is excluded
                            // without clipping users who begin speaking immediately.
                            if (elapsed < 90L) {
                                continue
                            }

                            var sumSquares = 0.0
                            var chunkPeak = 0
                            for (i in 0 until readCount) {
                                val sample = shortBuffer[i].toInt()
                                val absSample = abs(sample)
                                if (absSample > chunkPeak) chunkPeak = absSample
                                sumSquares += sample.toDouble() * sample.toDouble()
                                byteChunk[i * 2] = (sample and 0xFF).toByte()
                                byteChunk[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                            }

                            val chunkStartBytePos = pcmStream.size()
                            pcmStream.write(byteChunk, 0, readCount * 2)

                            val rms = sqrt(sumSquares / readCount)
                            if (chunkPeak > maxPeakObserved) {
                                maxPeakObserved = chunkPeak
                            }
                            if (rms > maxRmsObserved) {
                                maxRmsObserved = rms
                            }

                            // Track ambient floor conservatively so user speech never inflates noiseFloorRms
                            if (rms < noiseFloorRms) {
                                noiseFloorRms = (noiseFloorRms * 0.72) + (rms * 0.28)
                            } else if (!speechStarted && rms < noiseFloorRms * 1.28) {
                                noiseFloorRms = (noiseFloorRms * 0.90) + (rms * 0.10)
                            } else if (speechStarted && rms < noiseFloorRms * 1.15) {
                                noiseFloorRms = (noiseFloorRms * 0.94) + (rms * 0.06)
                            }
                            noiseFloorRms = noiseFloorRms.coerceIn(12.0, 80.0)

                            val onsetThreshold = max(noiseFloorRms * 1.38, noiseFloorRms + 14.0)
                                .coerceIn(32.0, 110.0)

                            if (!speechStarted) {
                                if (rms > onsetThreshold || chunkPeak > 210) {
                                    speechStarted = true
                                    speechStartTime = now
                                    lastVoiceTimestamp = now
                                    peakSpeechRms = rms
                                    firstVoiceBytePosition = max(0, chunkStartBytePos - (readCount * 2))
                                    lastVoiceBytePosition = pcmStream.size()
                                }
                            } else {
                                if (rms > peakSpeechRms) {
                                    peakSpeechRms = rms
                                }
                                val sustainThreshold = max(
                                    noiseFloorRms * 1.25,
                                    min(peakSpeechRms * 0.20, 115.0)
                                ).coerceIn(26.0, 120.0)

                                if (rms > sustainThreshold || chunkPeak > 175) {
                                    lastVoiceTimestamp = now
                                    lastVoiceBytePosition = pcmStream.size()
                                }
                            }

                            val normalizedDb = if (rms > 8.0) {
                                ((20.0 * log10(rms / 45.0)).toFloat()).coerceIn(-2f, 10f)
                            } else {
                                -2f
                            }
                            if (!isCancelled && activeSessionId.get() == sessionIdToken) {
                                onRmsChanged(normalizedDb)
                            }

                            // Auto-stop once the user pauses for silenceTimeoutMs after speaking
                            if (
                                speechStarted &&
                                (now - lastVoiceTimestamp) > effectiveSilenceWindow &&
                                (now - speechStartTime) > 420L
                            ) {
                                break
                            }
                        }

                        try {
                            audioRecord.stop()
                        } catch (_: Throwable) {
                        }

                        recordedPcmBytes = pcmStream.toByteArray()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                errorMessage = if (isEnglish) {
                    "Audio record error: ${t.localizedMessage ?: "unknown"}"
                } else {
                    "Ошибка записи звука: ${t.localizedMessage ?: "неизвестная ошибка"}"
                }
            } finally {
                try {
                    agc?.release()
                } catch (_: Throwable) {
                }
                try {
                    audioRecord?.release()
                } catch (_: Throwable) {
                }
            }

            // Never invoke callbacks if the recording session was cancelled or superseded
            if (isCancelled || activeSessionId.get() != sessionIdToken) {
                return@launch
            }
            coroutineContext.ensureActive()

            if (errorMessage != null) {
                onError(errorMessage)
                return@launch
            }

            val rawPcm = recordedPcmBytes
            val hasSoftVoiceSignal = speechStarted || maxPeakObserved >= 115 || maxRmsObserved >= 28.0
            if (!hasSoftVoiceSignal || rawPcm == null || rawPcm.size < 3200 || maxPeakObserved < 65) {
                onError(
                    if (isEnglish) {
                        "No voice detected. Speak right after pressing the button."
                    } else {
                        "Голос не услышан. Произнесите вопрос после нажатия кнопки."
                    }
                )
                return@launch
            }

            // Trim leading silence (keep up to ~250ms = 8000 bytes pre-roll before voice onset)
            // and trailing silence (keep up to ~280ms = 9000 bytes after last voice frame),
            // aligned to 2-byte 16-bit PCM sample boundaries.
            val startByte = if (speechStarted && firstVoiceBytePosition > 8000) {
                ((firstVoiceBytePosition - 8000) / 2) * 2
            } else {
                0
            }
            val endByte = if (speechStarted && lastVoiceBytePosition > startByte + 3200) {
                min(rawPcm.size, ((lastVoiceBytePosition + 9000) / 2) * 2)
            } else {
                rawPcm.size
            }

            val trimmedPcm = if (startByte > 0 || endByte < rawPcm.size) {
                rawPcm.copyOfRange(startByte, endByte)
            } else {
                rawPcm
            }

            val normalizedPcm = normalizePcm16Bit(trimmedPcm, maxPeakObserved)
            val wavBytes = encodePcmToWav(normalizedPcm, sampleRate, 1, 16)

            if (!isCancelled && activeSessionId.get() == sessionIdToken && isActive) {
                onRecordingComplete(wavBytes)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int
    ): AudioRecord? {
        // MIC first for full omnidirectional sensitivity when looking at the rear Glyph Matrix
        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSizeInBytes
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record
                }
                record.release()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun normalizePcm16Bit(pcmBytes: ByteArray, observedPeak: Int): ByteArray {
        val peak = max(observedPeak, 1)
        if (peak >= 20_000) return pcmBytes

        // Boost quiet/off-axis speech up to 16x so Whisper and Gemini Audio receive a clear signal
        val gain = (24_000.0 / peak.toDouble()).coerceIn(1.0, 16.0)
        if (gain <= 1.05) return pcmBytes

        val out = ByteArray(pcmBytes.size)
        val sampleCount = pcmBytes.size / 2
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val boosted = (sample * gain).toInt().coerceIn(-32767, 32767)
            out[i * 2] = (boosted and 0xFF).toByte()
            out[i * 2 + 1] = ((boosted shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun finishEarly() {
        shouldStopEarly = true
    }

    fun stopCancel() {
        isCancelled = true
        shouldStopEarly = true
        activeSessionId.incrementAndGet()
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun encodePcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmData.size + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat 1 = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcmData, 0, out, 44, pcmData.size)
        return out
    }
}
