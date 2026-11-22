
package com.hitha.visionassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hitha.visionassist.BuildConfig
import com.hitha.visionassist.data.model.DetectedObjectInfo
import com.hitha.visionassist.data.model.VisionAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-level Vision Engine.
 * Uses Gemini for online scene understanding,
 * YOLO11n for offline object detection,
 * ML Kit as detection fallback, and ML Kit OCR.
 */
class VisionEngine(private val context: Context) {

    private val imageProcessor = ImageProcessor(context)
    private val objectDetector = ObjectDetectionEngine()
    private val yoloDetector = YoloDetector(context)
    private val ocrManager = OCRManager()
    private val geminiClient = GeminiVisionClient()

    /**
     * YOLO11n is the primary offline detector.
     * ML Kit is used if YOLO detection fails.
     */
    private suspend fun detectObjects(
        bitmap: Bitmap
    ): List<DetectedObjectInfo> {
        return try {
            yoloDetector.detect(bitmap)
        } catch (e: Exception) {
            objectDetector.detectObjects(bitmap)
        }
    }

    /**
     * Complete scene understanding.
     * Attempts Gemini first and falls back to on-device analysis.
     */
    suspend fun describeScene(
        imageFile: File,
        userQuestion: String? = null,
        forceOffline: Boolean = false
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {

        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext VisionAnalysisResult(
                title = "Image Unavailable",
                spokenText = "No fresh image is available. Please hold your phone up and try again.",
                isOfflineFallback = true
            )
        }

        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        val hasGeminiKey =
            apiKey.isNotBlank() &&
                    !apiKey.equals("YOUR_API_KEY", ignoreCase = true)

        if (!forceOffline && hasGeminiKey) {
            try {
                val imageBytes =
                    imageProcessor.compressFileForUpload(imageFile)

                val geminiData = geminiClient.analyzeImage(
                    imageBytes,
                    userQuestion = userQuestion
                )

                return@withContext VisionAnalysisResult(
                    title = if (userQuestion.isNullOrBlank())
                        "Scene Description"
                    else
                        "Question Answer",
                    spokenText = geminiData.summaryForSpeech,
                    fullDetails = geminiData.sceneDescription,
                    isOfflineFallback = false,
                    objectsDetected = geminiData.visibleObjects
                )

            } catch (e: Exception) {

                val isAuthError =
                    e.message?.contains("HTTP 401") == true ||
                            e.message?.contains("HTTP 403") == true

                val isQuotaError =
                    e.message?.contains("HTTP 429") == true

                if (isAuthError || isQuotaError) {
                    val fallback =
                        runOnDeviceSceneAnalysis(imageFile, userQuestion)

                    val errorMessage = if (isAuthError)
                        "Gemini authentication error."
                    else
                        "Gemini API rate limit reached."

                    return@withContext fallback.copy(
                        spokenText = "$errorMessage Using offline detection: ${fallback.spokenText}"
                    )
                }
            }
        }

        runOnDeviceSceneAnalysis(imageFile, userQuestion)
    }

    /**
     * Searches for a specific object.
     */
    suspend fun searchObject(
        imageFile: File,
        targetObject: String,
        forceOffline: Boolean = false
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {

        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext VisionAnalysisResult(
                title = "Object Search",
                spokenText = "No image available to search for $targetObject.",
                isOfflineFallback = true
            )
        }

        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        val hasGeminiKey =
            apiKey.isNotBlank() &&
                    !apiKey.equals("YOUR_API_KEY", ignoreCase = true)

        if (!forceOffline && hasGeminiKey) {
            try {
                val imageBytes =
                    imageProcessor.compressFileForUpload(imageFile)

                val geminiData = geminiClient.analyzeImage(
                    imageBytes,
                    targetObject = targetObject,
                    isCounting = false
                )

                return@withContext VisionAnalysisResult(
                    title = "Object Search: $targetObject",
                    spokenText = geminiData.summaryForSpeech,
                    fullDetails = geminiData.sceneDescription,
                    isOfflineFallback = false,
                    objectsDetected = geminiData.visibleObjects
                )

            } catch (_: Exception) {
                // Fall back to on-device detection.
            }
        }

        val bitmap = imageProcessor.decodeAndNormalizeBitmap(imageFile)
            ?: return@withContext VisionAnalysisResult(
                title = "Object Search",
                spokenText = "Could not decode image for on-device search.",
                isOfflineFallback = true
            )

        val detected = try {
            detectObjects(bitmap)
        } finally {
            bitmap.recycle()
        }

        val matching =
            objectDetector.findObjects(detected, targetObject)

        val spoken = if (matching.isNotEmpty()) {
            val positions = matching.joinToString(", ") {
                "${it.label} is ${it.spokenPosition}"
            }
            "Offline detection: I found $positions."
        } else {
            "Offline detection: I could not see $targetObject in the current view."
        }

        VisionAnalysisResult(
            title = "Object Search: $targetObject",
            spokenText = spoken,
            fullDetails = spoken,
            isOfflineFallback = true,
            objectsDetected = matching
        )
    }

