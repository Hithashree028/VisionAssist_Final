package com.hitha.visionassist

import android.graphics.Rect
import com.hitha.visionassist.data.model.DetectedObjectInfo
import com.hitha.visionassist.data.model.HorizontalPosition
import com.hitha.visionassist.speech.VoiceCommand
import com.hitha.visionassist.speech.VoiceCommandParser
import com.hitha.visionassist.vision.GeminiVisionClient
import com.hitha.visionassist.vision.OCRManager
import com.hitha.visionassist.vision.ObjectDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class VisionAssistLogicTest {

    private val ocrManager = OCRManager()
    private val objectDetectionEngine = ObjectDetectionEngine()
    private val geminiClient = GeminiVisionClient()

    // ==========================================
    // 1. VOICE COMMAND PARSER TESTS
    // ==========================================

    @Test
    fun testVoiceCommandParsing_wakeWordVariations() {
        assertTrue(VoiceCommandParser.parseCommand("hey vision assist") is VoiceCommand.WakeWord)
        assertTrue(VoiceCommandParser.parseCommand("hey visionassist") is VoiceCommand.WakeWord)
        assertTrue(VoiceCommandParser.parseCommand("vision assist") is VoiceCommand.WakeWord)
        assertTrue(VoiceCommandParser.parseCommand("HEY VISIONASSIST") is VoiceCommand.WakeWord)
    }

    @Test
    fun testVoiceCommandParsing_wakeWordWithAttachedCommand() {
        val cmd = VoiceCommandParser.parseCommand("hey visionassist describe the room")
        assertTrue(cmd is VoiceCommand.DescribeSurroundings)

        val cmd2 = VoiceCommandParser.parseCommand("hey vision assist read the text")
        assertTrue(cmd2 is VoiceCommand.ReadText)
    }

    @Test
    fun testVoiceCommandParsing_handsFreeRequiredWorkflowCommands() {
        // 1. "Hey VisionAssist, describe my surroundings."
        val cmd1 = VoiceCommandParser.parseCommand("Hey VisionAssist, describe my surroundings.")
        assertTrue(cmd1 is VoiceCommand.DescribeSurroundings)

        // 2. "Hey VisionAssist, read the text."
        val cmd2 = VoiceCommandParser.parseCommand("Hey VisionAssist, read the text.")
        assertTrue(cmd2 is VoiceCommand.ReadText)

        // 3. "Hey VisionAssist, detect objects."
        val cmd3 = VoiceCommandParser.parseCommand("Hey VisionAssist, detect objects.")
        assertTrue(cmd3 is VoiceCommand.DescribeSurroundings)

        // 4. "Hey VisionAssist, what is in front of me?"
        val cmd4 = VoiceCommandParser.parseCommand("Hey VisionAssist, what is in front of me?")
        assertTrue(cmd4 is VoiceCommand.DescribeSurroundings)

        // 5. "Hey VisionAssist, where is the chair?"
        val cmd5 = VoiceCommandParser.parseCommand("Hey VisionAssist, where is the chair?")
        assertTrue(cmd5 is VoiceCommand.SearchObject)
        assertEquals("chair", (cmd5 as VoiceCommand.SearchObject).target)

        // 6. "Hey VisionAssist, how many people are there?"
        val cmd6 = VoiceCommandParser.parseCommand("Hey VisionAssist, how many people are there?")
        assertTrue(cmd6 is VoiceCommand.CountObjects)
        assertEquals("people", (cmd6 as VoiceCommand.CountObjects).target)

        // 7. "Hey VisionAssist, describe this object."
        val cmd7 = VoiceCommandParser.parseCommand("Hey VisionAssist, describe this object.")
        assertTrue(cmd7 is VoiceCommand.DescribeSurroundings)
    }

    @Test
    fun testVoiceCommandParsing_conversationalFollowUpWorkflow() {
        // Follow up without wake word: "Where is the chair?"
        val cmd1 = VoiceCommandParser.parseCommand("Where is the chair?", isAwaitingFollowUp = true)
        assertTrue(cmd1 is VoiceCommand.SearchObject)
        assertEquals("chair", (cmd1 as VoiceCommand.SearchObject).target)

        // Follow up without wake word: "What is on the table?"
        val cmd2 = VoiceCommandParser.parseCommand("What is on the table?", isAwaitingFollowUp = true)
        assertTrue(cmd2 is VoiceCommand.AskQuestion)
        assertEquals("What is on the table?", (cmd2 as VoiceCommand.AskQuestion).question)

        // Follow up without wake word: "Read the text."
        val cmd3 = VoiceCommandParser.parseCommand("Read the text.", isAwaitingFollowUp = true)
        assertTrue(cmd3 is VoiceCommand.ReadText)

        // Follow up without wake word: "How many people are there?"
        val cmd4 = VoiceCommandParser.parseCommand("How many people are there?", isAwaitingFollowUp = true)
        assertTrue(cmd4 is VoiceCommand.CountObjects)
        assertEquals("people", (cmd4 as VoiceCommand.CountObjects).target)
    }

    @Test
    fun testVoiceCommandParsing_sceneDescriptions() {
        assertTrue(VoiceCommandParser.parseCommand("describe my surroundings") is VoiceCommand.DescribeSurroundings)
        assertTrue(VoiceCommandParser.parseCommand("what is in front of me") is VoiceCommand.DescribeSurroundings)
        assertTrue(VoiceCommandParser.parseCommand("what is around me") is VoiceCommand.DescribeSurroundings)
        assertTrue(VoiceCommandParser.parseCommand("tell me what you can see") is VoiceCommand.DescribeSurroundings)
        assertTrue(VoiceCommandParser.parseCommand("describe the room") is VoiceCommand.DescribeSurroundings)
        assertTrue(VoiceCommandParser.parseCommand("look around") is VoiceCommand.DescribeSurroundings)
        assertTrue(VoiceCommandParser.parseCommand("what do you see") is VoiceCommand.DescribeSurroundings)
    }

    @Test
    fun testVoiceCommandParsing_objectSearch() {
        val cmd1 = VoiceCommandParser.parseCommand("where is my bottle?")
        assertTrue(cmd1 is VoiceCommand.SearchObject)
        assertEquals("bottle", (cmd1 as VoiceCommand.SearchObject).target)

        val cmd2 = VoiceCommandParser.parseCommand("find my phone")
        assertTrue(cmd2 is VoiceCommand.SearchObject)
        assertEquals("phone", (cmd2 as VoiceCommand.SearchObject).target)

        val cmd3 = VoiceCommandParser.parseCommand("where is the laptop")
        assertTrue(cmd3 is VoiceCommand.SearchObject)
        assertEquals("laptop", (cmd3 as VoiceCommand.SearchObject).target)

        val cmd4 = VoiceCommandParser.parseCommand("can you find my keys")
        assertTrue(cmd4 is VoiceCommand.SearchObject)
        assertEquals("keys", (cmd4 as VoiceCommand.SearchObject).target)
    }

    @Test
    fun testVoiceCommandParsing_objectCounting() {
        val cmd1 = VoiceCommandParser.parseCommand("how many chairs are there?")
        assertTrue(cmd1 is VoiceCommand.CountObjects)
        assertEquals("chairs", (cmd1 as VoiceCommand.CountObjects).target)

        val cmd2 = VoiceCommandParser.parseCommand("count the people")
        assertTrue(cmd2 is VoiceCommand.CountObjects)
        assertEquals("people", (cmd2 as VoiceCommand.CountObjects).target)

        val cmd3 = VoiceCommandParser.parseCommand("how many bottles can you see")
        assertTrue(cmd3 is VoiceCommand.CountObjects)
        assertEquals("bottles", (cmd3 as VoiceCommand.CountObjects).target)
    }

    @Test
    fun testVoiceCommandParsing_ocr() {
        assertTrue(VoiceCommandParser.parseCommand("read the text") is VoiceCommand.ReadText)
        assertTrue(VoiceCommandParser.parseCommand("read this document") is VoiceCommand.ReadText)
        assertTrue(VoiceCommandParser.parseCommand("read the sign") is VoiceCommand.ReadText)
        assertTrue(VoiceCommandParser.parseCommand("what is written here?") is VoiceCommand.ReadText)
        assertTrue(VoiceCommandParser.parseCommand("read the label") is VoiceCommand.ReadText)
    }

    @Test
    fun testVoiceCommandParsing_controls() {
        assertTrue(VoiceCommandParser.parseCommand("repeat") is VoiceCommand.Repeat)
        assertTrue(VoiceCommandParser.parseCommand("say that again") is VoiceCommand.Repeat)
        assertTrue(VoiceCommandParser.parseCommand("what did you say") is VoiceCommand.Repeat)
        assertTrue(VoiceCommandParser.parseCommand("stop") is VoiceCommand.StopSpeaking)
        assertTrue(VoiceCommandParser.parseCommand("stop speaking") is VoiceCommand.StopSpeaking)
        assertTrue(VoiceCommandParser.parseCommand("be quiet") is VoiceCommand.StopSpeaking)
        assertTrue(VoiceCommandParser.parseCommand("pause") is VoiceCommand.StopSpeaking)
        assertTrue(VoiceCommandParser.parseCommand("start listening") is VoiceCommand.StartContinuousListening)
        assertTrue(VoiceCommandParser.parseCommand("stop listening") is VoiceCommand.StopContinuousListening)
        assertTrue(VoiceCommandParser.parseCommand("help") is VoiceCommand.Help)
        assertTrue(VoiceCommandParser.parseCommand("what can you do") is VoiceCommand.Help)
    }

    @Test
    fun testVoiceCommandParsing_questions() {
        val cmd1 = VoiceCommandParser.parseCommand("what color is the mug?")
        assertTrue(cmd1 is VoiceCommand.AskQuestion)
        assertEquals("what color is the mug?", (cmd1 as VoiceCommand.AskQuestion).question)

        val cmd2 = VoiceCommandParser.parseCommand("is there a door on the left?")
        assertTrue(cmd2 is VoiceCommand.AskQuestion)
    }

    // ==========================================
    // 2. OCR TEXT PROCESSING TESTS
    // ==========================================

    @Test
    fun testCleanAndDeduplicateLines() {
        val lines = listOf(
            "   Pharmacy   ",
            "Pharmacy",
            "Open 24 Hours",
            "",
            "   ",
            "Emergency Entrance"
        )
        val cleaned = ocrManager.cleanAndDeduplicateLines(lines)
        val expected = "Pharmacy\nOpen 24 Hours\nEmergency Entrance"
        assertEquals(expected, cleaned)
    }

    @Test
    fun testChunkTextForSpeech() {
        val words = (1..60).map { "word$it" }.joinToString(" ")
        val chunks = ocrManager.chunkTextForSpeech(words, maxWords = 25)
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].startsWith("word1"))
        assertTrue(chunks[2].endsWith("word60"))
    }

    @Test
    fun testFormatOcrSpokenResponse() {
        val emptyResult = ocrManager.formatOcrSpokenResponse("")
        assertTrue(emptyResult.contains("No readable text"))

        val textResult = ocrManager.formatOcrSpokenResponse("Exit\nTurn Right")
        assertEquals("The visible text reads: Exit. Turn Right", textResult)
    }

    // ==========================================
    // 3. OBJECT DETECTION & POSITION TESTS
    // ==========================================

    @Test
    fun testHorizontalPositionCalculation() {
        // Left region: center < 0.35 * width (e.g. center = 100 on width 1000 = 0.10)
        assertEquals(HorizontalPosition.LEFT, objectDetectionEngine.calculateHorizontalPosition(50f, 150f, 1000f))

        // Center region: 0.35 <= center <= 0.65 (e.g. center = 500 on width 1000 = 0.50)
        assertEquals(HorizontalPosition.CENTER, objectDetectionEngine.calculateHorizontalPosition(450f, 550f, 1000f))

        // Right region: center > 0.65 (e.g. center = 875 on width 1000 = 0.875)
        assertEquals(HorizontalPosition.RIGHT, objectDetectionEngine.calculateHorizontalPosition(800f, 950f, 1000f))
    }

    @Test
    fun testFindAndCountObjects() {
        val detected = listOf(
            DetectedObjectInfo("water bottle", 0.9f, HorizontalPosition.LEFT),
            DetectedObjectInfo("laptop", 0.95f, HorizontalPosition.CENTER),
            DetectedObjectInfo("chair", 0.85f, HorizontalPosition.RIGHT),
            DetectedObjectInfo("chair", 0.80f, HorizontalPosition.RIGHT)
        )

        val bottleResults = objectDetectionEngine.findObjects(detected, "bottle")
        assertEquals(1, bottleResults.size)
        assertEquals(HorizontalPosition.LEFT, bottleResults[0].position)

        val chairCount = objectDetectionEngine.countObjects(detected, "chair")
        assertEquals(2, chairCount)

        val phoneResults = objectDetectionEngine.findObjects(detected, "phone")
        assertEquals(0, phoneResults.size)
    }

    @Test
    fun testObjectDetectionEngine_synonymAndPluralMatching() {
        val detected = listOf(
            DetectedObjectInfo("chair", 0.9f, HorizontalPosition.LEFT),
            DetectedObjectInfo("person", 0.85f, HorizontalPosition.RIGHT),
            DetectedObjectInfo("laptop", 0.8f, HorizontalPosition.CENTER)
        )

        // Match "chair" and plural "chairs"
        val chairResults = objectDetectionEngine.findObjects(detected, "chair")
        assertEquals(1, chairResults.size)
        assertEquals(HorizontalPosition.LEFT, chairResults[0].position)

        val chairsResults = objectDetectionEngine.findObjects(detected, "chairs")
        assertEquals(1, chairsResults.size)

        // Match "people" to "person"
        val peopleResults = objectDetectionEngine.findObjects(detected, "people")
        assertEquals(1, peopleResults.size)
        assertEquals(HorizontalPosition.RIGHT, peopleResults[0].position)

        // Count objects with synonyms
        assertEquals(1, objectDetectionEngine.countObjects(detected, "people"))
        assertEquals(1, objectDetectionEngine.countObjects(detected, "chairs"))
    }

    @Test
    fun testFormatSpokenObjectSummary() {
        val detected = listOf(
            DetectedObjectInfo("water bottle", 0.9f, HorizontalPosition.LEFT),
            DetectedObjectInfo("laptop", 0.95f, HorizontalPosition.CENTER),
            DetectedObjectInfo("chair", 0.85f, HorizontalPosition.RIGHT, isBackground = true)
        )

        val summary = objectDetectionEngine.formatSpokenObjectSummary(detected)
        assertTrue(summary.contains("a water bottle on the left"))
        assertTrue(summary.contains("a laptop near the center"))
        assertTrue(summary.contains("1 object in the background"))
    }

    // ==========================================
    // 4. GEMINI VISION CLIENT TESTS (MOCKED PAYLOADS)
    // ==========================================

    @Test
    fun testParseGeminiResponse_validStructuredJson() {
        val sampleResponse = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "```json\n{\n  \"sceneDescription\": \"A well-lit classroom with desks.\",\n  \"visibleObjects\": [\n    {\"label\": \"laptop\", \"position\": \"center\"},\n    {\"label\": \"water bottle\", \"position\": \"left\"}\n  ],\n  \"visibleText\": null,\n  \"summaryForSpeech\": \"There is a table in front of you. A water bottle is on the left, and a black laptop is near the center.\"\n}\n```"
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val parsed = geminiClient.parseGeminiResponse(sampleResponse)
        assertEquals("A well-lit classroom with desks.", parsed.sceneDescription)
        assertEquals(
            "There is a table in front of you. A water bottle is on the left, and a black laptop is near the center.",
            parsed.summaryForSpeech
        )
        assertEquals(2, parsed.visibleObjects.size)
        assertEquals("laptop", parsed.visibleObjects[0].label)
        assertEquals(HorizontalPosition.CENTER, parsed.visibleObjects[0].position)
        assertEquals("water bottle", parsed.visibleObjects[1].label)
        assertEquals(HorizontalPosition.LEFT, parsed.visibleObjects[1].position)
    }

    @Test
    fun testParseGeminiResponse_plainTextFallback() {
        val sampleResponse = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "**Notice**: There is a white mug *near the center* of the wooden desk."
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val parsed = geminiClient.parseGeminiResponse(sampleResponse)
        // Markdown should be stripped for speech
        assertEquals("Notice: There is a white mug near the center of the wooden desk.", parsed.summaryForSpeech)
    }

    @Test(expected = Exception::class)
    fun testParseGeminiResponse_safetyBlocked() {
        val blockedResponse = """
        {
          "candidates": [
            {
              "finishReason": "SAFETY"
            }
          ]
        }
        """.trimIndent()

        geminiClient.parseGeminiResponse(blockedResponse)
    }

    @Test(expected = Exception::class)
    fun testParseGeminiResponse_emptyCandidates() {
        val emptyResponse = """{ "candidates": [] }"""
        geminiClient.parseGeminiResponse(emptyResponse)
    }

    @Test
    fun testSpecificObjectIdentification_preservesExactNames() {
        val sampleResponse = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "```json\n{\n  \"sceneDescription\": \"A home office desk setup.\",\n  \"visibleObjects\": [\n    {\"label\": \"water bottle\", \"position\": \"left\"},\n    {\"label\": \"laptop\", \"position\": \"center\"},\n    {\"label\": \"chair\", \"position\": \"right\"},\n    {\"label\": \"phone\", \"position\": \"left\"}\n  ],\n  \"visibleText\": null,\n  \"summaryForSpeech\": \"There is a wooden table in front of you. A blue water bottle and a phone are on the left, a black laptop is near the center, and a chair is on the right.\"\n}\n```"
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val parsed = geminiClient.parseGeminiResponse(sampleResponse)
        val objectLabels = parsed.visibleObjects.map { it.label }

        assertTrue(objectLabels.contains("water bottle"))
        assertTrue(objectLabels.contains("laptop"))
        assertTrue(objectLabels.contains("chair"))
        assertTrue(objectLabels.contains("phone"))
        assertTrue(parsed.summaryForSpeech.contains("water bottle"))
        assertTrue(parsed.summaryForSpeech.contains("laptop"))
        assertTrue(parsed.summaryForSpeech.contains("chair"))
        assertTrue(parsed.summaryForSpeech.contains("phone"))
    }
}
