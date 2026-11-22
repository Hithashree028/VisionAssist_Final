package com.hitha.visionassist.speech

import java.util.Locale

/**
 * Parsed intention from user voice command.
 */
sealed class VoiceCommand {
    object WakeWord : VoiceCommand()
    object DescribeSurroundings : VoiceCommand()
    data class SearchObject(val target: String) : VoiceCommand()
    data class CountObjects(val target: String) : VoiceCommand()
    object ReadText : VoiceCommand()
    data class AskQuestion(val question: String) : VoiceCommand()
    object Repeat : VoiceCommand()
    object StopSpeaking : VoiceCommand()
    object StartContinuousListening : VoiceCommand()
    object StopContinuousListening : VoiceCommand()
    object Help : VoiceCommand()
    data class Unknown(val rawText: String) : VoiceCommand()
}

/**
 * Pure parsing logic for user speech utterances.
 * Robust against punctuation, Vosk acoustic model approximations,
 * compound wake-word + command utterances, and hands-free conversational follow-up questions.
 */
object VoiceCommandParser {

    private val wakePrefixes = listOf(
        "hey vision assistance",
        "hey vision assistant",
        "hey vision assist",
        "hey visionassist",
        "hi vision assist",
        "the vision assist",
        "division assist",
        "decision assist",
        "television assist",
        "hey visual assist",
        "hey vision",
        "vision assistance",
        "vision assistant",
        "vision assists",
        "vision assist",
        "visionassist"
    )

    fun parseCommand(rawText: String, isAwaitingFollowUp: Boolean = false): VoiceCommand {
        val cleaned = normalizeText(rawText)

        if (cleaned.isBlank()) {
            return VoiceCommand.Unknown(rawText)
        }

        // 1. Check for Wake Phrase (with or without attached command)
        if (isWakeWord(cleaned)) {
            val stripped = stripWakeWord(cleaned)
            if (stripped.isNotBlank()) {
                // Compound utterance: e.g. "hey visionassist describe the room" or "hey vision assist read the text"
                return parseCommand(stripped, isAwaitingFollowUp = isAwaitingFollowUp)
            }
            // Wake phrase only: e.g. "hey visionassist"
            return VoiceCommand.WakeWord
        }

        // 2. Control commands (highest priority for user control)
        if (isStopCommand(cleaned)) {
            return VoiceCommand.StopSpeaking
        }

        if (isRepeatCommand(cleaned)) {
            return VoiceCommand.Repeat
        }

        if (cleaned.contains("start listening") || cleaned.contains("enable listening") || cleaned == "resume") {
            return VoiceCommand.StartContinuousListening
        }

        if (cleaned.contains("stop listening") || cleaned.contains("disable listening")) {
            return VoiceCommand.StopContinuousListening
        }

        if (isHelpCommand(cleaned)) {
            return VoiceCommand.Help
        }

        // 3. OCR / Read text commands
        if (isOcrCommand(cleaned)) {
            return VoiceCommand.ReadText
        }

        // 4. Object Search (e.g., "where is my bottle", "find my phone", "where is the chair")
        val searchTarget = extractSearchTarget(cleaned)
        if (searchTarget != null) {
            return VoiceCommand.SearchObject(searchTarget)
        }

        // 5. Object Counting (e.g., "how many chairs are there", "count the people")
        val countTarget = extractCountTarget(cleaned)
        if (countTarget != null) {
            return VoiceCommand.CountObjects(countTarget)
        }

        // 6. Scene and object description commands
        if (isSceneDescriptionCommand(cleaned)) {
            return VoiceCommand.DescribeSurroundings
        }

        // 7. Conversational follow-up question / inquiry
        if (isAwaitingFollowUp || isLikelyQuestion(cleaned)) {
            return VoiceCommand.AskQuestion(rawText.trim())
        }

        return VoiceCommand.Unknown(rawText)
    }

    fun isWakeWord(text: String): Boolean {
        val norm = normalizeText(text)
        for (prefix in wakePrefixes) {
            if (norm == prefix || norm.startsWith("$prefix ")) {
                return true
            }
        }
        return false
    }