    /**
     * Counts occurrences of a specific object.
     */
    suspend fun countObjects(
        imageFile: File,
        targetObject: String,
        forceOffline: Boolean = false
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {

        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext VisionAnalysisResult(
                title = "Object Counting",
                spokenText = "No image available to count $targetObject.",
                isOfflineFallback = true
            )
        }

        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        val hasGeminiKey =
            apiKey.isNotBlank() &&
                    !apiKey.equals("YOUR_API_KEY", ignoreCase = true)

        if (!forceOffline && hasGeminiKey) {
            try {
                val imageBytes =
                    imageProcessor.compressFileForUpload(imageFile)

                val geminiData = geminiClient.analyzeImage(
                    imageBytes,
                    targetObject = targetObject,
                    isCounting = true
                )

                return@withContext VisionAnalysisResult(
                    title = "Count: $targetObject",
                    spokenText = geminiData.summaryForSpeech,
                    fullDetails = geminiData.sceneDescription,
                    isOfflineFallback = false,
                    objectsDetected = geminiData.visibleObjects
                )

            } catch (_: Exception) {
                // Fall back to on-device detection.
            }
        }

        val bitmap = imageProcessor.decodeAndNormalizeBitmap(imageFile)
            ?: return@withContext VisionAnalysisResult(
                title = "Object Counting",
                spokenText = "Could not decode image for counting.",
                isOfflineFallback = true
            )

        val detected = try {
            detectObjects(bitmap)
        } finally {
            bitmap.recycle()
        }

        val matching =
            objectDetector.findObjects(detected, targetObject)

        val count = matching.size

        val spoken = if (count > 0) {
            val noun = if (count == 1)
                targetObject
            else
                "${targetObject}s"

            val positions = matching.joinToString(", ") {
                it.spokenPosition
            }

            "Offline detection: I see $count $noun located $positions."
        } else {
            "Offline detection: I do not see any $targetObject here."
        }

        VisionAnalysisResult(
            title = "Count: $targetObject",
            spokenText = spoken,
            fullDetails = spoken,
            isOfflineFallback = true,
            objectsDetected = matching
        )
    }

    /**
     * OCR text extraction using local ML Kit Text Recognition.
     */
    suspend fun recognizeText(
        imageFile: File
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {

        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext VisionAnalysisResult(
                title = "Text Recognition",
                spokenText = "No image is available to read text from. Please capture or select an image again.",
                isOfflineFallback = true
            )
        }

        val bitmap =
            imageProcessor.decodeAndNormalizeBitmap(imageFile)
                ?: return@withContext VisionAnalysisResult(
                    title = "Text Recognition",
                    spokenText = "Could not process image for text recognition.",
                    isOfflineFallback = true
                )

        val ocrText = try {
            ocrManager.extractText(bitmap)
        } finally {
            bitmap.recycle()
        }

        val spoken =
            ocrManager.formatOcrSpokenResponse(ocrText)

        VisionAnalysisResult(
            title = "Text Recognition",
            spokenText = spoken,
            fullDetails = if (ocrText.isNotBlank())
                ocrText
            else
                "No text detected.",
            isOfflineFallback = true
        )
    }

    /**
     * On-device combined object detection and OCR synthesis.
     */
    private suspend fun runOnDeviceSceneAnalysis(
        imageFile: File,
        userQuestion: String? = null
    ): VisionAnalysisResult {

        val bitmap =
            imageProcessor.decodeAndNormalizeBitmap(imageFile)
                ?: return VisionAnalysisResult(
                    title = "On-Device Analysis",
                    spokenText = "Unable to decode image on device.",
                    isOfflineFallback = true
                )

        val detectedObjects: List<DetectedObjectInfo>
        val ocrText: String

        try {
            detectedObjects = detectObjects(bitmap)
            ocrText = ocrManager.extractText(bitmap)
        } finally {
            bitmap.recycle()
        }

        val objectSummary =
            objectDetector.formatSpokenObjectSummary(detectedObjects)

        val spoken = buildString {

            if (!userQuestion.isNullOrBlank()) {
                append("Offline response for \"$userQuestion\": ")
            } else {
                append("On-device analysis: ")
            }

            if (objectSummary.isNotBlank()) {
                append("I detect $objectSummary.")
            } else {
                append("No prominent objects clearly recognized.")
            }

            if (ocrText.isNotBlank()) {
                val shortText =
                    if (ocrText.length > 80)
                        ocrText.take(80) + "..."
                    else
                        ocrText

                append(" Visible text: \"$shortText\".")
            }
        }.trim()

        return VisionAnalysisResult(
            title = "On-Device Scene Analysis",
            spokenText = spoken,
            fullDetails = spoken,
            isOfflineFallback = true,
            objectsDetected = detectedObjects
        )
    }

    fun processSelectedImageUri(
        uri: Uri,
        targetFile: File
    ): Boolean {
        return imageProcessor.processSelectedUri(uri, targetFile)
    }

    fun cleanupTemporaryFiles(vararg keepFiles: File?) {
        imageProcessor.cleanupTemporaryFiles(*keepFiles)
    }

    fun close() {
        yoloDetector.close()
        objectDetector.close()
        ocrManager.close()
    }
}
