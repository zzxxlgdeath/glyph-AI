package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AiProvider
import com.example.data.OracleSettings
import com.example.data.POPULAR_OPENAI_COMPATIBLE_ENDPOINTS
import com.example.data.SttProvider
import com.example.data.VoiceCaptureMode
import com.example.ui.theme.NothingAmber
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingCard
import com.example.ui.theme.NothingCardElevated
import com.example.ui.theme.NothingGray
import com.example.ui.theme.NothingGreen
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingWhite
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    settings: OracleSettings,
    apiDiagnosticMessage: String?,
    isFetchingModels: Boolean,
    onSelectProvider: (AiProvider) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateCustomBaseUrl: (String) -> Unit,
    onApplyEndpointPreset: (String, String) -> Unit,
    onAddCustomModel: (String) -> Unit,
    onRemoveCustomModel: (String) -> Unit,
    onFetchModelsFromApi: () -> Unit,
    onTestApiConnection: () -> Unit,
    onUpdateApiKey: (AiProvider, String) -> Unit,
    onClearAllApiKeys: () -> Unit,
    onUpdateSystemPrompt: (String) -> Unit,
    onResetSystemPrompt: () -> Unit,
    onUpdateMaxWords: (Int) -> Unit,
    onUpdateBrightness: (Int) -> Unit,
    onUpdateScrollSpeed: (Int) -> Unit,
    onUpdateSilenceTimeout: (Int) -> Unit,
    onUpdateUiLanguage: (String) -> Unit = {},
    onUpdateSpeechLanguage: (String) -> Unit,
    onUpdateSttProvider: (SttProvider) -> Unit = {},
    onUpdateVoiceCaptureMode: (VoiceCaptureMode) -> Unit,
    onUpdateBackgroundServiceEnabled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var newCustomModelText by rememberSaveable { mutableStateOf("") }
    var modelSearchQuery by rememberSaveable { mutableStateOf("") }
    var showAllModels by rememberSaveable { mutableStateOf(false) }

    var showResetPromptConfirm by rememberSaveable { mutableStateOf(false) }
    var showClearKeysConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingModelToRemove by rememberSaveable { mutableStateOf<String?>(null) }

    val isEn = settings.isEnglish
    val activeProvider = settings.selectedProvider
    val currentCustomKey = settings.getRawApiKeyInput(activeProvider)
    val resolvedKey = settings.getResolvedApiKey(activeProvider)
    val hasActiveKey = resolvedKey.isNotBlank()
    val rawModelInput = settings.getRawModelInput(activeProvider)
    val customModelsForProvider = remember(
        activeProvider,
        settings.customGroqModels,
        settings.customGeminiModels,
        settings.customOpenRouterModels,
        settings.customOpenAiModels,
        settings.customDeepSeekModels,
        settings.customCompatibleModels
    ) {
        settings.getCustomModelsForProvider(activeProvider)
    }
    val fetchedModelsForProvider = remember(
        activeProvider,
        settings.fetchedGroqModels,
        settings.fetchedGeminiModels,
        settings.fetchedOpenRouterModels,
        settings.fetchedOpenAiModels,
        settings.fetchedDeepSeekModels,
        settings.fetchedCompatibleModels
    ) {
        settings.getFetchedModelsForProvider(activeProvider)
    }
    val allAvailableModels = remember(
        activeProvider,
        rawModelInput,
        customModelsForProvider,
        fetchedModelsForProvider
    ) {
        settings.getAllModelsForProvider(activeProvider)
    }
    val hasAnySavedKey = remember(
        settings.customGroqKey,
        settings.customGeminiKey,
        settings.customOpenRouterKey,
        settings.customOpenAiKey,
        settings.customDeepSeekKey,
        settings.customCompatibleKey
    ) {
        AiProvider.entries.any { settings.getResolvedApiKey(it).isNotBlank() }
    }

    val filteredModels = remember(allAvailableModels, modelSearchQuery) {
        val q = modelSearchQuery.trim()
        if (q.isEmpty()) {
            allAvailableModels
        } else {
            allAvailableModels.filter { it.contains(q, ignoreCase = true) }
        }
    }
    val displayedModels = remember(filteredModels, showAllModels) {
        if (showAllModels || filteredModels.size <= 24) filteredModels else filteredModels.take(24)
    }

    if (showResetPromptConfirm) {
        NothingConfirmationDialog(
            title = if (isEn) "RESET SYSTEM PROMPT?" else "СБРОСИТЬ ПРОМПТ?",
            message = if (isEn) {
                "Restore the default concise system instruction for the AI?"
            } else {
                "Восстановить стандартный системный промпт по умолчанию?"
            },
            confirmLabel = if (isEn) "RESET" else "СБРОСИТЬ",
            cancelLabel = if (isEn) "CANCEL" else "ОТМЕНА",
            onConfirm = {
                showResetPromptConfirm = false
                onResetSystemPrompt()
            },
            onDismiss = { showResetPromptConfirm = false }
        )
    }

    if (showClearKeysConfirm) {
        NothingConfirmationDialog(
            title = if (isEn) "CLEAR ALL API KEYS?" else "УДАЛИТЬ ВСЕ КЛЮЧИ?",
            message = if (isEn) {
                "All saved API keys for all providers will be removed from this device."
            } else {
                "Все сохраненные API-ключи для всех провайдеров будут удалены с этого устройства."
            },
            confirmLabel = if (isEn) "CLEAR KEYS" else "УДАЛИТЬ",
            cancelLabel = if (isEn) "CANCEL" else "ОТМЕНА",
            onConfirm = {
                showClearKeysConfirm = false
                onClearAllApiKeys()
            },
            onDismiss = { showClearKeysConfirm = false }
        )
    }

    val modelToDelete = pendingModelToRemove
    if (modelToDelete != null) {
        NothingConfirmationDialog(
            title = if (isEn) "REMOVE MODEL?" else "УДАЛИТЬ МОДЕЛЬ?",
            message = if (isEn) {
                "Remove «$modelToDelete» from your saved models list?"
            } else {
                "Удалить модель «$modelToDelete» из сохраненного списка?"
            },
            confirmLabel = if (isEn) "REMOVE" else "УДАЛИТЬ",
            cancelLabel = if (isEn) "CANCEL" else "ОТМЕНА",
            onConfirm = {
                pendingModelToRemove = null
                onRemoveCustomModel(modelToDelete)
            },
            onDismiss = { pendingModelToRemove = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // =========================================================================
            // CARD 1: LANGUAGE & AI PROVIDER
            // =========================================================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = NothingCard),
                border = BorderStroke(1.dp, NothingBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. UI Language Selector
                    Text(
                        text = if (isEn) "Interface Language" else "Язык интерфейса",
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LanguageOptionChip(
                            label = "Русский",
                            isSelected = !isEn,
                            onClick = { onUpdateUiLanguage("ru") },
                            testTag = "lang_chip_ru-RU",
                            modifier = Modifier.weight(1f)
                        )
                        LanguageOptionChip(
                            label = "English",
                            isSelected = isEn,
                            onClick = { onUpdateUiLanguage("en") },
                            testTag = "lang_chip_en-US",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Speech Recognition Language (Auto / RU / EN)
                    Text(
                        text = if (isEn) "Voice Recognition Language" else "Язык распознавания речи",
                        style = MaterialTheme.typography.titleSmall,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val speechLang = settings.speechLanguage
                        LanguageOptionChip(
                            label = if (isEn) "Auto" else "Авто",
                            isSelected = speechLang.equals("auto", ignoreCase = true),
                            onClick = { onUpdateSpeechLanguage("auto") },
                            testTag = "speech_lang_auto",
                            modifier = Modifier.weight(1f)
                        )
                        LanguageOptionChip(
                            label = "RU",
                            isSelected = speechLang.startsWith("ru", ignoreCase = true),
                            onClick = { onUpdateSpeechLanguage("ru-RU") },
                            testTag = "speech_lang_ru",
                            modifier = Modifier.weight(1f)
                        )
                        LanguageOptionChip(
                            label = "EN",
                            isSelected = speechLang.startsWith("en", ignoreCase = true),
                            onClick = { onUpdateSpeechLanguage("en-US") },
                            testTag = "speech_lang_en",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(
                        color = NothingBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // 2. AI Provider Grid
                    Text(
                        text = if (isEn) "AI Provider & Model" else "AI-провайдер и модель",
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val providerRows = AiProvider.entries.chunked(3)
                        providerRows.forEach { rowProviders ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowProviders.forEach { provider ->
                                    val isSelected = provider == activeProvider
                                    val label = if (provider == AiProvider.CUSTOM_OPENAI && isEn) {
                                        "OpenAI-Comp."
                                    } else {
                                        provider.displayName
                                    }
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { onSelectProvider(provider) }
                                            .testTag("provider_tab_${provider.name.lowercase()}"),
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) NothingWhite else NothingCardElevated,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) NothingWhite else NothingBorder
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    NothingWhite
                                                },
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. API Key Input
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val providerDisplay = if (activeProvider == AiProvider.CUSTOM_OPENAI && isEn) {
                                "OpenAI-Comp."
                            } else {
                                activeProvider.displayName
                            }
                            Text(
                                text = if (isEn) {
                                    "API Key ($providerDisplay)"
                                } else {
                                    "API-ключ ($providerDisplay)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = NothingWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (hasActiveKey) Icons.Filled.CheckCircle else Icons.Filled.Key,
                                    contentDescription = null,
                                    tint = if (hasActiveKey) NothingGreen else NothingAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = when {
                                        hasActiveKey && isEn -> "Encrypted (Keystore)"
                                        hasActiveKey -> "Зашифрован (Keystore)"
                                        isEn -> "No key"
                                        else -> "Нет ключа"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (hasActiveKey) NothingGreen else NothingAmber,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        OutlinedTextField(
                            value = currentCustomKey,
                            onValueChange = { onUpdateApiKey(activeProvider, it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .testTag("api_key_input"),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = if (isEn) {
                                        "Paste your ${activeProvider.displayName} API key"
                                    } else {
                                        "Вставьте API-ключ ${activeProvider.displayName}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NothingGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (isEn) "Show or hide API key" else "Показать или скрыть API-ключ"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NothingWhite,
                                unfocusedBorderColor = NothingBorder,
                                focusedContainerColor = NothingCardElevated,
                                unfocusedContainerColor = NothingCardElevated
                            )
                        )
                    }

                    // 4. Custom Base URL field when CUSTOM_OPENAI is selected
                    if (activeProvider == AiProvider.CUSTOM_OPENAI) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (isEn) {
                                    "OpenAI-Compatible Base URL"
                                } else {
                                    "Base URL совместимого сервера"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = NothingWhite,
                                maxLines = 1,
                                softWrap = false
                            )

                            OutlinedTextField(
                                value = settings.customOpenAiBaseUrl,
                                onValueChange = onUpdateCustomBaseUrl,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .testTag("custom_base_url_input"),
                                singleLine = true,
                                placeholder = {
                                    Text("https://api.mistral.ai/v1", color = NothingGray)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NothingWhite,
                                    unfocusedBorderColor = NothingBorder,
                                    focusedContainerColor = NothingCardElevated,
                                    unfocusedContainerColor = NothingCardElevated
                                )
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                POPULAR_OPENAI_COMPATIBLE_ENDPOINTS.forEach { preset ->
                                    val isCurrentEndpoint = settings.customOpenAiBaseUrl.trim()
                                        .equals(preset.baseUrl, ignoreCase = true)
                                    Surface(
                                        modifier = Modifier
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                onApplyEndpointPreset(preset.baseUrl, preset.sampleModel)
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isCurrentEndpoint) NothingRed else NothingCardElevated,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isCurrentEndpoint) NothingRed else NothingBorder
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = preset.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = NothingWhite,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Active Model Input & Custom Model Row
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isEn) "Active Model" else "Активная модель",
                            style = MaterialTheme.typography.titleSmall,
                            color = NothingWhite,
                            maxLines = 1,
                            softWrap = false
                        )

                        OutlinedTextField(
                            value = rawModelInput,
                            onValueChange = onUpdateModel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .testTag("model_name_input"),
                            singleLine = true,
                            placeholder = { Text(activeProvider.defaultModel, color = NothingGray) },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NothingWhite,
                                unfocusedBorderColor = NothingBorder,
                                focusedContainerColor = NothingCardElevated,
                                unfocusedContainerColor = NothingCardElevated
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newCustomModelText,
                                onValueChange = { newCustomModelText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp)
                                    .testTag("add_custom_model_input"),
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        text = if (isEn) "Add custom model..." else "Своя модель в список...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NothingGray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NothingWhite,
                                    unfocusedBorderColor = NothingBorder,
                                    focusedContainerColor = NothingCardElevated,
                                    unfocusedContainerColor = NothingCardElevated
                                )
                            )

                            Button(
                                onClick = {
                                    if (newCustomModelText.isNotBlank()) {
                                        onAddCustomModel(newCustomModelText)
                                        newCustomModelText = ""
                                    }
                                },
                                enabled = newCustomModelText.isNotBlank(),
                                modifier = Modifier
                                    .height(56.dp)
                                    .testTag("add_custom_model_button"),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NothingWhite,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = NothingCardElevated,
                                    disabledContentColor = NothingGray
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Add custom model",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isEn) "ADD" else "ДОБАВИТЬ",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    // 6. Action Buttons: Refresh Models from API & Test API
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val canFetch = (hasActiveKey || activeProvider == AiProvider.OPENROUTER) && !isFetchingModels
                        OutlinedButton(
                            onClick = onFetchModelsFromApi,
                            enabled = canFetch,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("fetch_models_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, NothingBorder)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = "Refresh models",
                                tint = if (canFetch) NothingWhite else NothingGray,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    isFetchingModels && isEn -> "SYNCING..."
                                    isFetchingModels -> "ОБНОВЛЕНИЕ..."
                                    isEn -> "REFRESH MODELS"
                                    else -> "ОБНОВИТЬ МОДЕЛИ"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 11.sp,
                                    letterSpacing = 0.2.sp
                                ),
                                color = if (canFetch) NothingWhite else NothingGray,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onTestApiConnection,
                            enabled = hasActiveKey,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("test_api_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NothingRed,
                                contentColor = NothingWhite,
                                disabledContainerColor = NothingCardElevated,
                                disabledContentColor = NothingGray
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NetworkCheck,
                                contentDescription = "Test API",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isEn) "TEST API" else "ТЕСТ API",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 11.sp,
                                    letterSpacing = 0.2.sp
                                ),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!hasActiveKey) {
                        Text(
                            text = if (isEn) {
                                "Enter an API key above — the model list will automatically sync with the server."
                            } else {
                                "После ввода API-ключа список актуальных моделей подгрузится с сервера автоматически."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = NothingGray
                        )
                    }

                    if (!apiDiagnosticMessage.isNullOrBlank()) {
                        val isError = apiDiagnosticMessage.startsWith("ОШИБКА") ||
                            apiDiagnosticMessage.startsWith("ERROR") ||
                            apiDiagnosticMessage.contains("Ошибка") ||
                            apiDiagnosticMessage.contains("Error")
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = NothingCardElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isError) NothingRed else NothingGreen
                            )
                        ) {
                            Text(
                                text = apiDiagnosticMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isError) NothingRed else NothingGreen,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // 7. Models Chips, Search Filter & Clear Saved Keys
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (isEn) "Available Models" else "Доступные модели",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NothingWhite,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val syncSubtitle = when {
                                    isFetchingModels && isEn -> "Auto-updating from API..."
                                    isFetchingModels -> "Автоподгрузка актуального списка из API..."
                                    fetchedModelsForProvider.isNotEmpty() && isEn ->
                                        "Live from API • ${fetchedModelsForProvider.size} models"
                                    fetchedModelsForProvider.isNotEmpty() ->
                                        "Актуальные из API • ${fetchedModelsForProvider.size} шт."
                                    isEn -> "Presets • ${allAvailableModels.size} models"
                                    else -> "Пресеты • ${allAvailableModels.size} шт."
                                }
                                Text(
                                    text = syncSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (fetchedModelsForProvider.isNotEmpty()) NothingGreen else NothingGray,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (hasAnySavedKey) {
                                OutlinedButton(
                                    onClick = { showClearKeysConfirm = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, NothingBorder)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = null,
                                        tint = NothingGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isEn) "CLEAR KEYS" else "СБРОС КЛЮЧЕЙ",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NothingWhite,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (allAvailableModels.size > 8) {
                            OutlinedTextField(
                                value = modelSearchQuery,
                                onValueChange = { modelSearchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("model_search_input"),
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = NothingGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = if (modelSearchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { modelSearchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Filled.Clear,
                                                contentDescription = if (isEn) "Clear search" else "Очистить поиск",
                                                tint = NothingGray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                } else null,
                                placeholder = {
                                    Text(
                                        text = if (isEn) "Filter models..." else "Поиск по списку моделей...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NothingGray
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NothingWhite,
                                    unfocusedBorderColor = NothingBorder,
                                    focusedContainerColor = NothingCardElevated,
                                    unfocusedContainerColor = NothingCardElevated
                                )
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            displayedModels.forEach { modelItem ->
                                val isCurrent = settings.getActiveModel(activeProvider) == modelItem
                                val isUserCustom = customModelsForProvider.contains(modelItem)
                                Surface(
                                    modifier = Modifier
                                        .heightIn(min = 36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onUpdateModel(modelItem) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isCurrent) NothingRed else NothingCardElevated,
                                    border = BorderStroke(1.dp, if (isCurrent) NothingRed else NothingBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            start = 12.dp,
                                            end = if (isUserCustom) 8.dp else 12.dp,
                                            top = 8.dp,
                                            bottom = 8.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = modelItem,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NothingWhite,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        if (isUserCustom) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = if (isEn) "Remove model" else "Удалить модель",
                                                tint = NothingWhite.copy(alpha = 0.75f),
                                                modifier = Modifier
                                                    .size(15.dp)
                                                    .clickable { pendingModelToRemove = modelItem }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (filteredModels.size > 24) {
                            TextButton(
                                onClick = { showAllModels = !showAllModels },
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                Text(
                                    text = when {
                                        showAllModels && isEn -> "SHOW FEWER MODELS"
                                        showAllModels -> "СВЕРНУТЬ СПИСОК"
                                        isEn -> "SHOW ALL (${filteredModels.size})"
                                        else -> "ПОКАЗАТЬ ВСЕ (${filteredModels.size})"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NothingWhite
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // CARD 2: GENERAL PARAMETERS (PROMPT, VOICE, STT & MATRIX)
            // =========================================================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = NothingCard),
                border = BorderStroke(1.dp, NothingBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = if (isEn) "General Parameters" else "Общие параметры",
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Section A: Clean System Prompt Editor (regulates AI response behavior & length)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = NothingCardElevated,
                        border = BorderStroke(1.dp, NothingBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = if (isEn) "System Prompt" else "Системный промпт",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = NothingWhite,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isEn) {
                                            "Controls the style and word count of the AI response"
                                        } else {
                                            "Регулирует формат и количество слов в ответе нейросети"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NothingGray
                                    )
                                }

                                OutlinedButton(
                                    onClick = { showResetPromptConfirm = true },
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("reset_prompt_button"),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, NothingBorder)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Reset prompt",
                                        tint = NothingWhite,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isEn) "RESET" else "СБРОС",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NothingWhite,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = settings.systemPrompt,
                                onValueChange = onUpdateSystemPrompt,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("system_prompt_input"),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = NothingWhite,
                                    lineHeight = 20.sp
                                ),
                                minLines = 3,
                                maxLines = 7,
                                placeholder = {
                                    Text(
                                        text = if (isEn) {
                                            OracleSettings.DEFAULT_SYSTEM_PROMPT_EN
                                        } else {
                                            OracleSettings.DEFAULT_SYSTEM_PROMPT_RU
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NothingGray
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NothingWhite,
                                    unfocusedBorderColor = NothingBorder,
                                    focusedContainerColor = NothingCard,
                                    unfocusedContainerColor = NothingCard
                                )
                            )
                        }
                    }

                    // Section A2: Post-Processing Matrix Word Limit (independent of prompt)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("max_words_slider"),
                        shape = RoundedCornerShape(16.dp),
                        color = NothingCardElevated,
                        border = BorderStroke(1.dp, NothingBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = if (isEn) {
                                            "Matrix Word Output Limit"
                                        } else {
                                            "Лимит вывода слов на матрицу"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = NothingWhite,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isEn) {
                                            "Truncates the received answer for the LED matrix only (independent of prompt)"
                                        } else {
                                            "Ограничивает вывод на матрицу уже после получения ответа (не зависит от промпта)"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NothingGray
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = NothingCard,
                                    border = BorderStroke(1.dp, NothingBorder)
                                ) {
                                    val limit = settings.maxWordsLimit
                                    val badgeText = if (isEn) {
                                        if (limit == 1) "MAX 1 WORD" else "MAX $limit WORDS"
                                    } else {
                                        when (limit) {
                                            1 -> "ДО 1 СЛОВА"
                                            else -> "ДО $limit СЛОВ"
                                        }
                                    }
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NothingWhite,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                (1..5).forEach { wordCount ->
                                    val isSelected = settings.maxWordsLimit == wordCount
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onUpdateMaxWords(wordCount) }
                                            .testTag("max_words_chip_$wordCount"),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) NothingRed else NothingCard,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) NothingRed else NothingBorder
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$wordCount",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = NothingWhite,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = NothingBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Section B: Voice Capture, STT Provider & Pause Window
                    Text(
                        text = if (isEn) "Voice Capture & STT Provider" else "Запись голоса и STT-провайдер",
                        style = MaterialTheme.typography.titleSmall,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SttProvider.entries.forEach { stt ->
                            val isSelected = settings.sttProvider == stt
                            Surface(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onUpdateSttProvider(stt) }
                                    .testTag("stt_provider_${stt.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) NothingWhite else NothingCardElevated,
                                border = BorderStroke(1.dp, if (isSelected) NothingWhite else NothingBorder)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stt.getLabel(isEn),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else NothingWhite,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    SettingSliderBlock(
                        label = if (isEn) "Pause window for auto-send" else "Окно паузы для автоотправки",
                        valueFormatter = { v ->
                            if (isEn) {
                                "${String.format("%.2f", v.roundToInt() / 1000f)} s"
                            } else {
                                "${String.format("%.2f", v.roundToInt() / 1000f)} с"
                            }
                        },
                        value = settings.silenceTimeoutMs.toFloat(),
                        onValueChangeCommitted = { onUpdateSilenceTimeout(it.roundToInt()) },
                        valueRange = 600f..2000f,
                        activeColor = NothingRed,
                        sliderTestTag = "silence_timeout_slider"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceCaptureMode.entries.forEach { mode ->
                            val isSelected = settings.voiceCaptureMode == mode
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onUpdateVoiceCaptureMode(mode) }
                                    .testTag("voice_mode_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(14.dp),
                                color = NothingCardElevated,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) NothingWhite else NothingBorder
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { onUpdateVoiceCaptureMode(mode) },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = NothingRed,
                                            unselectedColor = NothingGray
                                        )
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = mode.getTitle(isEn),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = NothingWhite
                                        )
                                        Text(
                                            text = mode.getSubtitle(isEn),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NothingGray
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = NothingBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Section C: Matrix LED Brightness & Scroll Speed
                    Text(
                        text = if (isEn) "Matrix Display" else "Экран матрицы",
                        style = MaterialTheme.typography.titleSmall,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false
                    )

                    SettingSliderBlock(
                        label = if (isEn) "Matrix LED brightness" else "Яркость светодиодов матрицы",
                        valueFormatter = { "${it.roundToInt()} / 255" },
                        value = settings.matrixBrightness.toFloat(),
                        onValueChangeCommitted = { onUpdateBrightness(it.roundToInt()) },
                        valueRange = 40f..255f,
                        activeColor = NothingWhite
                    )

                    SettingSliderBlock(
                        label = if (isEn) "Marquee scroll speed" else "Скорость бегущей строки",
                        valueFormatter = { v ->
                            if (isEn) "${v.roundToInt()} ms" else "${v.roundToInt()} мс"
                        },
                        value = settings.scrollSpeedMs.toFloat(),
                        onValueChangeCommitted = { onUpdateScrollSpeed(it.roundToInt()) },
                        valueRange = 35f..160f,
                        activeColor = NothingWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LanguageOptionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) NothingWhite else NothingCardElevated,
        border = BorderStroke(1.dp, if (isSelected) NothingWhite else NothingBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else NothingWhite,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingSliderBlock(
    label: String,
    valueFormatter: (Float) -> String,
    value: Float,
    onValueChangeCommitted: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    activeColor: androidx.compose.ui.graphics.Color,
    steps: Int = 0,
    sliderTestTag: String? = null
) {
    var localValue by remember(value) { mutableFloatStateOf(value) }
    LaunchedEffect(value) {
        localValue = value
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = NothingWhite,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = NothingCardElevated,
                border = BorderStroke(1.dp, NothingBorder)
            ) {
                Text(
                    text = valueFormatter(localValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingWhite,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Slider(
            value = localValue,
            onValueChange = { localValue = it },
            onValueChangeFinished = { onValueChangeCommitted(localValue) },
            valueRange = valueRange,
            steps = steps,
            modifier = if (sliderTestTag != null) Modifier.testTag(sliderTestTag) else Modifier,
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor
            )
        )
    }
}

@Composable
fun NothingConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    isDestructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NothingCardElevated,
        titleContentColor = NothingWhite,
        textContentColor = NothingGray,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NothingWhite
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NothingGray
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) NothingRed else NothingWhite,
                    contentColor = if (isDestructive) NothingWhite else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(42.dp)
            ) {
                Text(
                    text = cancelLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingWhite,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    )
}
