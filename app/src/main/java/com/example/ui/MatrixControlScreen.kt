package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.OracleSettings
import com.example.glyph.ToyOracleState
import com.example.ui.theme.NothingAmber
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingCard
import com.example.ui.theme.NothingCardElevated
import com.example.ui.theme.NothingGray
import com.example.ui.theme.NothingGreen
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingWhite
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MatrixControlScreen(
    oracleState: ToyOracleState,
    matrixFrameFlow: StateFlow<IntArray>,
    settings: OracleSettings,
    isHardwareConnected: Boolean,
    isToyServiceBound: Boolean,
    onGlyphButtonTrigger: () -> Unit,
    onStopListening: () -> Unit,
    onSubmitTextQuestion: (String) -> Unit,
    onReplayAnswer: (String, String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var manualQuestion by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val isListening = oracleState is ToyOracleState.Listening
    val isEn = settings.isEnglish
    val hasApiKey = settings.hasActiveApiKey

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HardwareStatusStrip(
                settings = settings,
                isHardwareConnected = isHardwareConnected,
                isToyServiceBound = isToyServiceBound,
                onOpenAiSettings = onOpenAiSettings
            )

            if (!hasApiKey) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("missing_api_key_banner"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = NothingCardElevated),
                    border = BorderStroke(1.dp, NothingAmber)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = NothingAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (isEn) {
                                        "API Key Required (${settings.selectedProvider.displayName})"
                                    } else {
                                        "Нужен API-ключ (${settings.selectedProvider.displayName})"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NothingWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isEn) {
                                        "Set a key in settings to unlock voice & text queries"
                                    } else {
                                        "Укажите ключ в настройках для голосовых и текстовых запросов"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NothingGray
                                )
                            }
                        }

                        Button(
                            onClick = onOpenAiSettings,
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("go_to_api_key_button"),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NothingAmber,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = if (isEn) "ENTER KEY" else "ВВЕСТИ КЛЮЧ",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("matrix_monitor_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = NothingCard),
                border = BorderStroke(1.dp, NothingBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GLYPH MATRIX • 25×25",
                            style = MaterialTheme.typography.titleMedium,
                            color = NothingWhite,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        StatusPill(oracleState = oracleState, isEnglish = isEn)
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    GlyphMatrix25x25Visualizer(
                        matrixFrameFlow = matrixFrameFlow,
                        isListening = isListening,
                        modifier = Modifier.size(252.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    OracleReadoutPanel(
                        oracleState = oracleState,
                        hasApiKey = hasApiKey,
                        providerName = settings.selectedProvider.displayName,
                        isEnglish = isEn,
                        onReplayAnswer = onReplayAnswer,
                        onDismissError = onDismissError
                    )
                }
            }

            Button(
                onClick = {
                    if (isListening) onStopListening() else onGlyphButtonTrigger()
                },
                enabled = hasApiKey || isListening,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag("glyph_voice_trigger_button"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) NothingRed else NothingWhite,
                    contentColor = if (isListening) NothingWhite else MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = NothingCardElevated,
                    disabledContentColor = NothingGray
                )
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (isListening) "Stop recording" else "Hold Glyph Button and ask voice question",
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        isListening && isEn -> "SPEAK NOW • TAP TO SEND"
                        isListening -> "ГОВОРИТЕ • НАЖМИТЕ ДЛЯ ОТПРАВКИ"
                        !hasApiKey && isEn -> "ENTER API KEY TO ASK BY VOICE"
                        !hasApiKey -> "ВВЕДИТЕ API-КЛЮЧ ДЛЯ ЗАПИСИ ГОЛОСА"
                        isEn -> "ASK BY VOICE • GLYPH BUTTON"
                        else -> "ЗАДАТЬ ВОПРОС ГОЛОСОМ"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NothingCard),
                border = BorderStroke(1.dp, NothingBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (isEn) "Text Question" else "Текстовый запрос",
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = manualQuestion,
                            onValueChange = { manualQuestion = it },
                            enabled = hasApiKey,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .testTag("manual_question_input"),
                            placeholder = {
                                Text(
                                    text = when {
                                        !hasApiKey && isEn -> "Set API key in settings first..."
                                        !hasApiKey -> "Сначала укажите API-ключ в настройках..."
                                        isEn -> "Type a question for the AI..."
                                        else -> "Введите вопрос для нейросети..."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NothingGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = if (manualQuestion.isNotEmpty() && hasApiKey) {
                                {
                                    IconButton(onClick = { manualQuestion = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Clear,
                                            contentDescription = if (isEn) "Clear text" else "Очистить поле",
                                            tint = NothingGray
                                        )
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NothingWhite,
                                unfocusedBorderColor = NothingBorder,
                                disabledBorderColor = NothingBorder,
                                focusedContainerColor = NothingCardElevated,
                                unfocusedContainerColor = NothingCardElevated,
                                disabledContainerColor = NothingCardElevated
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (hasApiKey && manualQuestion.isNotBlank()) {
                                        onSubmitTextQuestion(manualQuestion)
                                        focusManager.clearFocus()
                                    }
                                }
                            )
                        )

                        val canSendText = hasApiKey && manualQuestion.isNotBlank()
                        IconButton(
                            onClick = {
                                if (canSendText) {
                                    onSubmitTextQuestion(manualQuestion)
                                    focusManager.clearFocus()
                                }
                            },
                            enabled = canSendText,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (canSendText) NothingWhite else NothingCardElevated)
                                .testTag("send_question_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (isEn) "Send question to AI" else "Отправить вопрос в нейросеть",
                                tint = if (canSendText) MaterialTheme.colorScheme.onPrimary else NothingGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareStatusStrip(
    settings: OracleSettings,
    isHardwareConnected: Boolean,
    isToyServiceBound: Boolean,
    onOpenAiSettings: () -> Unit
) {
    val hasKey = settings.hasActiveApiKey
    val isEn = settings.isEnglish

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(62.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenAiSettings() },
            shape = RoundedCornerShape(16.dp),
            color = NothingCard,
            border = BorderStroke(1.dp, NothingBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (hasKey) NothingGreen else NothingAmber)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = settings.selectedProvider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = settings.getActiveModel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingGray,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = if (isEn) "Configure AI provider" else "Настроить провайдера",
                    tint = NothingGray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .height(62.dp),
            shape = RoundedCornerShape(16.dp),
            color = NothingCard,
            border = BorderStroke(1.dp, NothingBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isToyServiceBound -> NothingRed
                                isHardwareConnected -> NothingGreen
                                else -> NothingAmber
                            }
                        )
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Glyph Toy",
                        style = MaterialTheme.typography.titleSmall,
                        color = NothingWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            isToyServiceBound && isEn -> "TOY ACTIVE"
                            isToyServiceBound -> "ТОЙ АКТИВЕН"
                            isHardwareConnected && isEn -> "MATRIX CONNECTED"
                            isHardwareConnected -> "МАТРИЦА ПОДКЛЮЧЕНА"
                            isEn -> "SIMULATOR + SERVICE"
                            else -> "СИМУЛЯТОР + СЕРВИС"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingGray,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(oracleState: ToyOracleState, isEnglish: Boolean) {
    val (label, dotColor) = when (oracleState) {
        is ToyOracleState.Idle ->
            (if (isEnglish) "READY" else "ГОТОВ") to NothingGray
        is ToyOracleState.Listening ->
            (if (isEnglish) "REC • AUTO" else "ЗАПИСЬ • АВТО") to NothingRed
        is ToyOracleState.Thinking ->
            (if (isEnglish) "AI THINKING" else "ЗАПРОС К ИИ") to NothingAmber
        is ToyOracleState.DisplayingAnswer ->
            when {
                oracleState.isStatic && isEnglish -> "STATIC"
                oracleState.isStatic -> "СТАТИКА"
                isEnglish -> "MARQUEE"
                else -> "БЕГУЩАЯ СТРОКА"
            } to NothingGreen
        is ToyOracleState.Error ->
            (if (isEnglish) "ERROR" else "ОШИБКА") to NothingRed
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = NothingCardElevated,
        border = BorderStroke(1.dp, NothingBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = NothingWhite,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun OracleReadoutPanel(
    oracleState: ToyOracleState,
    hasApiKey: Boolean,
    providerName: String,
    isEnglish: Boolean,
    onReplayAnswer: (String, String) -> Unit,
    onDismissError: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("oracle_readout_panel"),
        shape = RoundedCornerShape(16.dp),
        color = NothingCardElevated,
        border = BorderStroke(
            1.dp,
            if (oracleState is ToyOracleState.Error) NothingRed else NothingBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (oracleState) {
                is ToyOracleState.Idle -> {
                    Text(
                        text = when {
                            !hasApiKey && isEnglish -> "API Key Not Set"
                            !hasApiKey -> "Ключ не задан"
                            isEnglish -> "Ready for Question"
                            else -> "Готов к вопросу"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hasApiKey) NothingWhite else NothingAmber,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = when {
                            !hasApiKey && isEnglish ->
                                "Add your $providerName API key in the settings tab to start asking voice or text questions."
                            !hasApiKey ->
                                "Добавьте API-ключ для $providerName во вкладке настроек, чтобы задавать вопросы голосом или текстом."
                            isEnglish ->
                                "Hold the Glyph Button on the back of Nothing Phone (3) or tap the voice button below, speak your question, and pause."
                            else ->
                                "Зажмите кнопку Glyph Button на корпусе телефона или нажмите кнопку ниже, произнесите вопрос и сделайте короткую паузу."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NothingGray,
                        textAlign = TextAlign.Center
                    )
                }

                is ToyOracleState.Listening -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = NothingRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isEnglish) {
                                "Listening • Auto-send on pause"
                            } else {
                                "Слушаю • Автоотправка при паузе"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = NothingRed,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = oracleState.partialText.ifBlank {
                            if (isEnglish) "Speak your question aloud..." else "Произнесите вопрос вслух..."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = NothingWhite,
                        textAlign = TextAlign.Center
                    )
                }

                is ToyOracleState.Thinking -> {
                    Text(
                        text = "«${oracleState.question}»",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NothingGray,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (isEnglish) {
                            "Waiting for ${oracleState.providerName}..."
                        } else {
                            "Ожидание ответа ${oracleState.providerName}..."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingAmber,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                is ToyOracleState.DisplayingAnswer -> {
                    Text(
                        text = "«${oracleState.question}»",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NothingGray,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = oracleState.answer,
                        style = MaterialTheme.typography.displayLarge,
                        color = NothingWhite,
                        textAlign = TextAlign.Center
                    )
                    val modeDesc = when {
                        oracleState.isStatic && isEnglish -> "Static"
                        oracleState.isStatic -> "Статика"
                        isEnglish -> "Marquee"
                        else -> "Бегущая строка"
                    }
                    val latencyDesc = if (oracleState.latencyMs > 0) {
                        if (isEnglish) " • ${oracleState.latencyMs} ms" else " • ${oracleState.latencyMs} мс"
                    } else ""
                    Text(
                        text = "${oracleState.providerName} • $modeDesc$latencyDesc",
                        style = MaterialTheme.typography.labelMedium,
                        color = NothingGreen,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Surface(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onReplayAnswer(oracleState.question, oracleState.answer) },
                        shape = RoundedCornerShape(10.dp),
                        color = NothingCard,
                        border = BorderStroke(1.dp, NothingBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Replay,
                                contentDescription = null,
                                tint = NothingWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isEnglish) "REPLAY ON MATRIX" else "ПОВТОРИТЬ НА МАТРИЦЕ",
                                style = MaterialTheme.typography.labelMedium,
                                color = NothingWhite,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                is ToyOracleState.Error -> {
                    Text(
                        text = if (isEnglish) "Error" else "Ошибка",
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingRed,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = oracleState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NothingWhite,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onDismissError,
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("dismiss_error_button"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NothingBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = NothingWhite,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEnglish) "DISMISS" else "ЗАКРЫТЬ",
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
