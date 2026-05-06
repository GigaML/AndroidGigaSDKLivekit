package com.gigaml.android.sample

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gigaml.android.api.AppConfigResponse
import com.gigaml.android.api.GigaApiClient
import com.gigaml.android.api.GigaApiClientConfig
import com.gigaml.android.api.emptyInitializationValues
import com.gigaml.android.api.parseInitializationValues
import com.gigaml.android.api.shortIdentifier
import com.gigaml.android.chat.GigaChatSessionManager
import com.gigaml.android.chat.GigaChatSessionState
import com.gigaml.android.model.TranscriptEntry
import com.gigaml.android.voice.GigaVoiceSessionManager
import com.gigaml.android.voice.VoiceConnectionState
import com.gigaml.android.voice.VoiceSessionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    SampleApp(applicationContext)
                }
            }
        }
    }
}

private enum class SampleScreen {
    Launcher,
    Voice,
    Chat,
}

@Composable
private fun SampleApp(
    appContext: Context,
) {
    val coroutineScope = rememberCoroutineScope()
    val defaultServerUrl = remember { "http://10.0.2.2:8787" }

    var baseUrl by rememberSaveable { mutableStateOf(defaultServerUrl) }
    var composerValue by rememberSaveable { mutableStateOf("") }
    var initializationJson by rememberSaveable { mutableStateOf("{}") }
    var launcherError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVoiceLaunch by rememberSaveable { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(SampleScreen.Launcher) }
    var config by remember { mutableStateOf<AppConfigResponse?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var isConfigLoading by remember { mutableStateOf(true) }

    val initializationValues = remember(initializationJson) {
        parseInitializationValues(initializationJson) ?: emptyInitializationValues()
    }
    val hasValidInitialization = remember(initializationJson) {
        parseInitializationValues(initializationJson) != null
    }
    val apiClient = remember(baseUrl) {
        GigaApiClient(
            GigaApiClientConfig(
                baseUrl = baseUrl.trim().ifBlank { defaultServerUrl },
            ),
        )
    }
    val voiceSessionManager = remember(apiClient) {
        GigaVoiceSessionManager(appContext, apiClient)
    }
    val chatSessionManager = remember(apiClient) {
        GigaChatSessionManager(apiClient)
    }
    val voiceState by voiceSessionManager.state.collectAsStateWithLifecycle()
    val chatState by chatSessionManager.state.collectAsStateWithLifecycle()

    DisposableEffect(voiceSessionManager, chatSessionManager) {
        onDispose {
            voiceSessionManager.close()
            chatSessionManager.close()
        }
    }

    LaunchedEffect(apiClient) {
        isConfigLoading = true
        configError = null
        config = null

        runCatching { apiClient.fetchConfig() }
            .onSuccess { config = it }
            .onFailure {
                configError = it.message ?: "Failed to load app config."
            }

        isConfigLoading = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            pendingVoiceLaunch = false
            launcherError = "Microphone permission was denied."
            return@rememberLauncherForActivityResult
        }

        if (!pendingVoiceLaunch) {
            return@rememberLauncherForActivityResult
        }

        pendingVoiceLaunch = false
        coroutineScope.launch {
            if (voiceSessionManager.start(initializationValues)) {
                launcherError = null
                screen = SampleScreen.Voice
            } else {
                launcherError = voiceSessionManager.state.value.error
            }
        }
    }

    when (screen) {
        SampleScreen.Launcher -> LauncherScreen(
            agentLabel = buildAgentLabel(config, isConfigLoading),
            baseUrl = baseUrl,
            configError = configError,
            hasValidInitialization = hasValidInitialization,
            initializationJson = initializationJson,
            isConfigLoading = isConfigLoading,
            launcherError = launcherError,
            onBaseUrlChange = {
                baseUrl = it
                launcherError = null
            },
            onInitializationJsonChange = {
                initializationJson = it
                launcherError = null
            },
            onStartChat = {
                if (!hasValidInitialization) {
                    launcherError = "Initialization values must be valid JSON."
                } else {
                    coroutineScope.launch {
                        if (chatSessionManager.start(initializationValues)) {
                            launcherError = null
                            screen = SampleScreen.Chat
                        } else {
                            launcherError = chatSessionManager.state.value.error
                        }
                    }
                }
            },
            onStartVoice = {
                if (!hasValidInitialization) {
                    launcherError = "Initialization values must be valid JSON."
                } else {
                    launcherError = null
                    if (voiceSessionManager.hasMicrophonePermission()) {
                        coroutineScope.launch {
                            if (voiceSessionManager.start(initializationValues)) {
                                screen = SampleScreen.Voice
                            } else {
                                launcherError = voiceSessionManager.state.value.error
                            }
                        }
                    } else {
                        pendingVoiceLaunch = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            voiceEnabled = !isConfigLoading && config?.supports?.voice != false,
            chatEnabled = !isConfigLoading && config?.supports?.chat != false,
        )

        SampleScreen.Voice -> VoiceScreen(
            state = voiceState,
            onBack = {
                voiceSessionManager.stop()
                screen = SampleScreen.Launcher
            },
            onToggleMute = {
                coroutineScope.launch {
                    voiceSessionManager.toggleMicrophone()
                }
            },
        )

        SampleScreen.Chat -> ChatScreen(
            composerValue = composerValue,
            state = chatState,
            onBack = {
                coroutineScope.launch {
                    chatSessionManager.end()
                    composerValue = ""
                    screen = SampleScreen.Launcher
                }
            },
            onComposerValueChange = { composerValue = it },
            onSend = {
                coroutineScope.launch {
                    if (chatSessionManager.send(composerValue)) {
                        composerValue = ""
                    }
                }
            },
        )
    }
}

@Composable
private fun LauncherScreen(
    baseUrl: String,
    initializationJson: String,
    hasValidInitialization: Boolean,
    isConfigLoading: Boolean,
    voiceEnabled: Boolean,
    chatEnabled: Boolean,
    agentLabel: String,
    launcherError: String?,
    configError: String?,
    onBaseUrlChange: (String) -> Unit,
    onInitializationJsonChange: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStartChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Android Giga LiveKit Sample",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = agentLabel,
            style = MaterialTheme.typography.bodyLarge,
        )

        if (isConfigLoading) {
            LoadingCard("Loading config")
        }

        if (launcherError != null) {
            ErrorCard(launcherError)
        }

        if (configError != null) {
            ErrorCard(configError)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backend base URL") },
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    label = { Text("Initialization JSON") },
                    value = initializationJson,
                    onValueChange = onInitializationJsonChange,
                    supportingText = {
                        Text(
                            if (hasValidInitialization) {
                                "Valid JSON object."
                            } else {
                                "Enter a valid JSON object."
                            },
                        )
                    },
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = voiceEnabled,
            onClick = onStartVoice,
        ) {
            Text("Start voice")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = chatEnabled,
            onClick = onStartChat,
        ) {
            Text("Start chat")
        }

        Text(
            text = "Default emulator URL is http://10.0.2.2:8787",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun VoiceScreen(
    state: VoiceSessionState,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Voice session",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = when (state.connectionState) {
                VoiceConnectionState.CONNECTED -> "Connected"
                VoiceConnectionState.CONNECTING -> "Connecting"
                VoiceConnectionState.IDLE -> "Idle"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        val voiceError = state.error
        if (voiceError != null) {
            ErrorCard(voiceError)
        }

        TranscriptCard(
            emptyState = "Start speaking. LiveKit transcriptions will appear here.",
            transcript = state.transcript,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onToggleMute,
            ) {
                Text(if (state.isMicrophoneEnabled) "Mute" else "Unmute")
            }

            Button(
                modifier = Modifier.weight(1f),
                onClick = onBack,
            ) {
                Text("End call")
            }
        }
    }
}

@Composable
private fun ChatScreen(
    state: GigaChatSessionState,
    composerValue: String,
    onComposerValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Chat session",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        val chatError = state.error
        if (chatError != null) {
            ErrorCard(chatError)
        }

        TranscriptCard(
            emptyState = if (state.isStarting) {
                "Starting chat session..."
            } else {
                "No messages yet."
            },
            transcript = state.transcript,
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isStarting && !state.isSending && !state.isClosing,
            label = { Text("Message") },
            value = composerValue,
            onValueChange = onComposerValueChange,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = composerValue.isNotBlank() && !state.isSending,
                onClick = onSend,
            ) {
                Text(if (state.isSending) "Sending..." else "Send")
            }

            TextButton(
                modifier = Modifier.weight(1f),
                onClick = onBack,
            ) {
                Text(if (state.isClosing) "Ending..." else "End session")
            }
        }
    }
}

