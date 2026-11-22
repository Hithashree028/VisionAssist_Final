
package com.hitha.visionassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hitha.visionassist.data.model.AssistantSettings
import com.hitha.visionassist.data.model.AssistantState
import com.hitha.visionassist.data.model.VisionAnalysisResult
import com.hitha.visionassist.data.repository.DefaultVisionRepository
import com.hitha.visionassist.data.repository.VisionRepository
import com.hitha.visionassist.service.AssistantForegroundService
import com.hitha.visionassist.speech.SpeechRecognitionManager
import com.hitha.visionassist.speech.VoiceCommand
import com.hitha.visionassist.ui.VisionAssistScreen
import com.hitha.visionassist.ui.theme.VisionAssistTheme
import com.hitha.visionassist.vision.ObjectDetectionEngine
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var visionRepository: VisionRepository
    private lateinit var speechManager: SpeechRecognitionManager

    private val objectDetector = ObjectDetectionEngine()

    private var assistantState by mutableStateOf(AssistantState.IDLE)
    private var statusMessage by mutableStateOf("VisionAssist is starting…")
    private var currentResult by mutableStateOf<VisionAnalysisResult?>(null)

    private val recentResults =
        mutableStateListOf<VisionAnalysisResult>()

    private var settings by mutableStateOf(AssistantSettings())
    private var cameraTriggerMode by mutableStateOf("")
    private var captureId by mutableIntStateOf(0)
    private var lastImageFile: File? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val cameraGranted =
                permissions[Manifest.permission.CAMERA] == true

            val audioGranted =
                permissions[Manifest.permission.RECORD_AUDIO] == true

            if (cameraGranted && audioGranted) {

                statusMessage =
                    "VisionAssist is ready. How can I help you?"

                AssistantForegroundService.startService(this)

                speechManager.startAssistant(
                    "VisionAssist is ready. How can I help you?"
                )

            } else {

                val missing = buildList {
                    if (!cameraGranted) add("Camera")
                    if (!audioGranted) add("Microphone")
                }.joinToString(" and ")

                statusMessage =
                    "$missing permission required for visual assistance."

                speechManager.speak(
                    "$missing permission required for visual assistance. Please grant permissions to continue."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        visionRepository =
            DefaultVisionRepository(applicationContext)

        speechManager = SpeechRecognitionManager(
            context = applicationContext,

            onCommandReceived = { command ->
                handleVoiceCommand(command)
            },

            onWakeWordTriggered = {
                statusMessage = "Listening for command…"
            },

            onStateChanged = { newState ->
                assistantState = newState
            },

            onStatusUpdate = { update ->
                statusMessage = update
            }
        )

        checkAndRequestPermissions()

        setContent {
            VisionAssistTheme {

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {

                    // Existing VisionAssist interface
                    VisionAssistScreen(
                        assistantState = assistantState,
                        statusMessage = statusMessage,
                        currentResult = currentResult,
                        recentResults = recentResults,
                        settings = settings,
                        cameraTriggerMode = cameraTriggerMode,
                        captureId = captureId,

                        onVoiceButtonClick = {
                            handleVoiceButtonClick()
                        },

                        onDescribeSurroundings = {
                            triggerCameraCapture("describe")
                        },

                        onReadText = {
                            triggerCameraCapture("read")
                        },

                        onImageSelected = { uri ->
                            handleImageSelected(uri)
                        },

                        onRepeatSpeech = {
                            speechManager.repeatLast()
                        },

                        onStopSpeech = {
                            speechManager.stopSpeaking()
                        },

                        onUpdateSettings = { newSettings ->
                            settings = newSettings
                            speechManager.settings = newSettings
                        },

                        onCaptureReady = { file, mode ->
                            handleCaptureReady(file, mode)
                        },

                        onCameraFailure = { err ->
                            cameraTriggerMode = ""
                            assistantState = AssistantState.ERROR
                            statusMessage = err
                            speechManager.speak(err)
                        }
                    )

                    // NEW: Evaluation button
                    FloatingActionButton(
                        onClick = {
                            startActivity(
                                Intent(
                                    this@MainActivity,
                                    EvaluationActivity::class.java
                                )
                            )
                        },

                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Text("Evaluate")
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {

        val needed = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {

            permissionLauncher.launch(
                needed.toTypedArray()
            )

        } else {

            AssistantForegroundService.startService(this)

            speechManager.startAssistant(
                "VisionAssist is ready. How can I help you?"
            )
        }
    }

    private fun handleVoiceButtonClick() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED ||

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkAndRequestPermissions()
            return
        }

        if (speechManager.isAssistantActive) {

            speechManager.stopAssistant()
            AssistantForegroundService.stopService(this)

            statusMessage =
                "Voice Assistant paused. Tap to start again."

            speechManager.speak(
                "Voice assistant paused."
            )

        } else {

            AssistantForegroundService.startService(this)

            speechManager.startAssistant(
                "VisionAssist is active. How can I help you?"
            )
        }
    }

    /**
     * Activates CameraX capture automatically.
     */
    private fun triggerCameraCapture(mode: String) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            statusMessage = "Camera permission required."
            speechManager.speak(statusMessage)
            checkAndRequestPermissions()
            return
        }

        speechManager.pauseListening()

        assistantState = AssistantState.CAPTURING
        cameraTriggerMode = mode
        captureId++

        val announcement = when {
            mode == "read" ->
                "Reading visible text. Hold steady."

            mode.startsWith("search:") ->
                "Looking for ${mode.removePrefix("search:")}. Hold steady."

            mode.startsWith("count:") ->
                "Counting ${mode.removePrefix("count:")}. Hold steady."

            else ->
                "Observing your surroundings. Hold steady."
        }

        statusMessage = announcement
        speechManager.speak(announcement)
    }

    private fun handleCaptureReady(
        photoFile: File,
        mode: String
    ) {

        cameraTriggerMode = ""
        lastImageFile = photoFile

        assistantState = AssistantState.ANALYZING
        statusMessage = "Analyzing visual information…"

        lifecycleScope.launch {

            try {

                val result: VisionAnalysisResult = when {

                    mode == "read" -> {
                        visionRepository.recognizeText(photoFile)
                    }

                    mode.startsWith("search:") -> {

                        val target = mode.removePrefix("search:")

                        visionRepository.searchObject(
                            photoFile,
                            target,
                            forceOffline = settings.preferOfflineOnly
                        )
                    }

                    mode.startsWith("count:") -> {

                        val target = mode.removePrefix("count:")

                        visionRepository.countObjects(
                            photoFile,
                            target,
                            forceOffline = settings.preferOfflineOnly
                        )
                    }

                    mode.startsWith("question:") -> {

                        val question = mode.removePrefix("question:")

                        visionRepository.analyzeSurroundings(
                            photoFile,
                            userQuestion = question,
                            forceOffline = settings.preferOfflineOnly
                        )
                    }

                    else -> {

                        visionRepository.analyzeSurroundings(
                            photoFile,
                            forceOffline = settings.preferOfflineOnly
                        )
                    }
                }

                currentResult = result
                recentResults.add(0, result)

                statusMessage = result.title

                speechManager.speak(result.spokenText)
                speechManager.setFollowUpExpected(true)

                visionRepository.cleanupTemporaryFiles(photoFile)

            } catch (e: Exception) {

                assistantState = AssistantState.ERROR

                val errMsg =
                    "Visual analysis error: ${e.localizedMessage ?: "please retry"}"

                statusMessage = errMsg
                speechManager.speak(errMsg)
            }
        }
    }

    private fun handleImageSelected(uri: Uri) {

        val cacheFolder =
            File(cacheDir, "visionassist_uploads")

        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        val targetFile = File(
            cacheFolder,
            "upload_${System.currentTimeMillis()}.jpg"
        )

        statusMessage = "Loading selected image…"

        val success =
            visionRepository.processSelectedUri(uri, targetFile)

        if (!success) {

            statusMessage = "Could not load selected image."
            speechManager.speak(statusMessage)
            return
        }

        lastImageFile = targetFile

        assistantState = AssistantState.ANALYZING
        statusMessage = "Analyzing selected image…"

        speechManager.speak(
            "Image loaded. Analyzing surroundings."
        )

        lifecycleScope.launch {

            try {

                val result =
                    visionRepository.analyzeSurroundings(
                        targetFile,
                        forceOffline = settings.preferOfflineOnly
                    )

                currentResult = result
                recentResults.add(0, result)

                statusMessage = result.title

                speechManager.speak(result.spokenText)
                speechManager.setFollowUpExpected(true)

            } catch (e: Exception) {

                assistantState = AssistantState.ERROR

                statusMessage =
                    "Failed to analyze image: ${e.localizedMessage}"

                speechManager.speak(statusMessage)
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommand) {

        when (command) {

            is VoiceCommand.WakeWord -> {
                // Wake word acknowledged by speech manager.
            }

            is VoiceCommand.DescribeSurroundings -> {
                triggerCameraCapture("describe")
            }

            is VoiceCommand.SearchObject -> {

                val target = command.target

                val cachedMatches = currentResult?.let { res ->

                    if (
                        System.currentTimeMillis() - res.timestamp < 45_000L
                    ) {
                        objectDetector.findObjects(
                            res.objectsDetected,
                            target
                        )
                    } else {
                        emptyList()
                    }

                } ?: emptyList()

                if (cachedMatches.isNotEmpty()) {

                    val match = cachedMatches.first()

                    val answer =
                        "The ${match.label} is ${match.spokenPosition}."

                    statusMessage = answer
                    speechManager.speak(answer)
                    speechManager.setFollowUpExpected(true)

                } else {

                    val existing = lastImageFile

                    if (
                        existing != null &&
                        existing.exists() &&
                        System.currentTimeMillis() - existing.lastModified() < 45_000L
                    ) {

                        assistantState = AssistantState.ANALYZING
                        statusMessage = "Locating $target…"

                        speechManager.speak(
                            "Locating $target."
                        )

                        lifecycleScope.launch {

                            try {

                                val result =
                                    visionRepository.searchObject(
                                        existing,
                                        target,
                                        forceOffline = settings.preferOfflineOnly
                                    )

                                currentResult = result
                                recentResults.add(0, result)

                                statusMessage = result.title

                                speechManager.speak(result.spokenText)
                                speechManager.setFollowUpExpected(true)

                            } catch (e: Exception) {

                                speechManager.speak(
                                    "Could not find $target: ${e.localizedMessage}"
                                )
                            }
                        }

                    } else {

                        triggerCameraCapture("search:$target")
                    }
                }
            }

            is VoiceCommand.CountObjects -> {

                val target = command.target

                val cachedMatches = currentResult?.let { res ->

                    if (
                        System.currentTimeMillis() - res.timestamp < 45_000L
                    ) {
                        objectDetector.findObjects(
                            res.objectsDetected,
                            target
                        )
                    } else {
                        emptyList()
                    }

                } ?: emptyList()

                if (cachedMatches.isNotEmpty()) {

                    val count = cachedMatches.size

                    val noun =
                        if (count == 1) target else "${target}s"

                    val positions =
                        cachedMatches.joinToString(", ") {
                            it.spokenPosition
                        }

                    val answer =
                        "I can see $count $noun located $positions."

                    statusMessage = answer
                    speechManager.speak(answer)
                    speechManager.setFollowUpExpected(true)

                } else {

                    val existing = lastImageFile

                    if (
                        existing != null &&
                        existing.exists() &&
                        System.currentTimeMillis() - existing.lastModified() < 45_000L
                    ) {

                        assistantState = AssistantState.ANALYZING
                        statusMessage = "Counting $target…"

                        speechManager.speak(
                            "Counting $target."
                        )

                        lifecycleScope.launch {

                            try {

                                val result =
                                    visionRepository.countObjects(
                                        existing,
                                        target,
                                        forceOffline = settings.preferOfflineOnly
                                    )

                                currentResult = result
                                recentResults.add(0, result)

                                statusMessage = result.title

                                speechManager.speak(result.spokenText)
                                speechManager.setFollowUpExpected(true)

                            } catch (e: Exception) {

                                speechManager.speak(
                                    "Could not count $target: ${e.localizedMessage}"
                                )
                            }
                        }

                    } else {

                        triggerCameraCapture("count:$target")
                    }
                }
            }

            is VoiceCommand.ReadText -> {
                triggerCameraCapture("read")
            }

            is VoiceCommand.AskQuestion -> {

                val existing = lastImageFile

                if (
                    existing != null &&
                    existing.exists() &&
                    System.currentTimeMillis() - existing.lastModified() < 45_000L
                ) {

                    assistantState = AssistantState.ANALYZING

                    statusMessage =
                        "Answering: \"${command.question}\""

                    speechManager.speak(
                        "Checking your question."
                    )

                    lifecycleScope.launch {

                        try {

                            val result =
                                visionRepository.analyzeSurroundings(
                                    existing,
                                    userQuestion = command.question,
                                    forceOffline = settings.preferOfflineOnly
                                )

                            currentResult = result
                            recentResults.add(0, result)

                            statusMessage = result.title

                            speechManager.speak(result.spokenText)
                            speechManager.setFollowUpExpected(true)

                        } catch (e: Exception) {

                            speechManager.speak(
                                "Could not answer: ${e.localizedMessage}"
                            )
                        }
                    }

                } else {

                    triggerCameraCapture(
                        "question:${command.question}"
                    )
                }
            }

            is VoiceCommand.Repeat -> {
                speechManager.repeatLast()
            }

            is VoiceCommand.StopSpeaking -> {
                speechManager.stopSpeaking()
            }

            is VoiceCommand.StartContinuousListening -> {

                settings = settings.copy(
                    autoListenEnabled = true
                )

                speechManager.settings = settings
            }

            is VoiceCommand.StopContinuousListening -> {

                settings = settings.copy(
                    autoListenEnabled = false
                )

                speechManager.settings = settings
            }

            is VoiceCommand.Help -> {
                // Handled directly by speechManager.
            }

            is VoiceCommand.Unknown -> {

                statusMessage =
                    "Heard: \"${command.rawText}\""

                speechManager.speak(
                    "You can say: Describe surroundings, Read text, Where is an object, or Count objects."
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            speechManager.isAssistantActive &&
            settings.autoListenEnabled &&
            assistantState != AssistantState.SPEAKING
        ) {
            speechManager.resumeListening()
        }
    }

    override fun onPause() {
        speechManager.pauseListening()
        super.onPause()
    }

    override fun onDestroy() {

        AssistantForegroundService.stopService(this)

        speechManager.shutdown()
        visionRepository.close()
        objectDetector.close()

        super.onDestroy()
    }
}
