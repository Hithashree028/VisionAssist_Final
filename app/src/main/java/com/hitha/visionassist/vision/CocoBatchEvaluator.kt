
package com.hitha.visionassist.vision

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class CocoEvaluationSummary(
    val totalImages: Int,
    val processedImages: Int,
    val failedImages: Int,
    val totalGroundTruth: Int,
    val totalPredictions: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double,
    val recall: Double,
    val f1Score: Double,
    val objectDetectionAccuracy: Double,
    val imageLevelAccuracy: Double,
    val imageLevelTruePositives: Int,
    val imageLevelTrueNegatives: Int,
    val imageLevelFalsePositives: Int,
    val imageLevelFalseNegatives: Int,
    val averageInferenceTimeMs: Double,
    val csvFile: String
)

private data class CocoImage(
    val id: Long,
    val fileName: String,
    val width: Int,
    val height: Int
)

private data class EvaluationBox(
    val categoryId: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

private data class MatchCandidate(
    val predictionIndex: Int,
    val groundTruthIndex: Int,
    val iou: Double
)

class CocoBatchEvaluator(
    private val detector: YoloDetector
) {

    private val yoloToCocoCategoryId = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        11, 13, 14, 15, 16, 17, 18, 19, 20, 21,
        22, 23, 24, 25, 27, 28, 31, 32, 33, 34,
        35, 36, 37, 38, 39, 40, 41, 42, 43, 44,
        46, 47, 48, 49, 50, 51, 52, 53, 54, 55,
        56, 57, 58, 59, 60, 61, 62, 63, 64, 65,
        67, 70, 72, 73, 74, 75, 76, 77, 78, 79,
        80, 81, 82, 84, 85, 86, 87, 88, 89, 90
    )

    suspend fun evaluate(
        imagesDirectory: File,
        annotationFile: File,
        outputCsv: File,
        iouThreshold: Double = 0.50
    ): CocoEvaluationSummary = withContext(Dispatchers.IO) {

        require(imagesDirectory.exists() && imagesDirectory.isDirectory) {
            "Images directory does not exist: ${imagesDirectory.absolutePath}"
        }

        require(annotationFile.exists() && annotationFile.isFile) {
            "COCO annotation file does not exist."
        }

        require(iouThreshold in 0.0..1.0) {
            "IoU threshold must be between 0 and 1."
        }

        val coco = JSONObject(annotationFile.readText())

        val imageArray = coco.getJSONArray("images")
        val annotationArray = coco.getJSONArray("annotations")

        val images = linkedMapOf<Long, CocoImage>()

        for (i in 0 until imageArray.length()) {
            val item = imageArray.getJSONObject(i)

            val image = CocoImage(
                id = item.getLong("id"),
                fileName = item.getString("file_name"),
                width = item.optInt("width", 0),
                height = item.optInt("height", 0)
            )

            images[image.id] = image
        }

        val groundTruth =
            mutableMapOf<Long, MutableList<EvaluationBox>>()

        for (i in 0 until annotationArray.length()) {

            val annotation = annotationArray.getJSONObject(i)

            if (annotation.optInt("iscrowd", 0) == 1) {
                continue
            }

            val imageId = annotation.getLong("image_id")
            val categoryId = annotation.getInt("category_id")
            val bbox = annotation.getJSONArray("bbox")

            if (bbox.length() < 4) continue

            val x = bbox.getDouble(0).toFloat()
            val y = bbox.getDouble(1).toFloat()
            val width = bbox.getDouble(2).toFloat()
            val height = bbox.getDouble(3).toFloat()

            if (
                !x.isFinite() ||
                !y.isFinite() ||
                !width.isFinite() ||
                !height.isFinite() ||
                width <= 0f ||
                height <= 0f
            ) {
                continue
            }

            val box = EvaluationBox(
                categoryId = categoryId,
                left = x,
                top = y,
                right = x + width,
                bottom = y + height
            )

            groundTruth.getOrPut(imageId) {
                mutableListOf()
            }.add(box)
        }

        var processedImages = 0
        var failedImages = 0

        var totalGroundTruth = 0
        var totalPredictions = 0

        var totalTP = 0
        var totalFP = 0
        var totalFN = 0

        // Image-level class-presence counters.
        var imageTP = 0
        var imageTN = 0
        var imageFP = 0
        var imageFN = 0

        var totalInferenceTime = 0.0
        var validInferenceMeasurements = 0

        val csv = StringBuilder()

        csv.appendLine(
            "image_name,ground_truth_count,prediction_count," +
                    "true_positives,false_positives,false_negatives," +
                    "inference_time_ms"
        )

        for ((imageId, imageInfo) in images) {

            val imageFile = File(
                imagesDirectory,
                imageInfo.fileName
            )

            if (!imageFile.exists()) {
                failedImages++

                csv.appendLine(
                    "${csvEscape(imageInfo.fileName)},0,0,0,0,0,FAILED_IMAGE_MISSING"
                )

                continue
            }

            val bitmap = BitmapFactory.decodeFile(
                imageFile.absolutePath
            )

            if (bitmap == null) {
                failedImages++

                csv.appendLine(
                    "${csvEscape(imageInfo.fileName)},0,0,0,0,0,FAILED_IMAGE_DECODE"
                )

                continue
            }

            try {

                // YOLO11n TFLite inference.
                val result = detector.detectForEvaluation(bitmap)

                processedImages++

                totalInferenceTime += result.inferenceTimeMs.toDouble()
                validInferenceMeasurements++

                val predictions = result.detections.mapNotNull { detection ->

                    val cocoCategoryId =
                        yoloToCocoCategoryId.getOrNull(detection.classId)
                            ?: return@mapNotNull null

                    EvaluationBox(
                        categoryId = cocoCategoryId,
                        left = detection.left,
                        top = detection.top,
                        right = detection.right,
                        bottom = detection.bottom
                    )
                }

                val originalGroundTruth =
                    groundTruth[imageId] ?: emptyList()

                val scaleX =
                    if (imageInfo.width > 0) {
                        bitmap.width.toFloat() / imageInfo.width
                    } else {
                        1f
                    }

                val scaleY =
                    if (imageInfo.height > 0) {
                        bitmap.height.toFloat() / imageInfo.height
                    } else {
                        1f
                    }

                val scaledGroundTruth = originalGroundTruth.map { box ->

                    EvaluationBox(
                        categoryId = box.categoryId,
                        left = box.left * scaleX,
                        top = box.top * scaleY,
                        right = box.right * scaleX,
                        bottom = box.bottom * scaleY
                    )
                }

                // IMAGE-LEVEL CLASS-PRESENCE ACCURACY
                // Each image is checked for the presence or absence
                // of each of the 80 COCO object categories.

                val actualClasses = scaledGroundTruth
                    .map { it.categoryId }
                    .toSet()

                val predictedClasses = predictions
                    .map { it.categoryId }
                    .toSet()

                for (categoryId in yoloToCocoCategoryId) {

                    val actualPresent =
                        categoryId in actualClasses

                    val predictedPresent =
                        categoryId in predictedClasses

                    when {
                        actualPresent && predictedPresent -> imageTP++

                        !actualPresent && !predictedPresent -> imageTN++

                        !actualPresent && predictedPresent -> imageFP++

                        actualPresent && !predictedPresent -> imageFN++
                    }
                }

                // OBJECT-LEVEL MATCHING
                val counts = matchBoxes(
                    predictions = predictions,
                    groundTruth = scaledGroundTruth,
                    threshold = iouThreshold
                )

                totalGroundTruth += scaledGroundTruth.size
                totalPredictions += predictions.size

                totalTP += counts.first
                totalFP += counts.second
                totalFN += counts.third

                csv.appendLine(
                    listOf(
                        csvEscape(imageInfo.fileName),
                        scaledGroundTruth.size,
                        predictions.size,
                        counts.first,
                        counts.second,
                        counts.third,
                        String.format(
                            Locale.US,
                            "%.3f",
                            result.inferenceTimeMs.toDouble()
                        )
                    ).joinToString(",")
                )

            } catch (exception: Exception) {

                failedImages++

                csv.appendLine(
                    "${csvEscape(imageInfo.fileName)},0,0,0,0,0," +
                            "FAILED_${csvEscape(exception.message ?: "UNKNOWN")}"
                )

            } finally {
                bitmap.recycle()
            }
        }

        // OBJECT-LEVEL METRICS

        val precision = safeDivide(
            totalTP.toDouble(),
            (totalTP + totalFP).toDouble()
        )

        val recall = safeDivide(
            totalTP.toDouble(),
            (totalTP + totalFN).toDouble()
        )

        val f1Score =
            if (precision + recall > 0.0) {
                2.0 * precision * recall / (precision + recall)
            } else {
                0.0
            }

        // Jaccard-style object detection score.
        // This is not conventional classification accuracy.

        val objectDetectionAccuracy = safeDivide(
            totalTP.toDouble(),
            (totalTP + totalFP + totalFN).toDouble()
        )

        // IMAGE-LEVEL CLASS-PRESENCE ACCURACY

        val imageLevelAccuracy = safeDivide(
            (imageTP + imageTN).toDouble(),
            (imageTP + imageTN + imageFP + imageFN).toDouble()
        ) * 100.0

        val averageInferenceTime =
            if (validInferenceMeasurements > 0) {
                totalInferenceTime / validInferenceMeasurements
            } else {
                0.0
            }

        // CSV SUMMARY

        csv.appendLine()
        csv.appendLine("EVALUATION_SUMMARY")
        csv.appendLine("Model,YOLO11n_TFLite")
        csv.appendLine("IoU_threshold,$iouThreshold")
        csv.appendLine("Total_images,${images.size}")
        csv.appendLine("Processed_images,$processedImages")
        csv.appendLine("Failed_images,$failedImages")
        csv.appendLine("Ground_truth_objects,$totalGroundTruth")
        csv.appendLine("Predicted_objects,$totalPredictions")
        csv.appendLine("True_positives,$totalTP")
        csv.appendLine("False_positives,$totalFP")
        csv.appendLine("False_negatives,$totalFN")

        csv.appendLine("Precision_percent,${precision * 100.0}")
        csv.appendLine("Recall_percent,${recall * 100.0}")
        csv.appendLine("F1_score_percent,${f1Score * 100.0}")

        csv.appendLine(
            "Object_detection_jaccard_percent," +
                    "${objectDetectionAccuracy * 100.0}"
        )

        csv.appendLine(
            "Image_level_class_presence_accuracy_percent," +
                    imageLevelAccuracy
        )

        csv.appendLine("Image_level_TP,$imageTP")
        csv.appendLine("Image_level_TN,$imageTN")
        csv.appendLine("Image_level_FP,$imageFP")
        csv.appendLine("Image_level_FN,$imageFN")

        csv.appendLine(
            "Average_inference_time_ms,$averageInferenceTime"
        )

        outputCsv.parentFile?.mkdirs()
        outputCsv.writeText(csv.toString())

        CocoEvaluationSummary(
            totalImages = images.size,
            processedImages = processedImages,
            failedImages = failedImages,
            totalGroundTruth = totalGroundTruth,
            totalPredictions = totalPredictions,
            truePositives = totalTP,
            falsePositives = totalFP,
            falseNegatives = totalFN,
            precision = precision * 100.0,
            recall = recall * 100.0,
            f1Score = f1Score * 100.0,
            objectDetectionAccuracy = objectDetectionAccuracy * 100.0,
            imageLevelAccuracy = imageLevelAccuracy,
            imageLevelTruePositives = imageTP,
            imageLevelTrueNegatives = imageTN,
            imageLevelFalsePositives = imageFP,
            imageLevelFalseNegatives = imageFN,
            averageInferenceTimeMs = averageInferenceTime,
            csvFile = outputCsv.absolutePath
        )
    }

    private fun matchBoxes(
        predictions: List<EvaluationBox>,
        groundTruth: List<EvaluationBox>,
        threshold: Double
    ): Triple<Int, Int, Int> {

        val candidates = mutableListOf<MatchCandidate>()

        for (p in predictions.indices) {
            for (g in groundTruth.indices) {

                if (
                    predictions[p].categoryId !=
                    groundTruth[g].categoryId
                ) {
                    continue
                }

                val iou = calculateIoU(
                    predictions[p],
                    groundTruth[g]
                )

                if (iou >= threshold) {
                    candidates.add(
                        MatchCandidate(
                            predictionIndex = p,
                            groundTruthIndex = g,
                            iou = iou
                        )
                    )
                }
            }
        }

        val sortedCandidates =
            candidates.sortedByDescending { it.iou }

        val matchedPredictions = mutableSetOf<Int>()
        val matchedGroundTruth = mutableSetOf<Int>()

        for (candidate in sortedCandidates) {

            if (
                candidate.predictionIndex !in matchedPredictions &&
                candidate.groundTruthIndex !in matchedGroundTruth
            ) {
                matchedPredictions.add(candidate.predictionIndex)
                matchedGroundTruth.add(candidate.groundTruthIndex)
            }
        }

        val truePositives = matchedPredictions.size
        val falsePositives = predictions.size - truePositives
        val falseNegatives = groundTruth.size - truePositives

        return Triple(
            truePositives,
            falsePositives,
            falseNegatives
        )
    }

    private fun calculateIoU(
        a: EvaluationBox,
        b: EvaluationBox
    ): Double {

        val intersectionLeft = max(a.left, b.left)
        val intersectionTop = max(a.top, b.top)
        val intersectionRight = min(a.right, b.right)
        val intersectionBottom = min(a.bottom, b.bottom)

        val intersectionWidth =
            max(0f, intersectionRight - intersectionLeft)

        val intersectionHeight =
            max(0f, intersectionBottom - intersectionTop)

        val intersectionArea =
            intersectionWidth * intersectionHeight

        val areaA =
            max(0f, a.right - a.left) *
                    max(0f, a.bottom - a.top)

        val areaB =
            max(0f, b.right - b.left) *
                    max(0f, b.bottom - b.top)

        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0f) {
            intersectionArea.toDouble() / unionArea.toDouble()
        } else {
            0.0
        }
    }

    private fun safeDivide(
        numerator: Double,
        denominator: Double
    ): Double {
        return if (denominator > 0.0) {
            numerator / denominator
        } else {
            0.0
        }
    }

    private fun csvEscape(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