@Composable
private fun ColumnScope.TranscriptCard(
    transcript: List<TranscriptEntry>,
    emptyState: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = true),
    ) {
        if (transcript.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyState,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                transcript.forEach { entry ->
                    val displayText = buildDisplayText(entry)
                    Surface(
                        color = if (entry.role == TranscriptEntry.Role.USER) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (entry.role == TranscriptEntry.Role.USER) "You" else "Agent",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (entry.imageUrls.isNotEmpty() || displayText.isNotBlank()) {
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                            entry.imageUrls.forEach { imageUrl ->
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = "Chat image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                                if (displayText.isNotBlank() || imageUrl != entry.imageUrls.last()) {
                                    Spacer(modifier = Modifier.size(8.dp))
                                }
                            }
                            if (displayText.isNotBlank()) {
                                Text(displayText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun LoadingCard(title: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(title)
        }
    }
}

private fun buildAgentLabel(
    config: AppConfigResponse?,
    isConfigLoading: Boolean,
): String =
    when {
        config?.defaultAgentId != null -> "Agent ${shortIdentifier(config.defaultAgentId)}"
        config?.defaultAgentTemplateId != null -> {
            "Template ${shortIdentifier(config.defaultAgentTemplateId)}"
        }
        isConfigLoading -> "Loading configured agent"
        else -> "No configured agent"
    }

private fun buildDisplayText(entry: TranscriptEntry): String {
    val imageMarkupRegex = Regex("""\[IMG\]<\s*https?://[^>]+>\[/IMG\]""", RegexOption.IGNORE_CASE)
    val markdownImageRegex = Regex("""!\[[^\]]*]\(\s*https?://[^)]+\)""", RegexOption.IGNORE_CASE)
    val emptyMarkdownImageRegex = Regex("""!\[[^\]]*]\(\s*\)""", RegexOption.IGNORE_CASE)

    var result = entry.text
        .replace(imageMarkupRegex, "")
        .replace(markdownImageRegex, "")
        .replace(emptyMarkdownImageRegex, "")
    entry.imageUrls.forEach { imageUrl ->
        result = result.replace(imageUrl, "")
    }

    return result
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
