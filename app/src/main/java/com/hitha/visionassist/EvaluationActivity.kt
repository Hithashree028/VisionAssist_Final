
package com.hitha.visionassist

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.hitha.visionassist.vision.CocoBatchEvaluator
import com.hitha.visionassist.vision.YoloDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class EvaluationActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var folderButton: Button
    private lateinit var jsonButton: Button
    private lateinit var runButton: Button

    private var imagesFolder: File? = null
    private var annotationFile: File? = null

    private lateinit var detector: YoloDetector

    // Select COCO image folder
    private val folderPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->

            if (uri != null) {

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Continue if persistable permission is unavailable.
                }

                lifecycleScope.launch {

                    try {

                        statusText.text =
                            "Copying image folder to VisionAssist..."

                        val destination = withContext(Dispatchers.IO) {

                            val output = File(
                                filesDir,
                                "VisionAssistEvaluation/val2017"
                            )

                            output.deleteRecursively()
                            output.mkdirs()

                            val documentFolder =
                                DocumentFile.fromTreeUri(
                                    this@EvaluationActivity,
                                    uri
                                ) ?: error("Cannot access selected folder")

                            copyFolderContents(
                                documentFolder,
                                output
                            )

                            output
                        }

                        imagesFolder = destination

                        val imageCount =
                            destination.listFiles()?.count {
                                it.isFile &&
                                        it.extension.lowercase(Locale.US) in
                                        listOf("jpg", "jpeg", "png")
                            } ?: 0

                        statusText.text =
                            "Image folder selected.\n" +
                                    "Images copied: $imageCount\n\n" +
                                    "Now select the COCO JSON file."

                        updateButtons()

                    } catch (e: Exception) {

                        statusText.text =
                            "Folder error: ${e.localizedMessage}"
                    }
                }
            }
        }

    // Select COCO annotation JSON
    private val jsonPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                lifecycleScope.launch {

                    try {

                        statusText.text =
                            "Copying annotation file..."

                        val destination = withContext(Dispatchers.IO) {

                            val output = File(
                                filesDir,
                                "VisionAssistEvaluation/ground_truth_subset.json"
                            )

                            output.parentFile?.mkdirs()

                            val inputStream =
                                contentResolver.openInputStream(uri)
                                    ?: error(
                                        "Unable to open selected JSON file"
                                    )

                            inputStream.use { input ->

                                FileOutputStream(output).use { stream ->
                                    input.copyTo(stream)
                                }
                            }

                            output
                        }

                        annotationFile = destination

                        statusText.text =
                            "COCO annotation file selected successfully."

                        updateButtons()

                    } catch (e: Exception) {

                        statusText.text =
                            "JSON error: ${e.localizedMessage}"
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createEvaluationScreen()

        try {

            detector = YoloDetector(applicationContext)

            statusText.text =
                "YOLO11n initialized successfully.\n\n" +
                        "Select the COCO image folder and JSON annotation file."

        } catch (e: Exception) {

            statusText.text =
                "YOLO11n initialization failed:\n${e.localizedMessage}"

            runButton.isEnabled = false
        }

        updateButtons()
    }

    private fun createEvaluationScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        val title = TextView(this).apply {
            text = "VisionAssist Model Evaluation"
            textSize = 23f
        }

        statusText = TextView(this).apply {
            text =
                "Select the COCO image folder and JSON annotation file."

            textSize = 16f
            setPadding(0, 28, 0, 28)
        }

        folderButton = Button(this).apply {

            text = "1. Select COCO Image Folder"

            setOnClickListener {
                folderPicker.launch(null)
            }
        }

        jsonButton = Button(this).apply {

            text = "2. Select Ground Truth JSON"

            setOnClickListener {
                jsonPicker.launch(
                    arrayOf("application/json", "text/plain")
                )
            }
        }

        runButton = Button(this).apply {

            text = "3. Run Evaluation"
            isEnabled = false

            setOnClickListener {
                runEvaluation()
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(folderButton)
        root.addView(jsonButton)
        root.addView(runButton)

        val scrollView = ScrollView(this).apply {
            addView(root)
        }

        setContentView(scrollView)

        updateButtons()
    }

    private fun updateButtons() {

        runButton.isEnabled =
            ::detector.isInitialized &&
                    imagesFolder?.exists() == true &&
                    annotationFile?.exists() == true
    }

    /**
     * Copies a selected Android document folder recursively
     * into application-private storage.
     */
    private fun copyFolderContents(
        source: DocumentFile,
        destination: File
    ) {

        for (document in source.listFiles()) {

            if (document.isDirectory) {

                val childFolder = File(
                    destination,
                    document.name ?: "folder"
                )

                childFolder.mkdirs()

                copyFolderContents(
                    document,
                    childFolder
                )

            } else if (document.isFile) {

                val name = document.name ?: continue

                val outputFile = File(destination, name)

                contentResolver.openInputStream(document.uri)?.use { input ->

                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun runEvaluation() {

        val imageDirectory = imagesFolder
        val jsonFile = annotationFile

        if (
            imageDirectory == null ||
            jsonFile == null ||
            !::detector.isInitialized
        ) {

            statusText.text =
                "Please select both files and check YOLO11n initialization."

            return
        }

        folderButton.isEnabled = false
        jsonButton.isEnabled = false
        runButton.isEnabled = false

        statusText.text =
            "YOLO11n evaluation started.\n" +
                    "Please keep VisionAssist open. " +
                    "This may take several minutes."

        lifecycleScope.launch {

            try {

                val reportDirectory = File(
                    getExternalFilesDir(null),
                    "VisionAssistReports"
                )

                reportDirectory.mkdirs()

                val csvFile = File(
                    reportDirectory,
                    "VisionAssist_YOLO11n_Evaluation_" +
                            "${System.currentTimeMillis()}.csv"
                )

                val evaluator = CocoBatchEvaluator(detector)

                val result = evaluator.evaluate(
                    imagesDirectory = imageDirectory,
                    annotationFile = jsonFile,
                    outputCsv = csvFile,
                    iouThreshold = 0.50
                )

                statusText.text = """
                    YOLO11n EVALUATION COMPLETED

                    Model: YOLO11n TensorFlow Lite
                    IoU threshold: 0.50

                    Total images: ${result.totalImages}
                    Processed images: ${result.processedImages}
                    Failed images: ${result.failedImages}

                    GROUND-TRUTH AND PREDICTIONS

                    Ground-truth objects: ${result.totalGroundTruth}
                    Predicted objects: ${result.totalPredictions}

                    True positives: ${result.truePositives}
                    False positives: ${result.falsePositives}
                    False negatives: ${result.falseNegatives}

                    OBJECT-LEVEL METRICS

                    Precision: ${formatMetric(result.precision)}%
                    Recall: ${formatMetric(result.recall)}%
                    F1-score: ${formatMetric(result.f1Score)}%

                    Object detection Jaccard score:
                    ${formatMetric(result.objectDetectionAccuracy)}%

                    IMAGE-LEVEL ACCURACY

                    Image-Level Class-Presence Accuracy:
                    ${formatMetric(result.imageLevelAccuracy)}%

                    Image-Level TP: ${result.imageLevelTruePositives}
                    Image-Level TN: ${result.imageLevelTrueNegatives}
                    Image-Level FP: ${result.imageLevelFalsePositives}
                    Image-Level FN: ${result.imageLevelFalseNegatives}

                    PERFORMANCE

                    Average TFLite inference time:
                    ${formatMetric(result.averageInferenceTimeMs)} ms

                    CSV REPORT:

                    ${result.csvFile}
                """.trimIndent()

            } catch (e: Exception) {

                statusText.text =
                    "Evaluation failed:\n${e.localizedMessage}"

            } finally {

                folderButton.isEnabled = true
                jsonButton.isEnabled = true

                updateButtons()
            }
        }
    }

    private fun formatMetric(value: Double): String {
        return String.format(
            Locale.US,
            "%.2f",
            value
        )
    }

    override fun onDestroy() {

        if (::detector.isInitialized) {
            detector.close()
        }

        super.onDestroy()
    }
}