    fun stripWakeWord(text: String): String {
        var norm = normalizeText(text)
        for (prefix in wakePrefixes) {
            if (norm == prefix) {
                return ""
            }
            if (norm.startsWith("$prefix ")) {
                norm = norm.removePrefix(prefix).trim()
                break
            }
        }
        // Also strip polite or introductory prefixes
        norm = norm.removePrefix("please ")
            .removePrefix("can you ")
            .removePrefix("could you ")
            .removePrefix("would you ")
            .removePrefix("will you ")
            .removePrefix("now ")
            .trim()
        return norm
    }

    private fun normalizeText(text: String): String {
        return text.lowercase(Locale.US)
            .replace(Regex("[,.!?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isStopCommand(text: String): Boolean {
        return text == "stop" ||
                text == "pause" ||
                text == "silence" ||
                text == "be quiet" ||
                text == "cancel" ||
                text.contains("stop speaking") ||
                text.contains("shut up") ||
                text.contains("quiet please")
    }

    private fun isRepeatCommand(text: String): Boolean {
        return text == "repeat" ||
                text.contains("repeat that") ||
                text.contains("say that again") ||
                text.contains("what did you say") ||
                text.contains("say again") ||
                text.contains("repeat please")
    }

    private fun isHelpCommand(text: String): Boolean {
        return text == "help" ||
                text.contains("what can you do") ||
                text.contains("help me") ||
                text.contains("available commands") ||
                text.contains("how do i use")
    }

    private fun isOcrCommand(text: String): Boolean {
        return text.contains("read text") ||
                text.contains("read the text") ||
                text.contains("read this text") ||
                text.contains("read visible text") ||
                text.contains("read this document") ||
                text.contains("read document") ||
                text.contains("read sign") ||
                text.contains("read the sign") ||
                text.contains("read the label") ||
                text.contains("read label") ||
                text.contains("what is written") ||
                text.contains("what does it say") ||
                text.contains("read aloud") ||
                (text.startsWith("read ") && !text.contains("surroundings"))
    }

    private fun isSceneDescriptionCommand(text: String): Boolean {
        return text.contains("describe my surroundings") ||
                text.contains("describe surroundings") ||
                text.contains("what is around me") ||
                text.contains("tell me what you can see") ||
                text.contains("what can you see") ||
                text.contains("describe the room") ||
                text.contains("describe the scene") ||
                text.contains("what is in front of me") ||
                text.contains("what's in front of me") ||
                text.contains("what is in front") ||
                text.contains("look around") ||
                text.contains("what do you see") ||
                text.contains("take a picture") ||
                text.contains("look at this") ||
                text.contains("detect objects") ||
                text.contains("detect the objects") ||
                text.contains("detect object") ||
                text.contains("identify objects") ||
                text.contains("describe this object") ||
                text.contains("describe the object") ||
                text.contains("describe object") ||
                text == "what is this" ||
                text == "what is this object" ||
                text == "what is in my hand"
    }

    fun extractSearchTarget(text: String): String? {
        val whereRegex = Regex("^(?:where is|where's|find|can you find|can you see|locate|search for|look for)\\s+(?:my|the|a|an)?\\s*(.+?)(?:\\?|$)", RegexOption.IGNORE_CASE)
        val match = whereRegex.find(text)
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank() &&
                !candidate.contains("surroundings") &&
                !candidate.contains("text") &&
                !candidate.contains("object")
            ) {
                return candidate
            }
        }
        return null
    }

    fun extractCountTarget(text: String): String? {
        val countRegex = Regex("^(?:how many|count the|count)\\s+(?:of\\s+)?(?:the\\s+)?(.+?)(?:\\s+are there|\\s+can you see|\\s+visible|\\s+do you see|\\s+are in front of me|\\?|$)", RegexOption.IGNORE_CASE)
        val match = countRegex.find(text)
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank()) {
                return candidate
            }
        }
        return null
    }

    fun isLikelyQuestion(text: String): Boolean {
        val questionStarters = listOf(
            "what", "where", "who", "which", "how many", "how much", "how",
            "what color", "what colour", "is there", "are there", "tell me", "can you", "could you", "does it"
        )
        return questionStarters.any { text.startsWith(it) } || text.endsWith("?")
    }
}
