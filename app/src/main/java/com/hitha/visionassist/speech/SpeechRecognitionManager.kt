package com.hitha.visionassist.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hitha.visionassist.data.model.AssistantSettings
import com.hitha.visionassist.data.model.AssistantState

/**
 * Coordinates Speech Input (Wake-word and Natural Voice Commands)
 * and Speech Output (Text-to-Speech) ensuring lifecycle synchronization,
 * speaker echo isolation, automated hands-free startup, and multi-turn conversational follow-up.
 */
class SpeechRecognitionManager(
    context: Context,
    private val onCommandReceived: (VoiceCommand) -> Unit,
    private val onWakeWordTriggered: () -> Unit,
    private val onStateChanged: (AssistantState) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    var settings: AssistantSettings = AssistantSettings()
        set(value) {
            field = value
            ttsManager.speechRate = value.speechRate
        }

    var isAwaitingFollowUp: Boolean = false
        private set

    private val followUpResetRunnable = Runnable {
        isAwaitingFollowUp = false
        if (isAssistantActive && settings.autoListenEnabled && !ttsManager.isSpeaking) {
            onStatusUpdate("Listening for 'Hey VisionAssist' or command…")
        }
    }

    val ttsManager: TextToSpeechManager = TextToSpeechManager(
        context = context,
        onSpeakingStarted = {
            wakeWordDetector.pauseListening()
            onStateChanged(AssistantState.SPEAKING)
        },
        onSpeakingFinished = {
            // Buffer delay (400ms) to ensure speaker audio output has completely finished
            // and hardware room echo does not feed back into the microphone.
            mainHandler.postDelayed({
                if (settings.autoListenEnabled && isAssistantActive) {
                    resumeListening()
                } else {
                    onStateChanged(AssistantState.IDLE)
                }
            }, 400L)
        }
    )

    private val wakeWordDetector: WakeWordDetector = WakeWordDetector(
        context = context,
        onWakeWordDetected = {
            handleWakeWordDetected()
        },
        onSpeechRecognized = { text ->
            handleSpeechRecognized(text)
        },
        onStatusUpdate = onStatusUpdate
    )

    var isAssistantActive: Boolean = false
        private set

    val isModelReady: Boolean
        get() = wakeWordDetector.isReady

    /**
     * Initializes speech engine models asynchronously.
     */
    fun initialize(onReady: (() -> Unit)? = null) {
        wakeWordDetector.initializeModel { success ->
            if (success && isAssistantActive && settings.autoListenEnabled) {
                resumeListening()
            }
            onReady?.invoke()
        }
    }

    /**
     * Activates the assistant hands-free listening loop.
     * Provides audible welcome feedback: "VisionAssist is ready. How can I help you?"
     */
    fun startAssistant(spokenGreeting: String = "VisionAssist is ready. How can I help you?") {
        isAssistantActive = true
        if (wakeWordDetector.isReady) {
            resumeListening()
            if (spokenGreeting.isNotBlank()) {
                ttsManager.speak(spokenGreeting)
            }
        } else {
            initialize {
                resumeListening()
                if (spokenGreeting.isNotBlank()) {
                    ttsManager.speak(spokenGreeting)
                }
            }
        }
    }

    /**
     * Pauses the assistant.
     */
    fun stopAssistant() {
        isAssistantActive = false
        setFollowUpExpected(false)
        wakeWordDetector.stopListening()
        ttsManager.stop()
        onStateChanged(AssistantState.IDLE)
        onStatusUpdate("Voice Assistant paused.")
    }

    /**
     * Resumes listening if speech output is not active.
     */
    fun resumeListening() {
        if (!isAssistantActive) return
        if (ttsManager.isSpeaking) return

        onStateChanged(AssistantState.LISTENING)
        if (isAwaitingFollowUp) {
            onStatusUpdate("Listening for follow-up question or command…")
        } else {
            onStatusUpdate("Listening for 'Hey VisionAssist' or command…")
        }
        wakeWordDetector.resumeListening()
    }

    /**
     * Pauses listening (e.g. during camera capture or analysis).
     */
    fun pauseListening() {
        wakeWordDetector.pauseListening()
    }

    /**
     * Sets or extends follow-up question expectation window.
     */
    fun setFollowUpExpected(expected: Boolean, timeoutMs: Long = 45_000L) {
        mainHandler.removeCallbacks(followUpResetRunnable)
        isAwaitingFollowUp = expected
        if (expected && timeoutMs > 0L) {
            mainHandler.postDelayed(followUpResetRunnable, timeoutMs)
        }
    }

    private fun handleWakeWordDetected() {
        onStatusUpdate("Wake word recognized: 'Hey VisionAssist'")
        setFollowUpExpected(true, 45_000L)
        speak("VisionAssist is listening. How can I help you?")
    }

    private fun handleSpeechRecognized(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return

        onStatusUpdate("Heard: \"$trimmed\"")
        val command = VoiceCommandParser.parseCommand(trimmed, isAwaitingFollowUp = isAwaitingFollowUp)

        when (command) {
            is VoiceCommand.WakeWord -> {
                handleWakeWordDetected()
            }
            is VoiceCommand.StopSpeaking -> {
                ttsManager.stop()
                setFollowUpExpected(false)
                onCommandReceived(command)
            }
            is VoiceCommand.Repeat -> {
                ttsManager.repeatLast()
                onCommandReceived(command)
            }
            is VoiceCommand.StartContinuousListening -> {
                settings = settings.copy(autoListenEnabled = true)
                ttsManager.speak("Continuous listening enabled.")
                onCommandReceived(command)
            }
            is VoiceCommand.StopContinuousListening -> {
                settings = settings.copy(autoListenEnabled = false)
                pauseListening()
                ttsManager.speak("Continuous listening paused.")
                onCommandReceived(command)
            }
            is VoiceCommand.Help -> {
                ttsManager.speak(
                    "You can say: Describe my surroundings, Read the text, Where is the chair, How many people are there, Repeat, or Stop."
                )
                onCommandReceived(command)
            }
            is VoiceCommand.Unknown -> {
                // Only provide audio feedback if the user said something substantial that wasn't recognized
                if (trimmed.length > 3) {
                    onStatusUpdate("Unrecognized: \"$trimmed\"")
                    speak("I didn't quite catch that. You can say: Describe surroundings, Read text, Where is an object, or Count objects.")
                }
            }
            else -> {
                // Keep follow-up open for subsequent questions
                setFollowUpExpected(true, 45_000L)
                onCommandReceived(command)
            }
        }
    }

    fun speak(text: String) {
        pauseListening()
        ttsManager.speak(text)
    }

    fun repeatLast() {
        ttsManager.repeatLast()
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    fun shutdown() {
        stopAssistant()
        wakeWordDetector.release()
        ttsManager.shutdown()
    }
}
