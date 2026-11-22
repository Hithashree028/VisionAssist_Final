package com.hitha.visionassist.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hitha.visionassist.data.model.AssistantState

/**
 * Prominent visual and accessible voice assistant status card.
 * Provides clear pulsing feedback during active listening, high-contrast typography,
 * and large touch target for visually impaired accessibility.
 */
@Composable
fun VoiceAssistantHeroCard(
    assistantState: AssistantState,
    statusMessage: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isListening = assistantState == AssistantState.LISTENING
    val isAnalyzing = assistantState == AssistantState.ANALYZING
    val isSpeaking = assistantState == AssistantState.SPEAKING
    val isCapturing = assistantState == AssistantState.CAPTURING
    val isError = assistantState == AssistantState.ERROR

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val primaryColor by animateColorAsState(
        targetValue = when {
            isListening -> Color(0xFF00E676) // Bright Accessible Green
            isCapturing -> Color(0xFFFFD600) // Amber
            isAnalyzing -> Color(0xFF40C4FF) // Cyan
            isSpeaking -> Color(0xFFB388FF)  // Purple
            isError -> Color(0xFFFF5252)     // Red
            else -> Color(0xFF00E5FF)        // Default Cyan/Teal
        },
        label = "heroColor"
    )

    val accessibilityLabel = buildString {
        append("Assistant status: ")
        append(assistantState.name.lowercase().replaceFirstChar { it.uppercase() })
        append(". ")
        append(statusMessage)
        append(". Double tap to ")
        if (isListening) append("pause voice assistant.") else append("start voice assistant.")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = accessibilityLabel
                role = Role.Button
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(2.dp, primaryColor.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(90.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.2f))
                    .border(3.dp, primaryColor, CircleShape)
            ) {
                Text(
                    text = when (assistantState) {
                        AssistantState.LISTENING -> "MIC"
                        AssistantState.CAPTURING -> "CAM"
                        AssistantState.ANALYZING -> "AI"
                        AssistantState.SPEAKING -> "TTS"
                        AssistantState.ERROR -> "!"
                        else -> "OFF"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryColor,
                    fontSize = 20.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = when (assistantState) {
                    AssistantState.LISTENING -> "Listening for 'Hey VisionAssist'…"
                    AssistantState.CAPTURING -> "Capturing Camera Image…"
                    AssistantState.ANALYZING -> "Analyzing Visual Scene…"
                    AssistantState.SPEAKING -> "Speaking Response…"
                    AssistantState.INITIALIZING -> "Initializing Voice Engine…"
                    AssistantState.ERROR -> "Service Attention Required"
                    AssistantState.IDLE -> "Voice Assistant Paused"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Surface(
                color = primaryColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isListening) "Tap to Pause" else "Tap to Start Assistant",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }
        }
    }
}
