package com.hitha.visionassist.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hitha.visionassist.data.model.AssistantSettings
import com.hitha.visionassist.data.model.AssistantState
import com.hitha.visionassist.data.model.VisionAnalysisResult
import com.hitha.visionassist.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionAssistScreen(
    assistantState: AssistantState,
    statusMessage: String,
    currentResult: VisionAnalysisResult?,
    recentResults: List<VisionAnalysisResult>,
    settings: AssistantSettings,
    cameraTriggerMode: String,
    captureId: Int,
    onVoiceButtonClick: () -> Unit,
    onDescribeSurroundings: () -> Unit,
    onReadText: () -> Unit,
    onImageSelected: (Uri) -> Unit,
    onRepeatSpeech: () -> Unit,
    onStopSpeech: () -> Unit,
    onUpdateSettings: (AssistantSettings) -> Unit,
    onCaptureReady: (File, String) -> Unit,
    onCameraFailure: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lastHandledCaptureId by remember { mutableIntStateOf(-1) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImageSelected(uri)
        }
    }

    // Camera capture lifecycle coordination
    LaunchedEffect(cameraTriggerMode) {
        if (cameraTriggerMode.isBlank()) {
            imageCapture = null
        }
    }

    LaunchedEffect(cameraTriggerMode, captureId, imageCapture) {
        if (cameraTriggerMode.isNotBlank() && captureId != lastHandledCaptureId && imageCapture != null) {
            lastHandledCaptureId = captureId
            kotlinx.coroutines.delay(1200) // Brief stabilization
            val capture = imageCapture
            if (capture != null) {
                val cacheDir = File(context.cacheDir, "visionassist_captures")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                capture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onCaptureReady(photoFile, cameraTriggerMode)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            onCameraFailure("Camera capture failed: ${exception.localizedMessage ?: "please retry"}")
                        }
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "VisionAssist",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Voice-first visual assistance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showHelpDialog = true },
                        modifier = Modifier.semantics { contentDescription = "Voice commands help" }
                    ) {
                        Text("Help", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    TextButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.semantics { contentDescription = "Settings" }
                    ) {
                        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // Large Prominent Voice Assistant Status & Action Button
            item {
                VoiceAssistantHeroCard(
                    assistantState = assistantState,
                    statusMessage = statusMessage,
                    onClick = onVoiceButtonClick
                )
            }

            // Primary Visual Assistance Action Buttons
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDescribeSurroundings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .semantics {
                                contentDescription = "Describe my surroundings. Automatically captures camera and speaks scene details."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Describe My Surroundings",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 18.sp
                        )
                    }

                    Button(
                        onClick = onReadText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .semantics {
                                contentDescription = "Read visible text aloud. Automatically captures camera and reads signs or documents."
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Read Text Aloud",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 18.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .semantics {
                                contentDescription = "Select an image from device gallery to describe or read."
                            },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = "Select Image from Gallery",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // Quick Speech Control Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onRepeatSpeech,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .semantics { contentDescription = "Repeat last spoken description" },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Repeat", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Button(
                        onClick = onStopSpeech,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .semantics { contentDescription = "Stop speaking immediately" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Stop Speaking", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // Camera preview section (only shown while camera is actively capturing)
            if (cameraTriggerMode.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { viewContext ->
                                    val previewView = PreviewView(viewContext).apply {
                                        scaleType = PreviewView.ScaleType.FILL_CENTER
                                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    }
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
                                    cameraProviderFuture.addListener({
                                        try {
                                            val cameraProvider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.surfaceProvider = previewView.surfaceProvider
                                            }
                                            val capture = ImageCapture.Builder()
                                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                                .build()

                                            cameraProvider.unbindAll()
                                            cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                capture
                                            )
                                            imageCapture = capture
                                        } catch (e: Exception) {
                                            onCameraFailure("Could not initialize camera preview: ${e.localizedMessage}")
                                        }
                                    }, ContextCompat.getMainExecutor(viewContext))
                                    previewView
                                }
                            )

                            Text(
                                text = "Capturing automatically. Hold phone steady.",
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Active Analysis Result Card
            if (currentResult != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "${currentResult.title}: ${currentResult.spokenText}"
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentResult.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (currentResult.isOfflineFallback) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "On-Device",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = currentResult.spokenText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Recent Spoken Descriptions History
            if (recentResults.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent Observations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }

                items(recentResults.take(4)) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Speak this item again when tapped
                                onUpdateSettings(settings)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                                Text(
                                    text = timeFormat.format(Date(item.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.spokenText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // Safety and Privacy Notice
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Safety Notice",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "VisionAssist is an assistive aid and does not guarantee obstacle avoidance, depth estimation, or navigational safety. Never rely solely on AI for crossing roads or hazardous movement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Continuous Listening", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Listen for 'Hey VisionAssist' after speaking",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = settings.autoListenEnabled,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(autoListenEnabled = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Force Offline Mode", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Use on-device ML Kit only without cloud APIs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = settings.preferOfflineOnly,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(preferOfflineOnly = it))
                            }
                        )
                    }

                    Column {
                        Text("Speech Rate: ${String.format(Locale.US, "%.2f", settings.speechRate)}x", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = settings.speechRate,
                            onValueChange = {
                                onUpdateSettings(settings.copy(speechRate = it))
                            },
                            valueRange = 0.75f..1.35f,
                            steps = 5
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Help Dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Voice Commands Help", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Wake Phrase:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• \"Hey VisionAssist\"")

                    Text("Supported Commands:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("• \"Describe my surroundings\"")
                    Text("• \"What is in front of me?\"")
                    Text("• \"Read the text\" or \"Read signs\"")
                    Text("• \"Describe this image\"")
                    Text("• \"Repeat\" or \"Say that again\"")
                    Text("• \"Stop speaking\" or \"Stop\"")
                    Text("• \"Start listening\" / \"Stop listening\"")
                    Text("• \"Help\"")
                    Text("• Follow-up questions: \"What color is it?\", \"How many items are there?\"")
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

/**
 * Large accessible Voice Assistant Hero Card showing live status,
 * pulse animation color, and large touch target.
 */
@Composable
private fun VoiceAssistantHeroCard(
    assistantState: AssistantState,
    statusMessage: String,
    onClick: () -> Unit
) {
    val stateColor by animateColorAsState(
        targetValue = when (assistantState) {
            AssistantState.LISTENING -> VisionListeningActive
            AssistantState.SPEAKING -> VisionSpeakingActive
            AssistantState.CAPTURING, AssistantState.ANALYZING, AssistantState.INITIALIZING -> VisionPrimary
            AssistantState.ERROR -> VisionError
            AssistantState.IDLE -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor"
    )

    val stateLabel = when (assistantState) {
        AssistantState.LISTENING -> "Microphone Active — Listening"
        AssistantState.SPEAKING -> "Speaking Result…"
        AssistantState.CAPTURING -> "Capturing Camera Frame…"
        AssistantState.ANALYZING -> "Analyzing Scene…"
        AssistantState.INITIALIZING -> "Initializing Speech Engine…"
        AssistantState.ERROR -> "Status / Notice"
        AssistantState.IDLE -> "Tap to Activate Voice Assistant"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$stateLabel. Status: $statusMessage. Tap to start or pause voice assistance."
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, stateColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // State indicator circle
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(stateColor.copy(alpha = 0.2f))
                    .border(3.dp, stateColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (assistantState) {
                        AssistantState.LISTENING -> "MIC"
                        AssistantState.SPEAKING -> "TTS"
                        AssistantState.CAPTURING -> "CAM"
                        AssistantState.ANALYZING -> "AI"
                        AssistantState.INITIALIZING -> "LOAD"
                        AssistantState.ERROR -> "!"
                        AssistantState.IDLE -> "VOICE"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = stateColor
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stateLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}
