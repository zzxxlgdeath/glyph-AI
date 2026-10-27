package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.OracleHistoryItem
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingCard
import com.example.ui.theme.NothingCardElevated
import com.example.ui.theme.NothingGray
import com.example.ui.theme.NothingGreen
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    history: List<OracleHistoryItem>,
    isEnglish: Boolean = false,
    onReplayOnMatrix: (OracleHistoryItem) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearAllConfirmDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<OracleHistoryItem?>(null) }
    val dateFormatter = remember {
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    }

    if (showClearAllConfirmDialog) {
        NothingConfirmationDialog(
            title = if (isEnglish) "CLEAR ALL HISTORY?" else "ОЧИСТИТЬ ИСТОРИЮ?",
            message = if (isEnglish) {
                "All saved questions and answers (${history.size}) will be permanently removed from the device."
            } else {
                "Все сохранённые вопросы и ответы (${history.size} шт.) будут безвозвратно удалены из журнала."
            },
            confirmLabel = if (isEnglish) "CLEAR ALL" else "ОЧИСТИТЬ ВСЁ",
            cancelLabel = if (isEnglish) "CANCEL" else "ОТМЕНА",
            isDestructive = true,
            onConfirm = {
                onClearAll()
                showClearAllConfirmDialog = false
            },
            onDismiss = { showClearAllConfirmDialog = false }
        )
    }

    itemToDelete?.let { targetItem ->
        NothingConfirmationDialog(
            title = if (isEnglish) "DELETE ENTRY?" else "УДАЛИТЬ ЗАПИСЬ?",
            message = if (isEnglish) {
                "Delete question «${targetItem.question}» (${targetItem.answer}) from history?"
            } else {
                "Удалить вопрос «${targetItem.question}» (ответ: ${targetItem.answer}) из истории?"
            },
            confirmLabel = if (isEnglish) "DELETE" else "УДАЛИТЬ",
            cancelLabel = if (isEnglish) "CANCEL" else "ОТМЕНА",
            isDestructive = true,
            onConfirm = {
                onDeleteItem(targetItem.id)
                itemToDelete = null
            },
            onDismiss = { itemToDelete = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (isEnglish) "Question & Answer Log" else "Журнал вопросов и ответов",
                        style = MaterialTheme.typography.titleMedium,
                        color = NothingWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isEnglish) "Total entries: ${history.size}" else "Всего записей: ${history.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NothingGray
                    )
                }

                if (history.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearAllConfirmDialog = true },
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("clear_history_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NothingBorder),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = if (isEnglish) "Clear all history" else "Очистить всю историю",
                            tint = NothingRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEnglish) "CLEAR ALL" else "ОЧИСТИТЬ",
                            style = MaterialTheme.typography.labelMedium,
                            color = NothingWhite,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (history.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .testTag("empty_history_state"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = NothingCard),
                    border = BorderStroke(1.dp, NothingBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = NothingGray,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (isEnglish) "History is Empty" else "История пуста",
                            style = MaterialTheme.typography.titleLarge,
                            color = NothingWhite
                        )
                        Text(
                            text = if (isEnglish) {
                                "Ask a voice question via the Glyph Button or on the main screen — all questions and ultra-short answers are saved here so you can replay them on the matrix."
                            } else {
                                "Задайте голосовой вопрос через кнопку Glyph Button или на главном экране — все вопросы и ультра-краткие ответы сохранятся здесь с возможностью повторного вывода на матрицу."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = NothingGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("history_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        val formattedDate = remember(item.timestamp) {
                            dateFormatter.format(Date(item.timestamp))
                        }
                        HistoryItemCard(
                            item = item,
                            formattedDate = formattedDate,
                            isEnglish = isEnglish,
                            onReplay = { onReplayOnMatrix(item) },
                            onDelete = { itemToDelete = item }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItemCard(
    item: OracleHistoryItem,
    formattedDate: String,
    isEnglish: Boolean,
    onReplay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_${item.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NothingCard),
        border = BorderStroke(1.dp, NothingBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = NothingCardElevated,
                    border = BorderStroke(1.dp, NothingBorder)
                ) {
                    val msUnit = if (isEnglish) "ms" else "мс"
                    Text(
                        text = "${item.provider} • ${item.model} • ${item.latencyMs} $msUnit",
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingGray,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Text(
                text = "«${item.question}»",
                style = MaterialTheme.typography.titleSmall,
                color = NothingWhite
            )

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
                        text = if (isEnglish) "ANSWER:" else "ОТВЕТ:",
                        style = MaterialTheme.typography.labelMedium,
                        color = NothingGray,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = item.answer,
                        style = MaterialTheme.typography.headlineMedium,
                        color = NothingWhite
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onReplay,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("replay_matrix_button_${item.id}"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NothingWhite,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = if (isEnglish) "Replay on matrix" else "Повторить вывод на матрицу",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isEnglish) "TO MATRIX" else "НА МАТРИЦУ",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = NothingCardElevated,
                        border = BorderStroke(1.dp, NothingBorder),
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("delete_history_button_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = if (isEnglish) "Delete entry" else "Удалить запись",
                                tint = NothingGray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
