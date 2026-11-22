
package com.hitha.visionassist.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.hitha.visionassist.data.model.DetectedObjectInfo
import com.hitha.visionassist.data.model.HorizontalPosition
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Raw detection information for model evaluation.
 */
data class EvaluationDetection(
    val label: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Result returned by the evaluation detector.
 */
data class EvaluationResult(
    val detections: List<EvaluationDetection>,
    val inferenceTimeMs: Double
)

/**
 * On-device visual object detection and spatial horizontal
 * classification using Google ML Kit.
 */
class ObjectDetectionEngine {

    private var detectorInstance: ObjectDetector? = null

    private fun getDetector(): ObjectDetector {
        if (detectorInstance == null) {
            detectorInstance = ObjectDetection.getClient(
                ObjectDetectorOptions.Builder()
                    .setDetectorMode(
                        ObjectDetectorOptions.SINGLE_IMAGE_MODE
                    )
                    .enableMultipleObjects()
                    .enableClassification()
                    .build()
            )
        }

        return detectorInstance!!
    }

    /**
     * Original application detection function.
     * Keeps accessible labels and existing deduplication.
     */
    suspend fun detectObjects(
        bitmap: Bitmap
    ): List<DetectedObjectInfo> =
        suspendCancellableCoroutine { continuation ->

            val inputImage = InputImage.fromBitmap(bitmap, 0)

            getDetector().process(inputImage)
                .addOnSuccessListener { objects ->

                    val imageWidth = bitmap.width.toFloat()
                    val imageHeight = bitmap.height.toFloat()

                    val result = mutableListOf<DetectedObjectInfo>()

                    for (obj in objects) {

                        val box = obj.boundingBox

                        val labelInfo = obj.labels.maxByOrNull {
                            it.confidence
                        }

                        val rawLabel = labelInfo?.text ?: "object"
                        val confidence = labelInfo?.confidence ?: 0.5f

                        val position = calculateHorizontalPosition(
                            box,
                            imageWidth
                        )

                        val boxArea =
                            (box.right - box.left).toFloat() *
                                    (box.bottom - box.top).toFloat()

                        val relativeArea =
                            boxArea / (imageWidth * imageHeight)

                        val isBackground = relativeArea < 0.04f

                        val friendlyName = mapToAccessibleLabel(rawLabel)

                        result.add(
                            DetectedObjectInfo(
                                label = friendlyName,
                                confidence = confidence,
                                position = position,
                                boundingBox = box,
                                isBackground = isBackground
                            )
                        )
                    }

                    if (continuation.isActive) {
                        continuation.resume(
                            deduplicateObjects(result)
                        )
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
        }

    /**
     * Separate raw detection function for evaluation.
     *
     * Does not apply accessible-label conversion or deduplication.
     * Records ML Kit inference time and original bounding boxes.
     */
    suspend fun detectObjectsForEvaluation(
        bitmap: Bitmap
    ): EvaluationResult =
        suspendCancellableCoroutine { continuation ->

            val inputImage = InputImage.fromBitmap(bitmap, 0)

            val startTime = SystemClock.elapsedRealtimeNanos()

            getDetector().process(inputImage)
                .addOnSuccessListener { objects ->

                    val endTime = SystemClock.elapsedRealtimeNanos()

                    val inferenceTimeMs =
                        (endTime - startTime) / 1_000_000.0

                    val predictions = objects.mapNotNull { obj ->

                        val labelInfo = obj.labels.maxByOrNull {
                            it.confidence
                        } ?: return@mapNotNull null

                        val box = obj.boundingBox

                        EvaluationDetection(
                            label = labelInfo.text.lowercase(Locale.US),
                            confidence = labelInfo.confidence,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom
                        )
                    }

                    if (continuation.isActive) {
                        continuation.resume(
                            EvaluationResult(
                                detections = predictions,
                                inferenceTimeMs = inferenceTimeMs
                            )
                        )
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(
                            EvaluationResult(
                                detections = emptyList(),
                                inferenceTimeMs = 0.0
                            )
                        )
                    }
                }
        }

    /**
     * Calculates horizontal position using the center X coordinate.
     */
    fun calculateHorizontalPosition(
        boxLeft: Float,
        boxRight: Float,
        imageWidth: Float
    ): HorizontalPosition {

        if (imageWidth <= 0f) {
            return HorizontalPosition.CENTER
        }

        val centerX =
            ((boxLeft + boxRight) / 2.0f) / imageWidth

        return when {
            centerX < 0.35f -> HorizontalPosition.LEFT
            centerX > 0.65f -> HorizontalPosition.RIGHT
            else -> HorizontalPosition.CENTER
        }
    }

    fun calculateHorizontalPosition(
        box: Rect,
        imageWidth: Float
    ): HorizontalPosition {

        return calculateHorizontalPosition(
            box.left.toFloat(),
            box.right.toFloat(),
            imageWidth
        )
    }

    /**
     * Finds objects based on the requested target.
     */
    fun findObjects(
        objects: List<DetectedObjectInfo>,
        target: String
    ): List<DetectedObjectInfo> {

        val query = target.lowercase(Locale.US).trim()

        val querySingular =
            if (query.endsWith("s") && query.length > 3) {
                query.dropLast(1)
            } else {
                query
            }

        return objects.filter { item ->

            val label = item.label.lowercase(Locale.US)

            val labelSingular =
                if (label.endsWith("s") && label.length > 3) {
                    label.dropLast(1)
                } else {
                    label
                }

            label == query ||
                    label.contains(query) ||
                    query.contains(label) ||
                    labelSingular == querySingular ||
                    isSynonymMatch(label, query)
        }
    }

    private fun isSynonymMatch(
        label: String,
        query: String
    ): Boolean {

        val personSynonyms = setOf(
            "person", "people", "human", "man",
            "woman", "someone", "child", "individual"
        )

        val chairSynonyms = setOf(
            "chair", "chairs", "seat",
            "seating", "armchair", "stool"
        )

        val deskSynonyms = setOf(
            "table", "desk", "workbench", "counter"
        )

        val phoneSynonyms = setOf(
            "phone", "cellphone", "smartphone", "mobile"
        )

        val computerSynonyms = setOf(
            "laptop", "computer", "notebook",
            "pc", "monitor", "screen"
        )

        val bottleSynonyms = setOf(
            "bottle", "cup", "mug",
            "flask", "tumbler", "drink"
        )

        val synonymGroups = listOf(
            personSynonyms,
            chairSynonyms,
            deskSynonyms,
            phoneSynonyms,
            computerSynonyms,
            bottleSynonyms
        )

        return synonymGroups.any { group ->

            val hasLabel = group.any {
                label.contains(it)
            }

            val hasQuery = group.any {
                query.contains(it)
            }

            hasLabel && hasQuery
        }
    }

    /**
     * Counts occurrences of a target object.
     */
    fun countObjects(
        objects: List<DetectedObjectInfo>,
        target: String
    ): Int {

        return findObjects(objects, target).size
    }

    /**
     * Creates a concise spoken object summary.
     */
    fun formatSpokenObjectSummary(
        objects: List<DetectedObjectInfo>
    ): String {

        if (objects.isEmpty()) return ""

        val foreground = objects.filter {
            !it.isBackground
        }

        val background = objects.filter {
            it.isBackground
        }

        val phrases = mutableListOf<String>()

        for (item in foreground.take(5)) {

            val article =
                if (
                    item.label.startsWith("a") ||
                    item.label.startsWith("e") ||
                    item.label.startsWith("i") ||
                    item.label.startsWith("o") ||
                    item.label.startsWith("u")
                ) {
                    "an"
                } else {
                    "a"
                }

            phrases.add(
                "$article ${item.label} ${item.spokenPosition}"
            )
        }

        if (background.isNotEmpty()) {

            val bgCount = background.size

            val noun =
                if (bgCount == 1) "object" else "objects"

            phrases.add(
                "$bgCount $noun in the background"
            )
        }

        return phrases.joinToString(separator = ", ")
    }

    /**
     * Removes redundant detections for normal application output.
     */
    private fun deduplicateObjects(
        objects: List<DetectedObjectInfo>
    ): List<DetectedObjectInfo> {

        val unique = mutableListOf<DetectedObjectInfo>()

        for (item in objects) {

            val duplicate = unique.any { existing ->

                existing.label.equals(
                    item.label,
                    ignoreCase = true
                ) &&
                        existing.position == item.position &&
                        Math.abs(
                            existing.confidence - item.confidence
                        ) < 0.15f
            }

            if (!duplicate) {
                unique.add(item)
            }
        }

        return unique
    }

    /**
     * Maps ML Kit labels to accessible spoken labels.
     */
    private fun mapToAccessibleLabel(
        mlKitLabel: String
    ): String {

        return when (mlKitLabel.lowercase(Locale.US)) {

            "home good" -> "household item"
            "fashion good" -> "clothing or accessory"
            "food" -> "food item"
            "place" -> "structure"
            "plant" -> "plant"

            else -> mlKitLabel.lowercase(Locale.US)
        }
    }

    /**
     * Releases the ML Kit object detector.
     */
    fun close() {

        try {
            detectorInstance?.close()
            detectorInstance = null
        } catch (_: Exception) {
        }
    }
}
