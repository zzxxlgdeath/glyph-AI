package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glyph.GlyphOracleCoordinator
import com.example.glyph.GlyphOracleToyService
import com.example.ui.AiSettingsScreen
import com.example.ui.HistoryScreen
import com.example.ui.MatrixControlScreen
import com.example.ui.OracleViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NothingBlack
import com.example.ui.theme.NothingGray
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingSurface
import com.example.ui.theme.NothingWhite
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            MyApplicationTheme {
                GlyphOracleApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        GlyphOracleToyService.syncBackgroundServiceState(applicationContext)
        GlyphOracleCoordinator.getInstance(applicationContext).onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        GlyphOracleCoordinator.getInstance(applicationContext).onAppBackground()
    }
}

@Composable
fun GlyphOracleApp(
    viewModel: OracleViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val oracleState by viewModel.oracleState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val isHardwareConnected by viewModel.isHardwareConnected.collectAsStateWithLifecycle()
    val isToyServiceBound by viewModel.isToyServiceBound.collectAsStateWithLifecycle()
    val apiDiagnosticMessage by viewModel.apiDiagnosticMessage.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()

    val isEn = settings.isEnglish
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    val initialPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            GlyphOracleToyService.syncBackgroundServiceState(context)
        }
    }

    LaunchedEffect(Unit) {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMicPerm) {
            GlyphOracleToyService.syncBackgroundServiceState(context)
        } else {
            initialPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            GlyphOracleToyService.syncBackgroundServiceState(context)
            viewModel.triggerGlyphLongPress()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (isEn) {
                        "Microphone permission is required for Glyph Button voice questions."
                    } else {
                        "Для голосового вопроса по кнопке Glyph Button необходимо разрешение на микрофон."
                    }
                )
            }
        }
    }

    val handleVoiceTrigger = {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMicPerm) {
            GlyphOracleToyService.syncBackgroundServiceState(context)
            viewModel.triggerGlyphLongPress()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = NothingBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            NothingTopBar()
        },
        bottomBar = {
            NavigationBar(
                containerColor = NothingSurface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("nav_tab_matrix"),
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Filled.GridOn else Icons.Outlined.GridOn,
                            contentDescription = if (isEn) "Matrix" else "Матрица"
                        )
                    },
                    label = {
                        Text(
                            text = if (isEn) "MATRIX" else "МАТРИЦА",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NothingBlack,
                        selectedTextColor = NothingWhite,
                        indicatorColor = NothingWhite,
                        unselectedIconColor = NothingGray,
                        unselectedTextColor = NothingGray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("nav_tab_ai_settings"),
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 1) Icons.Filled.Tune else Icons.Outlined.Tune,
                            contentDescription = if (isEn) "Settings" else "Настройки"
                        )
                    },
                    label = {
                        Text(
                            text = if (isEn) "SETTINGS" else "НАСТРОЙКИ",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NothingBlack,
                        selectedTextColor = NothingWhite,
                        indicatorColor = NothingWhite,
                        unselectedIconColor = NothingGray,
                        unselectedTextColor = NothingGray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("nav_tab_history"),
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 2) Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = if (isEn) "Question history" else "История вопросов"
                        )
                    },
                    label = {
                        Text(
                            text = if (isEn) "HISTORY (${history.size})" else "ИСТОРИЯ (${history.size})",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NothingBlack,
                        selectedTextColor = NothingWhite,
                        indicatorColor = NothingWhite,
                        unselectedIconColor = NothingGray,
                        unselectedTextColor = NothingGray
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> MatrixControlScreen(
                    oracleState = oracleState,
                    matrixFrameFlow = viewModel.matrixFrame,
                    settings = settings,
                    isHardwareConnected = isHardwareConnected,
                    isToyServiceBound = isToyServiceBound,
                    onGlyphButtonTrigger = handleVoiceTrigger,
                    onStopListening = { viewModel.stopVoiceListening() },
                    onSubmitTextQuestion = { viewModel.submitTextQuestion(it) },
                    onReplayAnswer = { q, a -> viewModel.previewAnswerDirectly(q, a) },
                    onOpenAiSettings = { selectedTab = 1 },
                    onDismissError = { viewModel.dismissError() }
                )

                1 -> AiSettingsScreen(
                    settings = settings,
                    apiDiagnosticMessage = apiDiagnosticMessage,
                    isFetchingModels = isFetchingModels,
                    onSelectProvider = { viewModel.selectProvider(it) },
                    onUpdateModel = { viewModel.updateActiveModel(it) },
                    onUpdateCustomBaseUrl = { viewModel.updateCustomOpenAiBaseUrl(it) },
                    onApplyEndpointPreset = { url, model ->
                        viewModel.applyOpenAiEndpointPreset(url, model)
                    },
                    onAddCustomModel = { viewModel.addCustomModel(it) },
                    onRemoveCustomModel = { viewModel.removeCustomModel(it) },
                    onFetchModelsFromApi = { viewModel.fetchModelsFromApi() },
                    onTestApiConnection = { viewModel.testActiveProviderConnection() },
                    onUpdateApiKey = { provider, key -> viewModel.updateCustomApiKey(provider, key) },
                    onClearAllApiKeys = { viewModel.clearAllApiKeys() },
                    onUpdateSystemPrompt = { viewModel.updateSystemPrompt(it) },
                    onResetSystemPrompt = { viewModel.resetSystemPrompt() },
                    onUpdateMaxWords = { viewModel.updateMaxWordsLimit(it) },
                    onUpdateBrightness = { viewModel.updateMatrixBrightness(it) },
                    onUpdateScrollSpeed = { viewModel.updateScrollSpeedMs(it) },
                    onUpdateSilenceTimeout = { viewModel.updateSilenceTimeoutMs(it) },
                    onUpdateUiLanguage = { viewModel.updateUiLanguage(it) },
                    onUpdateSpeechLanguage = { viewModel.updateSpeechLanguage(it) },
                    onUpdateSttProvider = { viewModel.updateSttProvider(it) },
                    onUpdateVoiceCaptureMode = { viewModel.updateVoiceCaptureMode(it) },
                    onUpdateBackgroundServiceEnabled = { viewModel.updateBackgroundServiceEnabled(it) }
                )

                2 -> HistoryScreen(
                    history = history,
                    isEnglish = isEn,
                    onReplayOnMatrix = { item ->
                        viewModel.replayOnMatrix(item)
                        selectedTab = 0
                    },
                    onDeleteItem = { viewModel.deleteHistoryItem(it) },
                    onClearAll = { viewModel.clearAllHistory() }
                )
            }
        }
    }
}

@Composable
private fun NothingTopBar() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 6.dp),
        color = NothingBlack
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(NothingRed)
            )
            Column {
                Text(
                    text = "GLYPH ORACLE",
                    style = MaterialTheme.typography.titleLarge,
                    color = NothingWhite,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "NOTHING PHONE (3) • GLYPH TOY",
                    style = MaterialTheme.typography.labelSmall,
                    color = NothingGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
