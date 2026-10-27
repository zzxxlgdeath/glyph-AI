# План реализации: Выбранные улучшения `AiOracleClient`, поддержка Gemma и исправление кнопки «ОБНОВИТЬ МОДЕЛИ»

План сфокусирован строго на выбранных вами улучшениях (без добавления кастомного Base URL и прокси-настроек для Gemini).

---

## 1. Фильтрация `thinking`-блоков (`extractGeminiResponseText` в `AiOracleClient.kt`)
- Добавить функцию `extractGeminiResponseText(root: JsonObject)`:
  - Перебирает массив `candidates[0].content.parts`.
  - Отфильтровывает части с `"thought": true`, чтобы внутренние рассуждения моделей Gemini 2.5 / 3.0 не попадали в итоговый ответ.
  - Если остаётся одна текстовая часть без флага `thought`, использует её как безопасный фоллбэк.
- Применить `extractGeminiResponseText(root)` во всех методах чтения ответа Gemini:
  - `callGeminiRestApi`
  - `askGeminiAudioSingleTrip`
  - `transcribeViaGeminiAudio`

---

## 2. Параметры `thinkingLevel = "low"`, `thinkingBudget = 0` и увеличение лимита токенов (`maxOutputTokens` / `max_tokens`)
- Внедрить конфигурацию генерации `putGeminiGenerationConfig(model, baseMaxTokens, temperature)` из корневой версии `AiOracleClient.kt` в сочетании с увеличенным лимитом для Pro/Thinking моделей:
  - Для моделей с поддержкой уровня рассуждений (`gemini-3*`, `geminiUsesThinkingLevel(model)`): устанавливать `thinkingConfig: { "thinkingLevel": "low" }` и `maxOutputTokens = maxOf(baseMaxTokens, 256)`.
  - Для моделей `pro` / `thinking`: увеличивать `maxOutputTokens` до `1024`, чтобы рассуждения никогда не обрезали финальный короткий ответ.
  - Для остальных моделей `flash`: отключать лишнюю задержку на рассуждения через `thinkingConfig: { "thinkingBudget": 0 }` и задавать увеличенный `baseMaxTokens` (`64`–`256` вместо прежних `28`–`35`).
- В `callOpenCompatibleChatApi` и `transcribeViaGroqWhisper`:
  - Увеличить `max_tokens` со слишком узких `24`/`35` до `64`.
  - Расширить проверку `isReasoningModel` (`r1`, `reasoner`, `o1`, `o3`, `o4`, `qwen3`, `2.5-pro`, `thinking`), чтобы для reasoning-моделей не передавался жёсткий низкий `max_tokens`.

---

## 3. Поддержка моделей Gemma и расширение пресетов (`AiOracleClient.kt`, `OracleSettingsRepository.kt`)
- В `OracleSettingsRepository.kt`:
  - Добавить `gemma-3-27b-it` (а также актуальные `gemini-2.5-flash-lite`, `gemini-2.0-flash-lite`, `gemini-pro-latest`) в базовый список пресетов `AiProvider.GEMINI.presetModels`.
- В `AiOracleClient.kt` (`callGeminiRestApi`):
  - Определять модели Gemma: `val isGemmaModel = cleanModel.startsWith("gemma", ignoreCase = true)`.
  - Так как модели Gemma в Google API `/v1beta` не поддерживают корневое поле `systemInstruction` (возвращают HTTP 400), для них пропускать блок `systemInstruction`, а системную инструкцию встраивать внутрь пользовательского сообщения (`"$systemInstruction\n\nQuestion: $question"`) с указанием `"role": "user"`.

---

## 4. Умная фильтрация и расширенный список в `fetchAvailableModels` (`AiOracleClient.kt`)
- **Для `AiProvider.GEMINI`**:
  - Запрашивать `GET $cleanBase/models?pageSize=100&key=$cleanKey`.
  - Проверять массив `supportedGenerationMethods`: оставлять только те модели, где список методов отсутствует или содержит `"generateContent"`.
  - Разрешать модели `gemini*` и `gemma*`, исключая специализированные не-текстовые модели: `embedding`, `aqa`, `imagen`, `veo`, `tts`, `vision`, `audio`, `robotics`, `computer-use`, `learnlm`.
- **Для OpenAI-совместимых провайдеров (`GROQ`, `OPENROUTER`, `OPENAI`, `DEEPSEEK`, `CUSTOM_OPENAI`)**:
  - Исключать не-чат модели (`whisper`, `guard`, `tts`, `embedding`, `moderation`, `dall-e`, `audio`, `realtime`, `transcribe`, `search`, `babbage`, `davinci`, `canary`, `orpheus`, `playai`).
  - Для `OpenRouter` увеличить выборку до 60 актуальных моделей.

---

## 5. Исправление кнопки «ОБНОВИТЬ МОДЕЛИ» на русском языке (`AiSettingsScreen.kt`)
- Уменьшить горизонтальные отступы кнопок `fetch_models_button` и `test_api_button` до `8.dp`, размер иконки — до `15.dp`, отступ между иконкой и текстом — до `4.dp`, а шрифт — до `11.sp` (`letterSpacing = 0.2.sp`), чтобы текст **«ОБНОВИТЬ МОДЕЛИ»** целиком помещался на кнопке без многоточия.
