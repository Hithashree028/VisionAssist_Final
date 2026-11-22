package com.hitha.visionassist.data.model

import android.graphics.Rect

/**
 * Visual assistance analysis mode.
 */
enum class AnalysisMode {
    SCENE_DESCRIPTION,
    OBJECT_SEARCH,
    OBJECT_COUNTING,
    OCR_READ_TEXT,
    QUESTION_ANSWER
}

/**
 * Runtime state of the voice-first assistant.
 */
enum class AssistantState {
    IDLE,
    INITIALIZING,
    LISTENING,
    CAPTURING,
    ANALYZING,
    SPEAKING,
    ERROR
}

/**
 * Spatial horizontal position classification.
 */
enum class HorizontalPosition(val spokenPhrase: String) {
    LEFT("on the left"),
    CENTER("near the center"),
    RIGHT("on the right")
}

/**
 * Information regarding a detected visual object.
 */
data class DetectedObjectInfo(
    val label: String,
    val confidence: Float,
    val position: HorizontalPosition,
    val boundingBox: Rect? = null,
    val isBackground: Boolean = false
) {
    val spokenPosition: String
        get() = position.spokenPhrase
}

/**
 * Structured Gemini AI Vision response.
 */
data class GeminiAnalysisData(
    val sceneDescription: String,
    val visibleObjects: List<DetectedObjectInfo> = emptyList(),
    val visibleText: String? = null,
    val summaryForSpeech: String
)

/**
 * Final result delivered to user via TTS and UI.
 */
data class VisionAnalysisResult(
    val title: String,
    val spokenText: String,
    val fullDetails: String = spokenText,
    val isOfflineFallback: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val objectsDetected: List<DetectedObjectInfo> = emptyList()
)

/**
 * User-configurable settings for speech and assistant behavior.
 */
data class AssistantSettings(
    val autoListenEnabled: Boolean = true,
    val speechRate: Float = 0.95f,
    val preferOfflineOnly: Boolean = false,
    val wakePhrase: String = "Hey VisionAssist"
)
