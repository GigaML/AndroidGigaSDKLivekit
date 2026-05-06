package com.gigaml.android.sample

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gigaml.android.api.AppConfigResponse
import com.gigaml.android.api.GigaApiClient
import com.gigaml.android.api.GigaApiClientConfig
import com.gigaml.android.api.InitializationOption
import com.gigaml.android.api.emptyInitializationValues
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
            MaterialTheme(
                colorScheme = SampleBrandColorScheme,
                typography = SampleBrandTypography,
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
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
    var launcherError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVoiceLaunch by rememberSaveable { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(SampleScreen.Launcher) }
    var config by remember { mutableStateOf<AppConfigResponse?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var isConfigLoading by remember { mutableStateOf(true) }

    val initializationOptions = remember(config?.initializationOptions) {
        buildInitializationOptions(config?.initializationOptions.orEmpty())
    }
    val initializationValues = remember(initializationOptions) {
        initializationOptions.first().values
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
    val isVoiceStarting = voiceState.isLoading || pendingVoiceLaunch
    val isChatStarting = chatState.isStarting

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

    fun launchVoiceSession() {
        coroutineScope.launch {
            if (voiceSessionManager.start(initializationValues)) {
                launcherError = null
                screen = SampleScreen.Voice
            } else {
                launcherError = voiceSessionManager.state.value.error
            }
        }
    }

    fun launchChatSession() {
        coroutineScope.launch {
            if (chatSessionManager.start(initializationValues)) {
                launcherError = null
                screen = SampleScreen.Chat
            } else {
                launcherError = chatSessionManager.state.value.error
            }
        }
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
        launchVoiceSession()
    }

    when (screen) {
        SampleScreen.Launcher -> LauncherScreen(
            configError = configError,
            isConfigLoading = isConfigLoading,
            isVoiceStarting = isVoiceStarting,
            isChatStarting = isChatStarting,
            launcherError = launcherError,
            onStartChat = {
                if (isChatStarting) {
                    launcherError = null
                } else {
                    launchChatSession()
                }
            },
            onStartVoice = {
                if (isVoiceStarting) {
                    launcherError = null
                } else {
                    launcherError = null
                    if (voiceSessionManager.hasMicrophonePermission()) {
                        launchVoiceSession()
                    } else {
                        pendingVoiceLaunch = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            voiceEnabled = !isConfigLoading && config?.supports?.voice != false && !isVoiceStarting,
            chatEnabled = !isConfigLoading && config?.supports?.chat != false && !isChatStarting,
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
    isConfigLoading: Boolean,
    isVoiceStarting: Boolean,
    isChatStarting: Boolean,
    voiceEnabled: Boolean,
    chatEnabled: Boolean,
    launcherError: String?,
    configError: String?,
    onStartVoice: () -> Unit,
    onStartChat: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HeroBackground()

        BrandWordmark(
            modifier = Modifier
                .align(Alignment.TopStart)
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroEyebrow(text = "AI AGENT FOR ENTERPRISE SUPPORT")
            Text(
                text = "AI that talks like a human.\nHandles millions of conversations.",
                style = MaterialTheme.typography.displaySmall,
                color = BrandSoftWhite,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Reach the agent instantly over voice or chat.",
                style = MaterialTheme.typography.bodyLarge,
                color = BrandSoftWhite.copy(alpha = 0.84f),
                textAlign = TextAlign.Center,
            )
            PrimaryActionButton(
                modifier = Modifier.fillMaxWidth(0.8f),
                label = if (isVoiceStarting) "Connecting call..." else CALL_US_LABEL,
                enabled = voiceEnabled,
                onClick = onStartVoice,
            )
            SecondaryActionButton(
                modifier = Modifier.fillMaxWidth(0.8f),
                label = if (isChatStarting) "Opening chat..." else CHAT_WITH_US_LABEL,
                enabled = chatEnabled,
                onClick = onStartChat,
            )
            if (isConfigLoading) {
                NoticeBanner("Connecting to backend...")
            }

            if (launcherError != null) {
                NoticeBanner(launcherError)
            }

            if (configError != null) {
                NoticeBanner(configError)
            }
        }
    }
}

@Composable
private fun HeroBackground() {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.giga_hero_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BrandHeroTopOverlay,
                            BrandHeroMiddleOverlay,
                            BrandHeroMiddleOverlay,
                            BrandHeroBottomOverlay,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun BrandWordmark(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.giga_orb_white),
            contentDescription = "Giga orb",
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Giga",
            style = MaterialTheme.typography.titleMedium,
            color = BrandSoftWhite,
        )
    }
}

@Composable
private fun HeroEyebrow(text: String) {
    Surface(
        color = BrandGlassColor,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, BrandGlassBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(BrandAccentColor),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = BrandSoftWhite.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun NoticeBanner(message: String) {
    Surface(
        color = BrandGlassColor,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BrandGlassBorderColor),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = BrandSoftWhite.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun VoiceScreen(
    state: VoiceSessionState,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HeroBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandWordmark()
            HeroEyebrow(text = "VOICE EXPERIENCE")
            Text(
                text = "Call with Us",
                style = MaterialTheme.typography.headlineLarge,
                color = BrandSoftWhite,
            )
            Text(
                text = "Stay in the same Giga experience while your live conversation unfolds below.",
                style = MaterialTheme.typography.bodyLarge,
                color = BrandSoftWhite.copy(alpha = 0.84f),
            )
            MonochromeCard {
                Text(
                    text = "Call status",
                    style = MaterialTheme.typography.labelLarge,
                    color = BrandSoftWhite.copy(alpha = 0.86f),
                )
                Text(
                    text = voiceConnectionLabel(state.connectionState),
                    style = MaterialTheme.typography.titleLarge,
                    color = BrandSoftWhite,
                )
                Text(
                    text = if (state.isMicrophoneEnabled) {
                        "Microphone is on and ready for a natural conversation."
                    } else {
                        "Microphone is muted right now. Unmute whenever you are ready."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val voiceError = state.error
            if (voiceError != null) {
                NoticeBanner(voiceError)
            }

            TranscriptCard(
                emptyState = "Start speaking. LiveKit transcriptions will appear here.",
                transcript = state.transcript,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryActionButton(
                    modifier = Modifier.weight(1f),
                    label = if (state.isMicrophoneEnabled) "Mute" else "Unmute",
                    onClick = onToggleMute,
                )
                PrimaryActionButton(
                    modifier = Modifier.weight(1f),
                    label = "End Call",
                    onClick = onBack,
                )
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
    Box(modifier = Modifier.fillMaxSize()) {
        HeroBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandWordmark()
            HeroEyebrow(text = "CHAT EXPERIENCE")
            Text(
                text = "Chat with Us",
                style = MaterialTheme.typography.headlineLarge,
                color = BrandSoftWhite,
            )
            Text(
                text = "Keep the same Giga feel while you send messages and follow the conversation in real time.",
                style = MaterialTheme.typography.bodyLarge,
                color = BrandSoftWhite.copy(alpha = 0.84f),
            )

            val chatError = state.error
            if (chatError != null) {
                NoticeBanner(chatError)
            }

            TranscriptCard(
                emptyState = if (state.isStarting) {
                    "Starting chat session..."
                } else {
                    "No messages yet."
                },
                transcript = state.transcript,
            )

            MonochromeCard {
                Text(
                    text = "Send a message",
                    style = MaterialTheme.typography.labelLarge,
                    color = BrandSoftWhite.copy(alpha = 0.86f),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isStarting && !state.isSending && !state.isClosing,
                    label = { Text("Message") },
                    value = composerValue,
                    onValueChange = onComposerValueChange,
                    colors = brandTextFieldColors(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryActionButton(
                    modifier = Modifier.weight(1f),
                    enabled = composerValue.isNotBlank() && !state.isSending && !state.isStarting,
                    label = if (state.isSending) "Sending..." else "Send",
                    onClick = onSend,
                )
                SecondaryActionButton(
                    modifier = Modifier.weight(1f),
                    enabled = !state.isClosing,
                    label = if (state.isClosing) "Closing..." else "End Chat",
                    onClick = onBack,
                )
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
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, BrandGlassBorderColor),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        shape = RoundedCornerShape(18.dp),
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
    MonochromeCard {
        Text(
            text = "Something needs attention",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LoadingCard(title: String) {
    MonochromeCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(title)
        }
    }
}

@Composable
private fun MonochromeCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, BrandGlassBorderColor),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, BrandGlassBorderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = BrandGlassColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InitializationOptionField(
    options: List<InitializationOption>,
    selectedInitializationOptionId: String,
    onOptionSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.id == selectedInitializationOptionId }
        ?: options.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = MenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                ),
            readOnly = true,
            value = selectedOption.label,
            onValueChange = {},
            label = { Text("Initialization preset") },
            supportingText = {
                Text(
                    selectedOption.description ?: "Choose a ready-made session preset.",
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = brandTextFieldColors(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                val optionDescription = option.description
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = option.label,
                                fontWeight = FontWeight.Medium,
                            )
                            if (!optionDescription.isNullOrBlank()) {
                                Text(
                                    text = optionDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onOptionSelected(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun brandTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrandSoftWhite,
    unfocusedTextColor = BrandSoftWhite,
    disabledTextColor = BrandSoftWhite.copy(alpha = 0.5f),
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedBorderColor = BrandSoftWhite.copy(alpha = 0.52f),
    unfocusedBorderColor = BrandSoftWhite.copy(alpha = 0.18f),
    disabledBorderColor = BrandSoftWhite.copy(alpha = 0.12f),
    focusedLabelColor = BrandSoftWhite.copy(alpha = 0.9f),
    unfocusedLabelColor = BrandSoftWhite.copy(alpha = 0.7f),
    disabledLabelColor = BrandSoftWhite.copy(alpha = 0.45f),
    focusedSupportingTextColor = BrandSoftWhite.copy(alpha = 0.7f),
    unfocusedSupportingTextColor = BrandSoftWhite.copy(alpha = 0.7f),
    disabledSupportingTextColor = BrandSoftWhite.copy(alpha = 0.45f),
    focusedTrailingIconColor = BrandSoftWhite.copy(alpha = 0.9f),
    unfocusedTrailingIconColor = BrandSoftWhite.copy(alpha = 0.7f),
    cursorColor = BrandSoftWhite,
)

private fun buildInitializationOptions(
    options: List<InitializationOption>,
): List<InitializationOption> =
    if (options.isEmpty()) {
        listOf(
            InitializationOption(
                id = DEFAULT_INITIALIZATION_OPTION_ID,
                label = "Default session",
                description = "Starts with no extra initialization values.",
                values = emptyInitializationValues(),
            ),
        )
    } else {
        options
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

private fun voiceConnectionLabel(connectionState: VoiceConnectionState): String =
    when (connectionState) {
        VoiceConnectionState.CONNECTED -> "Connected"
        VoiceConnectionState.CONNECTING -> "Connecting"
        VoiceConnectionState.IDLE -> "Idle"
    }

private const val DEFAULT_INITIALIZATION_OPTION_ID = "default"
private const val CALL_US_LABEL = "\uD83D\uDCDE Call Us"
private const val CHAT_WITH_US_LABEL = "\uD83D\uDCAC Chat with Us"

private val GigaSansTextFontFamily = FontFamily(
    Font(R.font.giga_sans_text_regular, FontWeight.Normal),
    Font(R.font.giga_sans_text_medium, FontWeight.Medium),
    Font(R.font.giga_sans_text_semibold, FontWeight.SemiBold),
)

private val GigaSansDisplayFontFamily = FontFamily(
    Font(R.font.giga_sans_display_regular, FontWeight.Normal),
    Font(R.font.giga_sans_display_medium, FontWeight.Medium),
    Font(R.font.giga_sans_display_semibold, FontWeight.SemiBold),
)

private val EmilioFontFamily = FontFamily(
    Font(R.font.emilio_light, FontWeight.Light),
    Font(R.font.emilio_regular, FontWeight.Normal),
    Font(R.font.emilio_semibold, FontWeight.SemiBold),
)

private val SampleBrandTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = EmilioFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = EmilioFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GigaSansDisplayFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = GigaSansDisplayFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GigaSansTextFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GigaSansTextFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GigaSansTextFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GigaSansTextFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GigaSansTextFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GigaSansTextFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    ),
)

private val SampleBrandColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0B1017),
    primaryContainer = Color(0x2AFFFFFF),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF0B1017),
    secondaryContainer = Color(0x1CFFFFFF),
    onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF081019),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF121A22),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A232C),
    onSurfaceVariant = Color(0xB8FFFFFF),
    outline = Color(0x26FFFFFF),
    error = Color(0xFFFFF2EC),
    onError = Color(0xFF0B1017),
)

private val BrandSoftWhite = Color(0xFFF8F5EF)
private val BrandAccentColor = Color(0xFFF76B15)
private val BrandGlassColor = Color(0x24060C12)
private val BrandGlassBorderColor = Color(0x26FFFFFF)
private val BrandHeroTopOverlay = Color(0x14081017)
private val BrandHeroMiddleOverlay = Color(0x08081017)
private val BrandHeroBottomOverlay = Color(0xD2081017)
