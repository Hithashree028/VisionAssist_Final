package com.hitha.visionassist.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Robust Text-to-Speech manager.
 * Guarantees serial execution (no overlapping speech), exposes repeat/stop capabilities,
 * buffers announcements requested before initialization completes,
 * and coordinates lifecycle transitions with speech recognition.
 */
class TextToSpeechManager(
    context: Context,
    private val onSpeakingStarted: () -> Unit,
    private val onSpeakingFinished: () -> Unit
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context, this)

    var isReady: Boolean = false
        private set
    var isSpeaking: Boolean = false
        private set

    var speechRate: Float = 0.95f
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    private var lastSpokenUtterance: String = ""
    private var pendingSpeech: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            tts?.language = Locale.US
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    mainHandler.post { onSpeakingStarted() }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.post { onSpeakingFinished() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.post { onSpeakingFinished() }
                }
            })

            // Flush pending utterance requested during startup
            val pending = pendingSpeech
            if (!pending.isNullOrBlank()) {
                pendingSpeech = null
                speak(pending)
            }
        } else {
            isReady = false
        }
    }

    /**
     * Speaks the provided text aloud.
     * If TTS engine is still initializing, queues text to speak as soon as initialized.
     * @param flushQueue If true, cancels ongoing speech to announce immediately.
     */
    fun speak(text: String, flushQueue: Boolean = true) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        lastSpokenUtterance = trimmed

        if (!isReady || tts == null) {
            pendingSpeech = trimmed
            return
        }

        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "va_utterance_${System.currentTimeMillis()}"

        mainHandler.post {
            if (isReady && tts != null) {
                tts?.speak(trimmed, queueMode, null, utteranceId)
            }
        }
    }

    /**
     * Repeats the most recent announcement.
     */
    fun repeatLast() {
        if (lastSpokenUtterance.isNotBlank()) {
            speak(lastSpokenUtterance)
        } else {
            speak("There is no previous statement to repeat.")
        }
    }

    /**
     * Immediately stops speaking.
     */
    fun stop() {
        tts?.stop()
        isSpeaking = false
        mainHandler.post { onSpeakingFinished() }
    }

    /**
     * Releases system TTS resources.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
