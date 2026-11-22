
package com.hitha.visionassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.hitha.visionassist.data.model.DetectedObjectInfo
import com.hitha.visionassist.data.model.HorizontalPosition
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class YoloEvaluationDetection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class YoloEvaluationResult(
    val detections: List<YoloEvaluationDetection>,
    val inferenceTimeMs: Long
)

class YoloDetector(context: Context) {

    companion object {
        private const val TAG = "YOLO_DEBUG"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private const val NUM_CLASSES = 80
        private const val MAX_DETECTIONS = 30

        private val LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane",
            "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat",
            "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon",
            "bowl", "banana", "apple", "sandwich", "orange", "broccoli",
            "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet",
            "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
        )
    }

    private val interpreter: Interpreter
    private val inputIsNchw: Boolean

    private data class Candidate(
        val classId: Int,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class PreparedImage(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class InferenceOutput(
        val candidates: List<Candidate>,
        val inferenceTimeMs: Long
    )

    init {
        val modelBytes = context.applicationContext.assets
            .open("yolo11n.tflite")
            .use { it.readBytes() }

        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())

        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }

        interpreter = Interpreter(modelBuffer, options)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()

        val isNchw = inputShape.contentEquals(
            intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE)
        )

        val isNhwc = inputShape.contentEquals(
            intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3)
        )

        require(isNchw || isNhwc) {
            "Unsupported YOLO input shape: ${inputShape.contentToString()}"
        }

        require(inputTensor.dataType() == DataType.FLOAT32) {
            "Expected FLOAT32 model input."
        }

        require(
            interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32
        ) {
            "Expected FLOAT32 model output."
        }

        inputIsNchw = isNchw

        Log.d(TAG, "YOLO model loaded")
        Log.d(TAG, "Input shape: ${inputShape.contentToString()}")
        Log.d(
            TAG,
            "Output shape: ${
                interpreter.getOutputTensor(0).shape().contentToString()
            }"
        )
    }

    fun detect(bitmap: Bitmap): List<DetectedObjectInfo> {
        val result = runDetection(bitmap)

        return result.candidates.map { candidate ->
            val box = Rect(
                candidate.left.toInt(),
                candidate.top.toInt(),
                candidate.right.toInt(),
                candidate.bottom.toInt()
            )

            val centerX =
                (box.left + box.right) / 2f / bitmap.width

            val position = when {
                centerX < 0.35f -> HorizontalPosition.LEFT
                centerX > 0.65f -> HorizontalPosition.RIGHT
                else -> HorizontalPosition.CENTER
            }

            val area = box.width().toFloat() * box.height().toFloat()

            val relativeArea =
                area / (bitmap.width.toFloat() * bitmap.height.toFloat())

            DetectedObjectInfo(
                label = LABELS[candidate.classId],
                confidence = candidate.confidence,
                position = position,
                boundingBox = box,
                isBackground = relativeArea < 0.04f
            )
        }
    }

    fun detectForEvaluation(bitmap: Bitmap): YoloEvaluationResult {
        val result = runDetection(bitmap)

        val detections = result.candidates.map { candidate ->
            YoloEvaluationDetection(
                classId = candidate.classId,
                label = LABELS[candidate.classId],
                confidence = candidate.confidence,
                left = candidate.left,
                top = candidate.top,
                right = candidate.right,
                bottom = candidate.bottom
            )
        }

        return YoloEvaluationResult(
            detections = detections,
            inferenceTimeMs = result.inferenceTimeMs
        )
    }

    @Synchronized
    private fun runDetection(bitmap: Bitmap): InferenceOutput {
        require(bitmap.width > 0 && bitmap.height > 0) {
            "Invalid input image."
        }

        val prepared = prepareImage(bitmap)

        try {
            val input = ByteBuffer.allocateDirect(
                INPUT_SIZE * INPUT_SIZE * 3 * 4
            ).order(ByteOrder.nativeOrder())

            if (inputIsNchw) {
                for (channel in 0 until 3) {
                    for (y in 0 until INPUT_SIZE) {
                        for (x in 0 until INPUT_SIZE) {
                            val pixel = prepared.bitmap.getPixel(x, y)

                            val value = when (channel) {
                                0 -> Color.red(pixel)
                                1 -> Color.green(pixel)
                                else -> Color.blue(pixel)
                            }

                            input.putFloat(value / 255f)
                        }
                    }
                }
            } else {
                for (y in 0 until INPUT_SIZE) {
                    for (x in 0 until INPUT_SIZE) {
                        val pixel = prepared.bitmap.getPixel(x, y)

                        input.putFloat(Color.red(pixel) / 255f)
                        input.putFloat(Color.green(pixel) / 255f)
                        input.putFloat(Color.blue(pixel) / 255f)
                    }
                }
            }

            input.rewind()

            val outputShape = interpreter.getOutputTensor(0).shape()

            require(outputShape.size == 3 && outputShape[0] == 1) {
                "Unexpected YOLO output shape: ${outputShape.contentToString()}"
            }

            val outputSize = outputShape.fold(1) { a, b -> a * b }

            val output = ByteBuffer.allocateDirect(outputSize * 4)
                .order(ByteOrder.nativeOrder())

            val startTime = SystemClock.elapsedRealtimeNanos()
            interpreter.run(input, output)
            val endTime = SystemClock.elapsedRealtimeNanos()

            val inferenceTimeMs =
                ((endTime - startTime) / 1_000_000L).coerceAtLeast(0L)

            output.rewind()

            val values = FloatArray(outputSize)
            output.asFloatBuffer().get(values)

            Log.d(TAG, "Output shape: ${outputShape.contentToString()}")
            Log.d(TAG, "Output min: ${values.minOrNull()}")
            Log.d(TAG, "Output max: ${values.maxOrNull()}")

            val candidates = decodeOutput(
                values,
                outputShape,
                bitmap.width,
                bitmap.height,
                prepared
            )

            val finalCandidates = applyNms(candidates)

            Log.d(TAG, "Candidates before NMS: ${candidates.size}")
            Log.d(TAG, "Detections after NMS: ${finalCandidates.size}")

            finalCandidates.take(5).forEachIndexed { index, candidate ->
                Log.d(
                    TAG,
                    "Detection $index: ${candidate.classId}, " +
                            "${candidate.confidence}, " +
                            "${candidate.left}, ${candidate.top}, " +
                            "${candidate.right}, ${candidate.bottom}"
                )
            }

            return InferenceOutput(
                candidates = finalCandidates,
                inferenceTimeMs = inferenceTimeMs
            )

        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun prepareImage(bitmap: Bitmap): PreparedImage {
        val scale = min(
            INPUT_SIZE.toFloat() / bitmap.width,
            INPUT_SIZE.toFloat() / bitmap.height
        )

        val newWidth = max(1, (bitmap.width * scale).toInt())
        val newHeight = max(1, (bitmap.height * scale).toInt())

        val padX = (INPUT_SIZE - newWidth) / 2f
        val padY = (INPUT_SIZE - newHeight) / 2f

        val resized = Bitmap.createBitmap(
            INPUT_SIZE,
            INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(resized)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(
            bitmap,
            null,
            RectF(
                padX,
                padY,
                padX + newWidth,
                padY + newHeight
            ),
            paint
        )

        return PreparedImage(
            bitmap = resized,
            scale = scale,
            padX = padX,
            padY = padY
        )
    }

    private fun decodeOutput(
        data: FloatArray,
        shape: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        prepared: PreparedImage
    ): List<Candidate> {

        require(shape.size == 3 && shape[0] == 1) {
            "Unexpected YOLO output shape: ${shape.contentToString()}"
        }

        val channelsFirst = shape[1] == NUM_CLASSES + 4

        val channels = if (channelsFirst) shape[1] else shape[2]
        val boxes = if (channelsFirst) shape[2] else shape[1]

        require(channels == NUM_CLASSES + 4) {
            "Expected 84 output channels, got $channels"
        }

        fun value(box: Int, channel: Int): Float {
            return if (channelsFirst) {
                data[channel * boxes + box]
            } else {
                data[box * channels + channel]
            }
        }

        // Inspect only the four box-coordinate channels.
        var maximumCoordinate = 0f

        for (box in 0 until boxes) {
            for (channel in 0 until 4) {
                val coordinate = value(box, channel)

                if (coordinate.isFinite()) {
                    maximumCoordinate = max(
                        maximumCoordinate,
                        abs(coordinate)
                    )
                }
            }
        }

        // Normalized coordinates must be converted to 640-pixel coordinates.
        val coordinateScale = if (maximumCoordinate <= 2f) {
            INPUT_SIZE.toFloat()
        } else {
            1f
        }

        Log.d(
            TAG,
            "Coordinate max=$maximumCoordinate, scale=$coordinateScale"
        )

        val results = mutableListOf<Candidate>()

        for (i in 0 until boxes) {
            var bestClass = -1
            var bestScore = 0f

            for (classId in 0 until NUM_CLASSES) {
                val score = value(i, classId + 4)

                if (score.isFinite() && score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }

            if (
                bestClass < 0 ||
                bestScore < CONFIDENCE_THRESHOLD
            ) {
                continue
            }

            val cx = value(i, 0) * coordinateScale
            val cy = value(i, 1) * coordinateScale
            val width = value(i, 2) * coordinateScale
            val height = value(i, 3) * coordinateScale

            if (
                !cx.isFinite() ||
                !cy.isFinite() ||
                !width.isFinite() ||
                !height.isFinite() ||
                width <= 0f ||
                height <= 0f
            ) {
                continue
            }

            val modelLeft = cx - width / 2f
            val modelTop = cy - height / 2f
            val modelRight = cx + width / 2f
            val modelBottom = cy + height / 2f

            val left = (
                    (modelLeft - prepared.padX) / prepared.scale
                    ).coerceIn(0f, imageWidth.toFloat())

            val top = (
                    (modelTop - prepared.padY) / prepared.scale
                    ).coerceIn(0f, imageHeight.toFloat())

            val right = (
                    (modelRight - prepared.padX) / prepared.scale
                    ).coerceIn(0f, imageWidth.toFloat())

            val bottom = (
                    (modelBottom - prepared.padY) / prepared.scale
                    ).coerceIn(0f, imageHeight.toFloat())

            if (right <= left || bottom <= top) {
                continue
            }

            results.add(
                Candidate(
                    classId = bestClass,
                    confidence = bestScore,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
            )
        }

        Log.d(TAG, "Decoded candidates: ${results.size}")

        return results
    }

    private fun applyNms(
        candidates: List<Candidate>
    ): List<Candidate> {
        val selected = mutableListOf<Candidate>()

        for (classId in 0 until NUM_CLASSES) {
            val remaining = candidates
                .filter { it.classId == classId }
                .sortedByDescending { it.confidence }
                .toMutableList()

            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0)
                selected.add(best)

                if (selected.size >= 100) break

                remaining.removeAll { other ->
                    calculateIoU(best, other) > IOU_THRESHOLD
                }
            }

            if (selected.size >= 100) break
        }

        return selected
            .sortedByDescending { it.confidence }
            .take(MAX_DETECTIONS)
    }

    private fun calculateIoU(
        a: Candidate,
        b: Candidate
    ): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val intersection =
            max(0f, right - left) * max(0f, bottom - top)

        val areaA =
            (a.right - a.left) * (a.bottom - a.top)

        val areaB =
            (b.right - b.left) * (b.bottom - b.top)

        val union = areaA + areaB - intersection

        return if (union <= 0f) {
            0f
        } else {
            intersection / union
        }
    }

    @Synchronized
    fun close() {
        interpreter.close()
    }
}
