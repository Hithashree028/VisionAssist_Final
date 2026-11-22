package com.hitha.visionassist.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device Optical Character Recognition (OCR) using Google ML Kit Text Recognition.
 * Operates offline, extracts text in natural reading order, cleans formatting,
 * and chunks long text for optimal Text-to-Speech playback.
 */
class OCRManager {

    private var recognizerInstance: com.google.mlkit.vision.text.TextRecognizer? = null

    private fun getRecognizer(): com.google.mlkit.vision.text.TextRecognizer {
        if (recognizerInstance == null) {
            recognizerInstance = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        return recognizerInstance!!
    }

    /**
     * Extracts text from the given bitmap, sorts blocks in top-to-bottom reading order,
     * and normalizes whitespace.
     */
    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        getRecognizer().process(inputImage)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks
                if (blocks.isEmpty()) {
                    continuation.resume("")
                    return@addOnSuccessListener
                }

                // Sort text blocks by vertical Y position, then horizontal X position for reading order
                val sortedBlocks = blocks.sortedWith(
                    compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })
                )

                val rawLines = mutableListOf<String>()
                for (block in sortedBlocks) {
                    for (line in block.lines) {
                        rawLines.add(line.text)
                    }
                }

                val cleaned = cleanAndDeduplicateLines(rawLines)
                continuation.resume(cleaned)
            }
            .addOnFailureListener {
                continuation.resume("")
            }
    }

    /**
     * Cleans whitespace, trims lines, and eliminates consecutive duplicate lines.
     */
    fun cleanAndDeduplicateLines(lines: List<String>): String {
        val cleanedLines = mutableListOf<String>()
        var lastLine = ""

        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isNotBlank() && !trimmed.equals(lastLine, ignoreCase = true)) {
                cleanedLines.add(trimmed)
                lastLine = trimmed
            }
        }

        return cleanedLines.joinToString(separator = "\n")
    }

    /**
     * Chunks text into speech-friendly utterances of up to maxWords.
     */
    fun chunkTextForSpeech(text: String, maxWords: Int = 50): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= maxWords) {
            return if (text.isNotBlank()) listOf(text) else emptyList()
        }

        val chunks = mutableListOf<String>()
        var currentChunk = mutableListOf<String>()

        for (word in words) {
            currentChunk.add(word)
            if (currentChunk.size >= maxWords) {
                chunks.add(currentChunk.joinToString(" "))
                currentChunk = mutableListOf()
            }
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.joinToString(" "))
        }

        return chunks
    }

    /**
     * Formats OCR text into a natural spoken statement.
     */
    fun formatOcrSpokenResponse(cleanedText: String): String {
        return if (cleanedText.isBlank()) {
            "No readable text was detected in this view. Please try holding the document closer and steady."
        } else {
            val singleSpokenString = cleanedText.replace("\n", ". ")
            "The visible text reads: $singleSpokenString"
        }
    }

    fun close() {
        try {
            recognizerInstance?.close()
            recognizerInstance = null
        } catch (_: Exception) {}
    }
}
