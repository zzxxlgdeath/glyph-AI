package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AiProvider
import com.example.data.OracleSettings
import com.example.data.OracleSettingsRepository
import com.example.data.isActiveSupportedTextModel
import com.example.glyph.PixelMatrixRenderer
import com.example.network.AiOracleClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Glyph Oracle", appName)
    }

    @Test
    fun `fetched models replace base preset list while preserving custom models`() {
        val settings = OracleSettings(
            selectedProvider = AiProvider.OPENAI,
            customOpenAiModels = listOf("my-custom-gpt"),
            fetchedOpenAiModels = listOf("gpt-4.1-mini", "gpt-4o-mini")
        )
        val allModels = settings.getAllModelsForProvider(AiProvider.OPENAI)
        assertEquals(listOf("my-custom-gpt", "gpt-4.1-mini", "gpt-4o-mini"), allModels)
    }

    @Test
    fun `isActiveSupportedTextModel filters retired models and allows active ones`() {
        assertTrue(isActiveSupportedTextModel("gemini-flash-latest", AiProvider.GEMINI))
        assertTrue(isActiveSupportedTextModel("gemini-flash-lite-latest", AiProvider.GEMINI))
        assertTrue(isActiveSupportedTextModel("gemini-3.5-flash", AiProvider.GEMINI))
        assertTrue(isActiveSupportedTextModel("llama-3.1-8b-instant", AiProvider.GROQ))

        assertFalse(isActiveSupportedTextModel("gemini-2.0-flash", AiProvider.GEMINI))
        assertFalse(isActiveSupportedTextModel("gemini-3.1-flash-lite-preview", AiProvider.GEMINI))
        assertFalse(isActiveSupportedTextModel("gemma2-9b-it", AiProvider.GROQ))
        assertFalse(isActiveSupportedTextModel("deepseek-r1-distill-llama-70b", AiProvider.GROQ))
        assertFalse(isActiveSupportedTextModel("qwen3-32b", AiProvider.GROQ))
    }

    @Test
    fun `getEffectiveSystemPrompt preserves custom prompt without maxWords injection and post-processes matrix words`() {
        val customPrompt = "Always answer like a pirate in 3 words."
        val settings = OracleSettings(
            maxWordsLimit = 2,
            systemPrompt = customPrompt,
            uiLanguage = "en"
        )
        val prompt = settings.getEffectiveSystemPrompt()
        assertEquals(customPrompt, prompt)

        val matrixOutput = AiOracleClient.sanitizeUltraShortAnswer(
            raw = "AHOY MATEY YES",
            maxWords = settings.maxWordsLimit,
            isEnglish = true
        )
        assertEquals("AHOY MATEY", matrixOutput)
    }

    @Test
    fun `sanitizeUltraShortAnswer strips think blocks and keeps concise line`() {
        val cleanedClosed = AiOracleClient.sanitizeUltraShortAnswer(
            raw = "<think>Calculating 2+2...</think> 4.",
            maxWords = 2,
            isEnglish = false
        )
        assertEquals("4", cleanedClosed)

        val cleanedUnclosed = AiOracleClient.sanitizeUltraShortAnswer(
            raw = "ДА <think>internal reasoning truncated",
            maxWords = 2,
            isEnglish = false
        )
        assertEquals("ДА", cleanedUnclosed)

        val multiLine = AiOracleClient.sanitizeUltraShortAnswer(
            raw = "Let me think about the capital of France carefully.\nPARIS",
            maxWords = 2,
            isEnglish = true
        )
        assertEquals("PARIS", multiLine)
    }

    @Test
    fun `parseSingleTripQa rejects truncated Q-only output and parses valid QA`() {
        val truncated = AiOracleClient.parseSingleTripQa(
            rawText = "Q: Какая сегодня погода в Москве"
        )
        assertNull(truncated)

        val valid = AiOracleClient.parseSingleTripQa(
            rawText = "Q: Сколько будет два плюс два\nA: 4"
        )
        assertEquals("Сколько будет два плюс два" to "4", valid)
    }

    @Test
    fun `normalizeForMatrix preserves Cyrillic Й and Ё while stripping Latin accents`() {
        val normalized = PixelMatrixRenderer.normalizeForMatrix("Йошкар-Ола Ёлка Café")
        assertEquals("ЙОШКАР-ОЛА ЁЛКА CAFE", normalized)
    }
}
