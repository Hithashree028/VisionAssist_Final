package com.hitha.visionassist.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device, offline speech recognizer and wake-word detector using Vosk.
 * Manages Vosk acoustic model loading, microphone streaming lifecycle,
 * seamless pausing during TTS/camera operations, and reliable phrase dispatch.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onSpeechRecognized: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null

    var isReady: Boolean = false
        private set
    private var isModelLoading: Boolean = false
    var isListening: Boolean = false
        private set
    private var isPaused: Boolean = false

    private var lastTriggerTime: Long = 0L
    private var lastProcessedText: String = ""
    private val debounceCooldownMs: Long = 1200L

    private val errorRestartRunnable = Runnable {
        if (isReady && !isPaused) {
            startListening()
        }
    }

    /**
     * Initializes Vosk offline model from assets folder in a background worker thread.
     */
    fun initializeModel(onComplete: ((Boolean) -> Unit)? = null) {
        if (isReady) {
            onComplete?.invoke(true)
            return
        }
        if (isModelLoading) {
            return
        }

        isModelLoading = true
        onStatusUpdate("Loading on-device speech model…")

        Thread {
            try {
                val modelDir = File(context.filesDir, "visionassist-vosk-model")
                val confFile = File(modelDir, "conf/model.conf")
                val mdlFile = File(modelDir, "am/final.mdl")

                if (!confFile.exists() || !mdlFile.exists()) {
                    modelDir.deleteRecursively()
                    modelDir.mkdirs()
                    copyAssetsRecursively("model", modelDir)
                }

                val loaded = Model(modelDir.absolutePath)
                val rec = Recognizer(loaded, 16000.0f)

                mainHandler.post {
                    voskModel = loaded
                    recognizer = rec
                    isReady = true
                    isModelLoading = false
                    onStatusUpdate("Speech recognition ready. Listening for 'Hey VisionAssist'.")
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isReady = false
                    isModelLoading = false
                    onStatusUpdate("Failed to initialize speech model: ${e.localizedMessage}")
                    onComplete?.invoke(false)
                }
            }
        }.start()
    }

    /**
     * Starts continuous listening for user voice commands.
     */
    fun startListening() {
        if (!isReady || voskModel == null) return
        mainHandler.removeCallbacks(errorRestartRunnable)

        // If speech service is already running and paused, simply unpause
        val service = speechService
        if (service != null && isListening) {
            try {
                service.setPause(false)
                isPaused = false
                return
            } catch (_: Exception) {}
        }

        try {
            // Ensure any previous service instance is safely stopped
            stopListeningInternal(shutdown = true)

            val rec = Recognizer(voskModel, 16000.0f)
            recognizer = rec
            val newService = SpeechService(rec, 16000.0f)
            speechService = newService
            isListening = true
            isPaused = false

            newService.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val partial = extractRecognizedText(hypothesis)
                    if (partial.isNotBlank()) {
                        mainHandler.post {
                            onStatusUpdate("Hearing: \"$partial\"…")
                        }
                    }
                }

                override fun onResult(hypothesis: String?) {
                    processRecognizedHypothesis(hypothesis)
                }

                override fun onFinalResult(hypothesis: String?) {
                    processRecognizedHypothesis(hypothesis)
                }

                override fun onError(exception: Exception?) {
                    mainHandler.post {
                        onStatusUpdate("Speech recognition interrupted. Reconnecting microphone…")
                        mainHandler.removeCallbacks(errorRestartRunnable)
                        mainHandler.postDelayed(errorRestartRunnable, 1000L)
                    }
                }

                override fun onTimeout() {
                    mainHandler.post {
                        if (isListening && !isPaused) {
                            speechService?.reset()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            onStatusUpdate("Microphone listening initialization error: ${e.localizedMessage}")
            mainHandler.postDelayed(errorRestartRunnable, 1500L)
        }
    }

    private fun processRecognizedHypothesis(jsonStr: String?) {
        val text = extractRecognizedText(jsonStr)
        if (text.isBlank()) return

        val now = System.currentTimeMillis()
        if (text.equals(lastProcessedText, ignoreCase = true) && (now - lastTriggerTime < debounceCooldownMs)) {
            return
        }

        lastTriggerTime = now
        lastProcessedText = text

        mainHandler.post {
            // Forward recognized text for comprehensive intention and follow-up parsing
            onSpeechRecognized(text)
        }
    }

    /**
     * Pauses active microphone listening during TTS output or camera capture
     * without tearing down the underlying AudioRecord thread.
     */
    fun pauseListening() {
        isPaused = true
        try {
            speechService?.setPause(true)
        } catch (_: Exception) {}
    }

    /**
     * Resumes listening after speaking or capturing completes.
     */
    fun resumeListening() {
        if (!isReady) return
        isPaused = false
        if (speechService != null && isListening) {
            try {
                speechService?.setPause(false)
                speechService?.reset()
                return
            } catch (_: Exception) {}
        }
        startListening()
    }

    /**
     * Stops active microphone listening.
     */
    fun stopListening() {
        stopListeningInternal(shutdown = false)
    }

    private fun stopListeningInternal(shutdown: Boolean) {
        isListening = false
        isPaused = false
        val s = speechService
        val r = recognizer
        if (shutdown) {
            speechService = null
            recognizer = null
            Thread {
                try { s?.stop() } catch (_: Exception) {}
                try { s?.shutdown() } catch (_: Exception) {}
                try { r?.close() } catch (_: Exception) {}
            }.start()
        } else {
            try { s?.stop() } catch (_: Exception) {}
        }
    }

    private fun extractRecognizedText(jsonText: String?): String {
        return try {
            val json = JSONObject(jsonText.orEmpty())
            val text = if (json.has("text")) {
                json.optString("text", "")
            } else {
                json.optString("partial", "")
            }
            text.trim().lowercase(Locale.US)
        } catch (_: Exception) {
            ""
        }
    }

    private fun copyAssetsRecursively(assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output -> input.copyTo(output) }
            }
            return
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("Failed to create ${targetDir.absolutePath}")
        }

        for (child in children) {
            copyAssetsRecursively("$assetPath/$child", File(targetDir, child))
        }
    }

    fun release() {
        mainHandler.removeCallbacks(errorRestartRunnable)
        stopListeningInternal(shutdown = true)
        try { voskModel?.close() } catch (_: Exception) {}
        voskModel = null
        isReady = false
    }
}
