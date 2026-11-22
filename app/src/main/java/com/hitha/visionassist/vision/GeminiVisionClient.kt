package com.hitha.visionassist.vision

import android.util.Base64
import com.hitha.visionassist.BuildConfig
import com.hitha.visionassist.data.model.DetectedObjectInfo
import com.hitha.visionassist.data.model.GeminiAnalysisData
import com.hitha.visionassist.data.model.HorizontalPosition
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handles communication with Google Gemini Multimodal Vision API.
 * Receives the real captured image bytes, asks for structured environmental and object understanding,
 * and parses response into typed GeminiAnalysisData.
 */
class GeminiVisionClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val proxyEndpoint: String? = null
) {

    private val supportedModels = listOf(
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-3.5-flash",
        "gemini-flash-latest"
    )

    /**
     * Analyses surroundings or answers questions from raw image bytes.
     */
    @Throws(Exception::class)
    fun analyzeImage(
        imageBytes: ByteArray,
        userQuestion: String? = null,
        targetObject: String? = null,
        isCounting: Boolean = false
    ): GeminiAnalysisData {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank() || apiKey.equals("YOUR_API_KEY", ignoreCase = true)) {
            throw IllegalArgumentException("API_KEY_MISSING")
        }

        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val prompt = buildAnalysisPrompt(userQuestion, targetObject, isCounting)

        var lastException: Exception? = null

        for (model in supportedModels) {
            try {
                val responseJson = executeRequest(apiKey, model, prompt, base64Image)
                return parseGeminiResponse(responseJson)
            } catch (e: Exception) {
                lastException = e
                // If model was not found (404), fall through to next candidate model
                if (e.message?.contains("HTTP 404") == true) {
                    continue
                }
                // For critical errors like auth or quota, stop early
                if (e.message?.contains("HTTP 401") == true ||
                    e.message?.contains("HTTP 403") == true ||
                    e.message?.contains("HTTP 429") == true
                ) {
                    throw e
                }
            }
        }

        throw lastException ?: IOException("Failed to communicate with Gemini vision API")
    }

    private fun buildAnalysisPrompt(
        userQuestion: String?,
        targetObject: String?,
        isCounting: Boolean
    ): String {
        return when {
            !targetObject.isNullOrBlank() && isCounting -> {
                """You are VisionAssist, an assistive vision system for visually impaired people.
Analyze this image and count the following items: "$targetObject".
Respond in valid JSON format only with these keys:
{
  "sceneDescription": "Brief scene description",
  "visibleObjects": [
    {"label": "$targetObject", "position": "left" | "center" | "right"}
  ],
  "visibleText": null,
  "summaryForSpeech": "Natural speech statement stating the exact count and their positions (left, center, right)."
}
Do not use markdown blocks, asterisks, or extra symbols."""
            }
            !targetObject.isNullOrBlank() -> {
                """You are VisionAssist, an assistive vision system for visually impaired people.
The user is asking: "Where is my $targetObject?" or "Find my $targetObject".
Search the image for: "$targetObject".
Respond in valid JSON format only with these keys:
{
  "sceneDescription": "Brief scene context",
  "visibleObjects": [
    {"label": "$targetObject", "position": "left" | "center" | "right"}
  ],
  "visibleText": null,
  "summaryForSpeech": "Clear speech telling if $targetObject is visible and its exact horizontal position (on the left, near the center, or on the right). If not found, say so honestly."
}
Do not use markdown blocks, asterisks, or extra symbols."""
            }
            !userQuestion.isNullOrBlank() -> {
                """You are VisionAssist, assisting a visually impaired user.
Question: "$userQuestion"
Answer accurately based only on what is visibly confirmed in the image.
Respond in valid JSON format with keys:
{
  "sceneDescription": "Brief scene context",
  "visibleObjects": [],
  "visibleText": "Any relevant text",
  "summaryForSpeech": "Natural, direct spoken answer to the question in 1 or 2 concise sentences."
}
Do not use markdown blocks or formatting."""
            }
            else -> {
                """You are VisionAssist, an assistive visual guide for a blind or visually impaired person.
Inspect the image carefully and identify specific visible objects using their exact names (such as laptop, water bottle, phone, chair, table, cup, notebook, keyboard, person, door, backpack, etc.).
State where each specific object is located: on the left, near the center, or on the right.
Identify key relationships (e.g. laptop on the table, bottle to the left of the laptop).
Never use vague generic words like 'an object' when you can name the specific object.
Respond in valid JSON format only:
{
  "sceneDescription": "Detailed overview of the scene mentioning the environment and key items",
  "visibleObjects": [
    {"label": "specific object name", "position": "left" | "center" | "right"}
  ],
  "visibleText": "any readable text or null",
  "summaryForSpeech": "A clear, natural spoken description naming each specific object and its position. Example: 'There is a wooden table in front of you. A blue water bottle is on the left, a black laptop is near the center, and a chair is on the right.' Do not use markdown, asterisks, or bullet points."
}
Do not guess physical distances in meters or feet. Never invent objects that are not clearly visible."""
            }
        }
    }

    private fun executeRequest(
        apiKey: String,
        model: String,
        prompt: String,
        base64Image: String
    ): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", base64Image)
                )
            )

        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val root = JSONObject().put("contents", contents)

        val url = proxyEndpoint ?: "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val requestBody = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    when (response.code) {
                        400 -> throw IOException("HTTP 400: Bad request or unsupported image format")
                        401, 403 -> throw IOException("HTTP ${response.code}: Invalid or unauthorized Gemini API key")
                        404 -> throw IOException("HTTP 404: Model $model not found")
                        429 -> throw IOException("HTTP 429: API rate limit or quota exceeded")
                        500, 502, 503 -> throw IOException("HTTP ${response.code}: Gemini service temporary server error")
                        else -> throw IOException("HTTP ${response.code}: $body")
                    }
                }
                return body
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("Request timeout while contacting vision service", e)
        }
    }

    /**
     * Parses the Gemini response JSON, handling structured JSON payload and safety blockages.
     */
    fun parseGeminiResponse(responseJsonStr: String): GeminiAnalysisData {
        val root = JSONObject(responseJsonStr)

        val promptFeedback = root.optJSONObject("promptFeedback")
        val blockReason = promptFeedback?.optString("blockReason")
        if (!blockReason.isNullOrBlank()) {
            throw IOException("SAFETY_BLOCKED: Prompt blocked by AI safety filters ($blockReason)")
        }

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw IOException("EMPTY_RESPONSE: No visual analysis candidates returned")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val finishReason = firstCandidate.optString("finishReason", "")
        if (finishReason.equals("SAFETY", ignoreCase = true)) {
            throw IOException("SAFETY_BLOCKED: Visual analysis blocked by safety filters")
        }

        val content = firstCandidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            throw IOException("EMPTY_RESPONSE: No content parts in response")
        }

        val rawText = buildString {
            for (i in 0 until parts.length()) {
                append(parts.getJSONObject(i).optString("text", ""))
            }
        }.trim()

        if (rawText.isBlank()) {
            throw IOException("EMPTY_RESPONSE: Blank text returned from vision model")
        }

        return parseStructuredAnalysisText(rawText)
    }

    /**
     * Extracts and validates structured JSON from Gemini text, or provides robust fallback.
     */
    fun parseStructuredAnalysisText(text: String): GeminiAnalysisData {
        // Strip markdown backticks if returned
        val cleanText = text.replace("```json", "")
            .replace("```", "")
            .trim()

        val jsonStartIndex = cleanText.indexOf('{')
        val jsonEndIndex = cleanText.lastIndexOf('}')

        if (jsonStartIndex != -1 && jsonEndIndex > jsonStartIndex) {
            val jsonSubstring = cleanText.substring(jsonStartIndex, jsonEndIndex + 1)
            try {
                val json = JSONObject(jsonSubstring)
                val sceneDescription = json.optString("sceneDescription", "").trim()
                val summaryForSpeech = sanitizeForSpeech(json.optString("summaryForSpeech", sceneDescription))
                val visibleText = json.optString("visibleText").takeIf { it.isNotBlank() && it != "null" }

                val objectsList = mutableListOf<DetectedObjectInfo>()
                val objectsArray = json.optJSONArray("visibleObjects")
                if (objectsArray != null) {
                    for (i in 0 until objectsArray.length()) {
                        val obj = objectsArray.optJSONObject(i) ?: continue
                        val label = obj.optString("label", "object")
                        val posStr = obj.optString("position", "center").lowercase(Locale.US)
                        val position = when {
                            posStr.contains("left") -> HorizontalPosition.LEFT
                            posStr.contains("right") -> HorizontalPosition.RIGHT
                            else -> HorizontalPosition.CENTER
                        }
                        objectsList.add(DetectedObjectInfo(label = label, confidence = 0.9f, position = position))
                    }
                }

                val finalSpeech = if (summaryForSpeech.isBlank() || summaryForSpeech.equals("Scene analyzed.", ignoreCase = true)) {
                    if (objectsList.isNotEmpty()) {
                        val itemsStr = objectsList.joinToString(", ") { "${it.label} ${it.spokenPosition}" }
                        "I can see $itemsStr."
                    } else if (sceneDescription.isNotBlank()) {
                        sceneDescription
                    } else {
                        "Scene analyzed."
                    }
                } else {
                    summaryForSpeech
                }

                return GeminiAnalysisData(
                    sceneDescription = sceneDescription.ifBlank { finalSpeech },
                    visibleObjects = objectsList,
                    visibleText = visibleText,
                    summaryForSpeech = finalSpeech
                )
            } catch (_: Exception) {
                // Fall back to plain text parsing
            }
        }

        // Fallback if not JSON
        val spoken = sanitizeForSpeech(cleanText)
        return GeminiAnalysisData(
            sceneDescription = spoken,
            visibleObjects = emptyList(),
            visibleText = null,
            summaryForSpeech = spoken
        )
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw.replace(Regex("[*#_`~>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
